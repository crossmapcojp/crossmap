package jp.co.crossmap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import crossmap.app.shared.generated.resources.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource

private sealed interface AppState {
    data object NoIndex : AppState
    data object Ready : AppState
    data object Loading : AppState
    data class Results(val response: ChurchSearchResponse) : AppState
    data class Detail(val church: ChurchDetailResponse) : AppState
    data class Error(val message: String) : AppState
}

internal fun preferredChurchName(
    localizedNames: List<LocalizedName>,
    languageCode: String,
    fallbackName: String,
    englishName: String,
): String = localizedDomainText(
    Language.fromCodeOrEnglish(languageCode),
    localizedNames,
    englishName,
    fallbackName,
) ?: fallbackName

internal fun preferredDenominationName(
    localizedNames: List<LocalizedName>,
    languageCode: String,
    fallbackId: String?,
): String? = localizedDomainText(Language.fromCodeOrEnglish(languageCode), localizedNames)
    ?: fallbackId?.takeIf(String::isNotBlank)

@Composable
private fun appString(
    language: Language,
    osLocaleCode: String,
    key: String,
    resource: StringResource,
    vararg arguments: Any,
): String {
    val systemValue = stringResource(resource, *arguments)
    return if (language == Language.fromCodeOrEnglish(osLocaleCode)) {
        systemValue
    } else {
        GeneratedAppUiMessages.text(language, key, *arguments)
    }
}

