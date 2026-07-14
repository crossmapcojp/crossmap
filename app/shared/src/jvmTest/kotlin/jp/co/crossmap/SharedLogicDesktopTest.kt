package jp.co.crossmap

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedLogicDesktopTest {

    @Test
    fun realChurchRequestKeepsJapaneseQuery() {
        val request = ChurchSearchRequest("岡山バプテスト教会")
        assertEquals("岡山バプテスト教会", request.query)
    }
}
