package jp.co.crossmap.crawl

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChineseDictionaryValidatorTest {
    @Test
    fun validatesPairedDictionariesAndReportsIdenticalTermsForReview() {
        val root = Files.createTempDirectory("crossmap-chinese-dictionaries")
        try {
            Files.createDirectories(root.resolve("dictionary"))
            Files.writeString(root.resolve("dictionary/ja-zh-Hans-concept-dictionary.csv"), "教会,教会\n恵み,恩典\n")
            Files.writeString(root.resolve("dictionary/ja-zh-Hant-concept-dictionary.csv"), "教会,教會\n恵み,恩典\n")

            val report = ChineseDictionaryValidator.validate(root)

            assertTrue(report.valid)
            assertEquals(4, report.entriesChecked)
            assertTrue(report.reviewSignals.any { "恩典" in it && "identical" in it })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsMalformedLocalesDuplicatesAndUnpairedSources() {
        val root = Files.createTempDirectory("crossmap-invalid-chinese-dictionaries")
        try {
            Files.createDirectories(root.resolve("dictionary"))
            Files.writeString(root.resolve("dictionary/ja-zh-CN-concept-dictionary.csv"), "教会,教会\n")
            Files.writeString(root.resolve("dictionary/ja-zh-Hans-concept-dictionary.csv"), "教会,教会\n教会,教堂\n")
            Files.writeString(root.resolve("dictionary/ja-zh-Hant-concept-dictionary.csv"), "礼拝,禮拜\n")

            val report = ChineseDictionaryValidator.validate(root)

            assertFalse(report.valid)
            assertTrue(report.errors.any { "Malformed" in it })
            assertTrue(report.errors.any { "duplicate source" in it })
            assertTrue(report.errors.any { "unpaired source" in it })
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