@Composable
@Preview
fun App(
    appDataPath: String? = null,
    serverBaseUrl: String = "http://10.0.2.2:8080",
    locationProvider: suspend () -> GeoPoint? = { null },
    openUrl: (String) -> Unit = {},
    osLocaleCode: String = "en",
    languagePreferences: UiLanguagePreferences = NoOpUiLanguagePreferences,
) {
    val manager = remember(appDataPath, serverBaseUrl) {
        appDataPath?.let { SnapshotManager(it.toPath(), serverBaseUrl, HttpClient()) }
    }
    var displayLanguage by remember {
        mutableStateOf(initialUiLanguage(languagePreferences.readLanguageCode(), osLocaleCode))
    }
    var engine by remember(manager, displayLanguage) { mutableStateOf(manager?.activeEngine(displayLanguage.code)) }
    var state: AppState by remember(engine) { mutableStateOf(if (engine == null) AppState.NoIndex else AppState.Ready) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val siteName = appString(displayLanguage, osLocaleCode, "site_name", Res.string.site_name)
    val searchHeading = appString(displayLanguage, osLocaleCode, "search_heading", Res.string.search_heading)
    val downloadPrompt = appString(displayLanguage, osLocaleCode, "download_index_prompt", Res.string.download_index_prompt)
    val downloadButton = appString(displayLanguage, osLocaleCode, "download_index_button", Res.string.download_index_button)
    val downloadFailed = appString(displayLanguage, osLocaleCode, "download_failed", Res.string.download_failed)
    val indexUnavailable = appString(displayLanguage, osLocaleCode, "index_unavailable", Res.string.index_unavailable)
    val searchPlaceholder = appString(displayLanguage, osLocaleCode, "search_placeholder", Res.string.search_placeholder)
    val searchButton = appString(displayLanguage, osLocaleCode, "search_button", Res.string.search_button)
    val searchFailed = appString(displayLanguage, osLocaleCode, "search_failed", Res.string.search_failed)
    val noResults = appString(displayLanguage, osLocaleCode, "no_results", Res.string.no_results)
    val detailUnavailable = appString(displayLanguage, osLocaleCode, "church_detail_unavailable", Res.string.church_detail_unavailable)
    val backToSearch = appString(displayLanguage, osLocaleCode, "back_to_search", Res.string.back_to_search)
    val denominationLabel = appString(displayLanguage, osLocaleCode, "church_denomination", Res.string.church_denomination)
    val websiteLabel = appString(displayLanguage, osLocaleCode, "church_website", Res.string.church_website)
    val distancePattern = appString(displayLanguage, osLocaleCode, "distance_km", Res.string.distance_km)

    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(siteName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(searchHeading, style = MaterialTheme.typography.headlineLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(Language.entries) { language ->
                    TextButton(onClick = {
                        displayLanguage = language
                        languagePreferences.writeLanguageCode(language.code)
                        engine = manager?.activeEngine(language.code)
                    }) {
                        Text(if (language == displayLanguage) "[${language.displayName}]" else language.displayName)
                    }
                }
            }
            if (engine == null) {
                Text(downloadPrompt)
                Button(
                    enabled = manager != null && state !is AppState.Loading,
                    onClick = {
                        scope.launch {
                            state = AppState.Loading
                            state = runCatching {
                                manager!!.update()
                                engine = manager.activeEngine(displayLanguage.code) ?: error(indexUnavailable)
                                AppState.Ready
                            }.getOrElse { AppState.Error(downloadFailed) }
                        }
                    },
                ) { Text(downloadButton) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(searchPlaceholder) },
                    )
                    Button(
                        enabled = query.isNotBlank() && state !is AppState.Loading,
                        onClick = {
                            scope.launch {
                                state = AppState.Loading
                                state = runCatching {
                                    val queryLanguage = QueryLanguageDetector.detect(query, displayLanguage.code)
                                    val queryEngine = manager?.activeEngine(queryLanguage) ?: engine
                                        ?: error(indexUnavailable)
                                    var response = withContext(Dispatchers.Default) {
                                        queryEngine.search(ChurchSearchRequest(query))
                                    }
                                    if (response.resolvedLocations.isEmpty()) {
                                        locationProvider()?.let { location ->
                                            response = withContext(Dispatchers.Default) {
                                                queryEngine.search(ChurchSearchRequest(query, userLocation = location))
                                            }
                                        }
                                    }
                                    AppState.Results(response)
                                }.getOrElse { AppState.Error(searchFailed) }
                            }
                        },
                    ) { Text(searchButton) }
                }
            }
            when (val current = state) {
                AppState.Loading -> CircularProgressIndicator()
                is AppState.Error -> Text(current.message, color = MaterialTheme.colorScheme.error)
                is AppState.Results -> {
                    Text(appString(
                        displayLanguage,
                        osLocaleCode,
                        "search_results_count",
                        Res.string.search_results_count,
                        current.response.total.toString(),
                    ))
                    if (current.response.hits.isEmpty()) {
                        Text(noResults)
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(current.response.hits, key = { it.churchId }) { hit ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    state = engine?.church(hit.churchId)?.let { AppState.Detail(it) }
                                        ?: AppState.Error(detailUnavailable)
                                },
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        preferredChurchName(
                                            hit.localizedNames,
                                            displayLanguage.code,
                                            hit.name,
                                            hit.englishName,
                                        ),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    if (displayLanguage != Language.ENGLISH) {
                                        Text(hit.englishName, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Text(hit.address, style = MaterialTheme.typography.bodyMedium)
                                    hit.distanceKm?.let {
                                        Text(distancePattern.replace("%1\$s", ((it * 10).toInt() / 10.0).toString()))
                                    }
                                    hit.matchedPages.firstOrNull()?.snippet?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
                is AppState.Detail -> {
                    Button(onClick = { state = AppState.Ready }) { Text(backToSearch) }
                    Text(
                        preferredChurchName(
                            current.church.localizedNames,
                            displayLanguage.code,
                            current.church.name,
                            current.church.englishName,
                        ),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    if (displayLanguage != Language.ENGLISH) {
                        Text(current.church.englishName, style = MaterialTheme.typography.titleMedium)
                    }
                    preferredDenominationName(
                        current.church.localizedDenominationNames,
                        displayLanguage.code,
                        current.church.denominationId,
                    )?.let { Text("$denominationLabel: $it") }
                    Text(current.church.address)
                    if (current.church.websiteUrl.isNotBlank()) {
                        Button(onClick = { openUrl(current.church.websiteUrl) }) { Text(websiteLabel) }
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
