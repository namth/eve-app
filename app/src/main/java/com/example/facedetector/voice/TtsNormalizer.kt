package com.example.facedetector.voice

import com.example.facedetector.data.EveDatabaseHelper

object TtsNormalizer {

    /**
     * Strips Markdown formatting (*, #, _, `, >, [, ], ~, |) so TTS does not read symbols.
     */
    fun stripMarkdown(text: String): String {
        return text
            .replace(Regex("""[*#_`>~|]"""), "")
            .replace(Regex("""\[(.*?)\]\(.*?\)"""), "$1") // Replace [text](url) with text
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * Normalizes text for Vietnamese TTS:
     * 1. Strips Markdown.
     * 2. Replaces learned pronunciations from SQLite database.
     * 3. Normalizes web domains (e.g. google.com -> gút gồ chấm com).
     */
    fun normalizeForTts(text: String, dbHelper: EveDatabaseHelper? = null): String {
        var cleanText = stripMarkdown(text)

        // 1. Apply learned pronunciation dictionary from SQLite
        if (dbHelper != null) {
            try {
                val pronunciations = dbHelper.getAllPronunciations()
                for ((word, speak) in pronunciations) {
                    if (word.isNotBlank() && speak.isNotBlank()) {
                        cleanText = cleanText.replace(Regex("""\b${Regex.escape(word)}\b""", RegexOption.IGNORE_CASE), speak)
                    }
                }
            } catch (e: Exception) {
                // Ignore db read error
            }
        }

        // 2. Normalize common domain names
        val domainMap = mapOf(
            "google.com" to "gút gồ chấm com",
            "facebook.com" to "phây súp chấm com",
            "youtube.com" to "iu tuýp chấm com",
            "zalo.me" to "za lô chấm mi",
            "tiktok.com" to "tích tắc chấm com",
            "vnexpress.net" to "vn express chấm nét"
        )
        for ((domain, phonetic) in domainMap) {
            cleanText = cleanText.replace(domain, phonetic, ignoreCase = true)
        }

        // 3. Normalize common TLD extensions
        cleanText = cleanText
            .replace(Regex("""\.com\.vn\b""", RegexOption.IGNORE_CASE), " chấm com chấm vi en ")
            .replace(Regex("""\.com\b""", RegexOption.IGNORE_CASE), " chấm com ")
            .replace(Regex("""\.vn\b""", RegexOption.IGNORE_CASE), " chấm vi en ")
            .replace(Regex("""\.net\b""", RegexOption.IGNORE_CASE), " chấm nét ")
            .replace(Regex("""\.org\b""", RegexOption.IGNORE_CASE), " chấm oóc ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        return cleanText
    }
}
