package jp.co.crossmap

import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Minimal test-only CDP client for real click and browser-history flows in Lightpanda. */
internal class LightPandaCdpSession private constructor(
    private val process: Process,
    private val socket: WebSocket,
    private val listener: MessageListener,
) : AutoCloseable {
    private val nextId = AtomicInteger()
    private val json = Json { ignoreUnknownKeys = true }
    private var sessionId: String? = null

    fun evaluate(expression: String): String? {
        val response = command(
            "Runtime.evaluate",
            buildJsonObject {
                put("expression", expression)
                put("returnByValue", true)
                put("awaitPromise", true)
            },
        )
        return (response["result"]?.jsonObject?.get("result")?.jsonObject?.get("value") as? JsonPrimitive)?.content
    }

    fun waitUntil(timeout: Duration = Duration.ofSeconds(15), condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        error("Lightpanda CDP condition was not satisfied within ${timeout.toSeconds()} seconds")
    }

    private fun command(method: String, parameters: JsonObject = buildJsonObject {}): JsonObject {
        val id = nextId.incrementAndGet()
        socket.sendText(
            buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", parameters)
                sessionId?.let { put("sessionId", it) }
            }.toString(),
            true,
        ).join()
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline) {
            val deferred = mutableListOf<String>()
            while (true) {
                val message = listener.messages.poll() ?: break
                val parsed = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull()
                if ((parsed?.get("id") as? JsonPrimitive)?.content == id.toString()) {
                    deferred.forEach(listener.messages::add)
                    parsed["error"]?.let { error("CDP $method failed: $it") }
                    return parsed
                }
                deferred += message
            }
            deferred.forEach(listener.messages::add)
            Thread.sleep(10)
        }
        error("Timed out waiting for CDP response to $method")
    }

    override fun close() {
        runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join() }
        process.destroy()
        if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
    }

    companion object {
        fun open(url: String, geolocation: GeoPoint? = null): LightPandaCdpSession {
            val port = ServerSocket(0).use { it.localPort }
            val binary = System.getenv("LIGHTPANDA_BINARY")?.takeIf(String::isNotBlank) ?: "lightpanda"
            val process = ProcessBuilder(binary, "serve", "--host", "127.0.0.1", "--port", port.toString())
                .redirectErrorStream(true)
                .start()
            try {
                awaitEndpoint(port)
                val version = http("http://127.0.0.1:$port/json/version")
                val webSocketUrl = Json.parseToJsonElement(version).jsonObject
                    .getValue("webSocketDebuggerUrl").let { (it as JsonPrimitive).content }
                val listener = MessageListener()
                val socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI(webSocketUrl), listener)
                    .join()
                return LightPandaCdpSession(process, socket, listener).also {
                    val targetId = it.command(
                        "Target.createTarget",
                        buildJsonObject { put("url", if (geolocation == null) url else "about:blank") },
                    )["result"]!!.jsonObject.getValue("targetId").let { value -> (value as JsonPrimitive).content }
                    it.sessionId = it.command(
                        "Target.attachToTarget",
                        buildJsonObject {
                            put("targetId", targetId)
                            put("flatten", true)
                        },
                    )["result"]!!.jsonObject.getValue("sessionId").let { value -> (value as JsonPrimitive).content }
                    it.command("Page.enable")
                    it.command("Runtime.enable")
                    geolocation?.let { point ->
                        it.command(
                            "Page.addScriptToEvaluateOnNewDocument",
                            buildJsonObject {
                                put(
                                    "source",
                                    """
                                    Object.defineProperty(navigator, "geolocation", {
                                      configurable: true,
                                      value: {
                                        getCurrentPosition(success) {
                                          success({coords: {
                                            latitude: ${point.latitude},
                                            longitude: ${point.longitude},
                                            accuracy: 10
                                          }});
                                        }
                                      }
                                    });
                                    """.trimIndent(),
                                )
                            },
                        )
                        it.command("Page.navigate", buildJsonObject { put("url", url) })
                    }
                }
            } catch (error: Throwable) {
                process.destroyForcibly()
                throw error
            }
        }

        private fun awaitEndpoint(port: Int) {
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            var lastError: Throwable? = null
            while (System.nanoTime() < deadline) {
                try {
                    http("http://127.0.0.1:$port/json/version")
                    return
                } catch (error: Throwable) {
                    lastError = error
                    Thread.sleep(50)
                }
            }
            error("Lightpanda CDP did not start: ${lastError?.message}")
        }

        private fun http(url: String, method: String = "GET"): String {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = 500
            connection.readTimeout = 2_000
            return try {
                check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode} for $url" }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
    }

    private class MessageListener : WebSocket.Listener {
        val messages = ConcurrentLinkedQueue<String>()
        private val current = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            current.append(data)
            if (last) {
                messages.add(current.toString())
                current.setLength(0)
            }
            webSocket.request(1)
            return null
        }

        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            webSocket.request(1)
            return null
        }
    }
}
