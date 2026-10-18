package com.example.text

/**
 * The writing system (script) of recognized text, named after its Unicode script in lowercase
 * ("latin", "hangul", "thai", ...). Chinese characters and Japanese kana count as one, "cjk".
 *
 * Used to join recognized pieces with or without a space, to keep pieces in different scripts
 * out of one sentence, and to check the language a speech model says it heard.
 */
object WritingSystem {
    const val CJK = "cjk"
    const val LATIN = "latin"

    fun ofChar(c: Char): String? = when (val script = Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA -> CJK
        Character.UnicodeScript.COMMON,
        Character.UnicodeScript.INHERITED,
        Character.UnicodeScript.UNKNOWN -> null
        else -> script.name.lowercase()
    }

    /** The script [code] (a translator language code) is written in. */
    fun ofLanguage(code: String): String = when (code) {
        "ja", "zh" -> CJK
        "ko" -> "hangul"
        "ru", "uk", "bg", "be", "mk" -> "cyrillic"
        "ar", "fa", "ur" -> "arabic"
        "hi", "mr", "ne" -> "devanagari"
        "bn" -> "bengali"
        "gu" -> "gujarati"
        "ta" -> "tamil"
        "te" -> "telugu"
        "kn" -> "kannada"
        "th" -> "thai"
        "el" -> "greek"
        "he" -> "hebrew"
        "ka" -> "georgian"
        else -> LATIN
    }

    /** The script most of the letters in [text] are in, or null without letters. */
    fun majorityOf(text: String): String? =
        text.filter { it.isLetter() }
            .groupingBy { ofChar(it) }
            .eachCount()
            .filterKeys { it != null }
            .maxByOrNull { it.value }
            ?.key

    /**
     * The script a piece of speech is written in: the most used one other than Latin when it
     * has at least two such letters (Korean or Japanese speech is often written with English
     * words in it, "iPhone です"), otherwise Latin. Null when [text] has no letters.
     */
    fun mainOf(text: String): String? {
        val counts = text.filter { it.isLetter() }
            .groupingBy { ofChar(it) }
            .eachCount()
            .filterKeys { it != null }
        val nonLatin = counts.filterKeys { it != LATIN }
        if (nonLatin.values.sum() >= 2) return nonLatin.maxByOrNull { it.value }?.key
        return if (counts.isEmpty()) null else counts.maxByOrNull { it.value }?.key
    }

    /** Number of letters in [text] (for weighing pieces against each other). */
    fun letterCount(text: String): Int = text.count { it.isLetter() }

    fun hasKana(text: String): Boolean = text.any {
        val script = Character.UnicodeScript.of(it.code)
        script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
    }

    /**
     * The language [text] must be in, judged from its letters alone: kana is Japanese, Hangul is
     * Korean, Thai script is Thai, and so on. Null when the script is shared by several
     * languages (Latin, Cyrillic, Arabic, Devanagari, or Chinese characters without kana).
     */
    fun languageOfScript(text: String): String? {
        if (hasKana(text)) return "ja"
        return when (mainOf(text)) {
            "hangul" -> "ko"
            "thai" -> "th"
            "tamil" -> "ta"
            "telugu" -> "te"
            "kannada" -> "kn"
            "gujarati" -> "gu"
            "bengali" -> "bn"
            "greek" -> "el"
            "hebrew" -> "he"
            "georgian" -> "ka"
            else -> null
        }
    }

    /**
     * The language to translate [text] from, given the language a speech model [heard]:
     * - the text's own language when only one language is written that way (on short, noisy
     *   pieces Whisper can label Korean speech "en" while still writing Hangul);
     * - [heard] when the text is in [heard]'s script (or has no letters);
     * - null when the text is in another, shared script (Latin text labelled Korean): only a
     *   text language detector can tell then.
     */
    fun checkHeardLanguage(heard: String, text: String): String? {
        languageOfScript(text)?.let { return it }
        val main = mainOf(text) ?: return heard
        return if (main == ofLanguage(heard)) heard else null
    }

    /**
     * True for characters written without spaces around them: Chinese characters, kana and
     * their marks (ー, 々), Bopomofo, and the full-width forms used in Chinese and Japanese text.
     * Korean writes spaces between words, and Thai between phrases, so they are not included.
     */
    fun isUnspaced(c: Char): Boolean {
        val code = c.code
        if (code in 0x3000..0x303F || code == 0x30FC || code in 0xFF00..0xFFEF) return true
        return when (Character.UnicodeScript.of(code)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.BOPOMOFO -> true
            else -> false
        }
    }
}
