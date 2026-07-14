package jp.co.crossmap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath

private sealed interface AppState {
    data object NoIndex : AppState
    data object Ready : AppState
    data object Loading : AppState
    data class Results(val response: ChurchSearchResponse) : AppState
    data class Detail(val church: ChurchDetailResponse) : AppState
    data class Error(val message: String) : AppState
}

@Composable
@Preview
fun App(
    appDataPath: String? = null,
    serverBaseUrl: String = "http://10.0.2.2:8080",
    locationProvider: suspend () -> GeoPoint? = { null },
    openUrl: (String) -> Unit = {},
) {
    val manager = remember(appDataPath, serverBaseUrl) {
        appDataPath?.let { SnapshotManager(it.toPath(), serverBaseUrl, HttpClient()) }
    }
    var engine by remember(manager) { mutableStateOf(manager?.activeEngine()) }
    var state: AppState by remember(engine) { mutableStateOf(if (engine == null) AppState.NoIndex else AppState.Ready) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Crossmap", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("教会を探す", style = MaterialTheme.typography.headlineLarge)
            if (engine == null) {
                Text("検索データをダウンロードすると、端末上でオフライン検索できます。")
                Button(
                    enabled = manager != null && state !is AppState.Loading,
                    onClick = {
                        scope.launch {
                            state = AppState.Loading
                            state = runCatching {
                                manager!!.update()
                                engine = manager.activeEngine() ?: error("Downloaded index could not be opened")
                                AppState.Ready
                            }.getOrElse { AppState.Error(it.message ?: "Download failed") }
                        }
                    },
                ) { Text("検索データをダウンロード") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("教会名・地域・キーワード") },
                    )
                    Button(
                        enabled = query.isNotBlank() && state !is AppState.Loading,
                        onClick = {
                            scope.launch {
                                state = AppState.Loading
                                state = runCatching {
                                    var response = withContext(Dispatchers.Default) {
                                        engine!!.search(ChurchSearchRequest(query))
                                    }
                                    if (response.resolvedLocations.isEmpty()) {
                                        locationProvider()?.let { location ->
                                            response = withContext(Dispatchers.Default) {
                                                engine!!.search(ChurchSearchRequest(query, userLocation = location))
                                            }
                                        }
                                    }
                                    AppState.Results(response)
                                }.getOrElse { AppState.Error(it.message ?: "Search failed") }
                            }
                        },
                    ) { Text("検索") }
                }
            }
            when (val current = state) {
                AppState.Loading -> CircularProgressIndicator()
                is AppState.Error -> Text(current.message, color = MaterialTheme.colorScheme.error)
                is AppState.Results -> {
                    Text("${current.response.total}件")
                    if (current.response.hits.isEmpty()) {
                        Text("該当する教会が見つかりませんでした。検索語や地域を変えてください。")
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(current.response.hits, key = { it.churchId }) { hit ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    state = engine?.church(hit.churchId)?.let { AppState.Detail(it) }
                                        ?: AppState.Error("Church detail is unavailable: ${hit.churchId}")
                                },
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(hit.name, style = MaterialTheme.typography.titleMedium)
                                    Text(hit.address, style = MaterialTheme.typography.bodyMedium)
                                    hit.distanceKm?.let { Text("${((it * 10).toInt() / 10.0)} km") }
                                    hit.matchedPages.firstOrNull()?.snippet?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
                is AppState.Detail -> {
                    Button(onClick = { state = AppState.Ready }) { Text("検索に戻る") }
                    Text(current.church.name, style = MaterialTheme.typography.headlineMedium)
                    Text(current.church.englishName, style = MaterialTheme.typography.titleMedium)
                    current.church.denominationId?.let { Text("教派: $it") }
                    Text(current.church.address)
                    if (current.church.websiteUrl.isNotBlank()) {
                        Button(onClick = { openUrl(current.church.websiteUrl) }) { Text("ウェブサイト") }
                    }
                    current.church.socialProfiles.forEach { profile ->
                        Button(onClick = { openUrl(profile.url) }) { Text(profile.platform.name) }
                    }
                }
                else -> Unit
            }
        }
    }
}
