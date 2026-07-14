package jp.co.crossmap

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path
import org.gnit.lucenekmp.analysis.ja.JapaneseAnalyzer
import org.gnit.lucenekmp.document.Document
import org.gnit.lucenekmp.document.Field
import org.gnit.lucenekmp.document.LatLonDocValuesField
import org.gnit.lucenekmp.document.LatLonPoint
import org.gnit.lucenekmp.document.StringField
import org.gnit.lucenekmp.document.StoredField
import org.gnit.lucenekmp.document.TextField
import org.gnit.lucenekmp.index.IndexWriter
import org.gnit.lucenekmp.index.IndexWriterConfig
import org.gnit.lucenekmp.store.FSDirectory

object ChurchIndex {
    const val FIELD_ID = "id"
    const val FIELD_NAME = "name"
    const val FIELD_CATEGORY = "category"
    const val FIELD_DENOMINATION = "denomination"
    const val FIELD_ADDRESS = "address"
    const val FIELD_CONTENT = "content"
    const val FIELD_SOCIAL = "social"
    const val FIELD_CONTENT_TYPE = "content_type"
    const val FIELD_LOCATION = "location"
    const val FIELD_RECORD = "record"

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun build(indexPath: Path, churches: List<ChurchRecord>) {
        val directory = FSDirectory.open(indexPath)
        val config = IndexWriterConfig(JapaneseAnalyzer()).apply {
            openMode = IndexWriterConfig.OpenMode.CREATE
        }
        IndexWriter(directory, config).use { writer ->
            churches.sortedBy { it.id }.forEach { church -> writer.addDocument(church.toDocument()) }
        }
        directory.close()
    }

    private fun ChurchRecord.toDocument(): Document = Document().apply {
        add(StringField(FIELD_ID, id, Field.Store.YES))
        add(TextField(FIELD_NAME, name, Field.Store.YES))
        denominationId?.takeIf { it.isNotBlank() }?.let { add(TextField(FIELD_DENOMINATION, it, Field.Store.YES)) }
        category?.takeIf { it.isNotBlank() }?.let { add(TextField(FIELD_CATEGORY, it, Field.Store.YES)) }
        add(TextField(FIELD_ADDRESS, address, Field.Store.YES))
        val searchableContent = pages.joinToString("\n") { "${it.title}\n${it.text}" }
        if (searchableContent.isNotBlank()) add(TextField(FIELD_CONTENT, searchableContent, Field.Store.NO))
        pages.map { it.contentType.name }.distinct().forEach {
            add(StringField(FIELD_CONTENT_TYPE, it, Field.Store.NO))
        }
        val socialContent = socialProfiles.joinToString("\n") {
            listOfNotNull(it.handle, it.displayName, it.description).joinToString(" ")
        }
        if (socialContent.isNotBlank()) add(TextField(FIELD_SOCIAL, socialContent, Field.Store.NO))
        add(LatLonPoint(FIELD_LOCATION, location.latitude, location.longitude))
        add(LatLonDocValuesField(FIELD_LOCATION, location.latitude, location.longitude))
        add(StoredField(FIELD_RECORD, json.encodeToString(this@toDocument)))
    }
}
