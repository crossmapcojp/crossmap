package jp.co.crossmap.crawl

import kotlin.test.Test
import kotlin.test.assertEquals

class ChurchTitleLanguageDetectorTest {
    @Test
    fun detectsLanguagesPresentInRealGooglePlaceTitlesWithoutTreatingBrandAcronymsAsEnglish() {
        assertEquals(listOf("ja"), ChurchTitleLanguageDetector.detect("福岡大濠公園教会"))
        assertEquals(listOf("en", "ja"), ChurchTitleLanguageDetector.detect("Tokyo Baptist Church 東京バプテスト教会"))
        assertEquals(listOf("ja", "ko"), ChurchTitleLanguageDetector.detect("ヨハン東京キリスト教会 요한동경교회"))
        assertEquals(listOf("ko"), ChurchTitleLanguageDetector.detect("예수사랑교회"))
        assertEquals(listOf("ja"), ChurchTitleLanguageDetector.detect("HCCライブチャーチ津山"))
        assertEquals(listOf("id"), ChurchTitleLanguageDetector.detect("Gereja Interdenominasi Injili Indonesia"))
        assertEquals(listOf("pt"), ChurchTitleLanguageDetector.detect("Igreja Evangélica das Nações"))
        assertEquals(listOf("pt"), ChurchTitleLanguageDetector.detect("Missão Apoio Toyohashi"))
        assertEquals(listOf("pt"), ChurchTitleLanguageDetector.detect("Assembléia de Deus Belém Japão Iwakura"))
        assertEquals(listOf("pt"), ChurchTitleLanguageDetector.detect("JMEAD HIROSHIMA"))
        assertEquals(listOf("pt"), ChurchTitleLanguageDetector.detect("MEB TOYOHASHI"))
        assertEquals(listOf("en"), ChurchTitleLanguageDetector.detect("JCCM NAGOYA"))
        assertEquals(listOf("en"), ChurchTitleLanguageDetector.detect("LIGHT HOUSE BETHANY"))
        assertEquals(listOf("en", "ja"), ChurchTitleLanguageDetector.detect("FirstVineyardChurch可児福音教会"))
        assertEquals(listOf("es"), ChurchTitleLanguageDetector.detect("Movimiento Misionero Mundial"))
        assertEquals(listOf("de"), ChurchTitleLanguageDetector.detect("Kreuzkirche Tokyo"))
        assertEquals(listOf("en", "pt"), ChurchTitleLanguageDetector.detect("Bola de Neve Church, Hamamatsu-Shi"))
        assertEquals(listOf("en", "es"), ChurchTitleLanguageDetector.detect("Iglesia Ni Cristo (Church of Christ)"))
    }
}
