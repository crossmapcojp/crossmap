package jp.co.crossmap.crawl.denomination

import java.nio.file.Path
import jp.co.crossmap.ChurchMinister
import kotlin.test.Test
import kotlin.test.assertEquals

class PersonalNameTransliteratorTest {
    private val transliterator = PersonalNameTransliterator.load(Path.of("../resources/personalnames"))

    @Test
    fun transliteratesSpacedJapaneseNameIntoEverySupportedLanguage() {
        val localized = transliterator.localize(minister("佐藤 太郎"))

        assertEquals(listOf("佐藤 太郎", "さとう たろう"), localized.localizedNames.filter { it.languageCode == "ja" }.map { it.name })
        assertEquals("Tarou Satou", localized.localizedNames.single { it.languageCode == "en" }.name)
        assertEquals("사토 다로", localized.localizedNames.single { it.languageCode == "ko" }.name)
        assertEquals("Tarou Satou", localized.localizedNames.single { it.languageCode == "pt" }.name)
        assertEquals("Tarou Satou", localized.localizedNames.single { it.languageCode == "id" }.name)
    }

    @Test
    fun segmentsUnspacedNameUsingSurnameAndGivenNameDictionaries() {
        val localized = transliterator.localize(minister("山田花子"))

        assertEquals("Hanako Yamada", localized.localizedNames.single { it.languageCode == "en" }.name)
        assertEquals("야마다 하나코", localized.localizedNames.single { it.languageCode == "ko" }.name)
    }

    @Test
    fun unknownNameIsRetainedWithoutInventingAReading() {
        val localized = transliterator.localize(minister("未知名"))

        assertEquals(setOf("ja", "en", "ko", "pt", "id"), localized.localizedNames.map { it.languageCode }.toSet())
        assertEquals("未知名", localized.localizedNames.single { it.languageCode == "en" }.name)
    }

    private fun minister(name: String) = ChurchMinister(
        name = name,
        roleId = "pastor",
        roleName = "牧師",
        localizedRoleNames = emptyList(),
    )
}
