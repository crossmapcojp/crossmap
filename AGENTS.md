IMPORTANT: When applicable, prefer using intellij-index MCP tools for code navigation and refactoring.

Modules:
* core, app, cli modules are written in Kotlin/Common, so use kotlin standard libraries and KMP libraries only, do not write JDK specific Kotlin/JVM which will not work in other platforms. use KotlinLogging for log.
* crawl, server modules are written in Kotlin/JVM so you can use any JDK features with Kotlin/JVM and any KMP libraries. use logback for log.
