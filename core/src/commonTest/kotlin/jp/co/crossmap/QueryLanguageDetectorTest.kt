package jp.co.crossmap

import kotlin.test.Test
import kotlin.test.assertEquals

class QueryLanguageDetectorTest {
    @Test
    fun detectsTranslatedAddressGeonamesWithoutUiLanguageHint() {
        assertEquals("pt", QueryLanguageDetector.detect("Distrito de Minato"))
        assertEquals("id", QueryLanguageDetector.detect("Distrik Minato"))
        assertEquals("en", QueryLanguageDetector.detect("Minato City"))
        assertEquals("ko", QueryLanguageDetector.detect("미나토구"))
        assertEquals("ja", QueryLanguageDetector.detect("港区"))
    }
}
