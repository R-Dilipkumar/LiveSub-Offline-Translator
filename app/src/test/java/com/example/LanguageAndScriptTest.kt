package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.asr.SentenceAssembler
import com.example.asr.SpeechRecognizerEngine
import com.example.text.WritingSystem
import com.example.translate.LocalTranslatorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Joining pieces, keeping a sentence in one language, and checking the language a speech model
 * heard against the text. From the phone log of 2026-10-18 (Korean stream, Whisper on Auto).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LanguageAndScriptTest {

    private fun join(a: String, b: String) = SpeechRecognizerEngine.joinFragments(a, b)

    // ---- Joining pieces ----

    @Test
    fun koreanPiecesKeepTheSpaceBetweenWords() {
        // Was "구독자지금": Korean writes spaces between words
        assertEquals("구독자 지금", join("구독자", "지금"))
        assertEquals("조심하게 Thank you", join("조심하게", "Thank you"))
    }

    @Test
    fun thaiPiecesKeepTheSpaceBetweenPhrases() {
        // Pieces end at pauses, and Thai writes a space between phrases
        assertEquals("สวัสดีครับ วันนี้", join("สวัสดีครับ", "วันนี้"))
    }

    @Test
    fun chineseAndJapaneseStillJoinWithoutSpace() {
        assertEquals("駅はどこですか", join("駅", "はどこですか"))
        assertEquals("コーヒーを", join("コーヒー", "を"))
        assertEquals("ｉＰｈｏｎｅです", join("ｉＰｈｏｎｅ", "です"))
        assertEquals("好Hello", join("好", "Hello"))
    }

    // ---- One sentence, one language ----

    @Test
    fun aShortMisheardLastPieceDoesNotChangeTheSentenceLanguage() {
        val assembler = SentenceAssembler()
        assertFalse(assembler.onPiece("우리 시청자 여러분 오늘도", 1, 0, language = "ko").isFinal)
        // Whisper labelled a short noisy tail "en" (but still wrote Hangul)
        val sentence = assembler.onPiece("감사합니다.", 2, 100, language = "en")
        assertTrue(sentence.isFinal)
        assertEquals("ko", sentence.language)
    }

    @Test
    fun aPieceInAnotherWritingSystemStartsANewSentence() {
        val assembler = SentenceAssembler()
        assertFalse(assembler.onPiece("조심하게", 1, 0, language = "ko").isFinal)
        // English after Korean: the Korean sentence ends first, on its own
        val waiting = assembler.breakBefore("Thank you")
        assertEquals("조심하게", waiting?.text)
        assertEquals("ko", waiting?.language)
        assertEquals("Thank you", assembler.onPiece("Thank you", 2, 100, language = "en").text)
    }

    @Test
    fun englishWordsInsideJapaneseDoNotSplitTheSentence() {
        val assembler = SentenceAssembler()
        assertFalse(assembler.onPiece("新しい", 1, 0, language = "ja").isFinal)
        assertNull(assembler.breakBefore("iPhone を買った"))
        // One letter is too little to judge the writing system by
        assertNull(assembler.breakBefore("A"))
    }

    // ---- Japanese sentence endings ----

    @Test
    fun midSentenceWordsEndingInAFinalKanaWaitForTheRest() {
        for (piece in listOf("まだ", "ただ", "誰か", "なんか", "静か")) {
            assertFalse(piece, SentenceAssembler().onPiece(piece, 1, 0).isFinal)
        }
        // Real endings still finish at once
        for (piece in listOf("映画を見ました", "そうですね", "行くんですか", "本当だよ", "いいよね")) {
            assertTrue(piece, SentenceAssembler().onPiece(piece, 1, 0).isFinal)
        }
        // A sentence ending in a lone final kana ends after the pause instead
        val assembler = SentenceAssembler(pauseMs = 400L)
        assertFalse(assembler.onPiece("そうだ", 1, 0).isFinal)
        assertEquals("そうだ", assembler.onTick(500, isSpeaking = false)?.text)
    }

    // ---- The heard language against the text ----

    @Test
    fun hangulTextLabelledEnglishIsTranslatedAsKorean() {
        assertEquals("ko", WritingSystem.checkHeardLanguage("en", "조심하게"))
        assertEquals("ja", WritingSystem.checkHeardLanguage("zh", "もっとそっちに"))
        assertEquals("ta", WritingSystem.checkHeardLanguage("hi", "வணக்கம்"))
    }

    @Test
    fun textInTheHeardLanguagesScriptKeepsTheHeardLanguage() {
        assertEquals("ko", WritingSystem.checkHeardLanguage("ko", "유튜브 YouTube 채널"))
        // Kanji only: Chinese or Japanese, so the model's ear decides
        assertEquals("ja", WritingSystem.checkHeardLanguage("ja", "電話"))
        assertEquals("ru", WritingSystem.checkHeardLanguage("ru", "Привет"))
        assertEquals("en", WritingSystem.checkHeardLanguage("en", "123"))
    }

    @Test
    fun latinTextLabelledKoreanIsLeftToTheTextDetector() {
        // Latin letters are shared by many languages: only a text detector can tell
        assertNull(WritingSystem.checkHeardLanguage("ko", "Thank you"))
        assertNull(WritingSystem.checkHeardLanguage("ja", "Hello there"))
    }

    // ---- Language packs ----

    @Test
    fun sameSpokenAndSubtitleLanguageNeedsNoPack() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val engine = LocalTranslatorEngine(context)
        try {
            assertTrue(engine.getRequiredLanguageCodes("ja", "ja").isEmpty())
            assertEquals(setOf("ja", "ko", "en"), engine.getRequiredLanguageCodes("ja", "ko"))
            assertEquals(setOf("ko", "en"), engine.getRequiredLanguageCodes("ko", "en"))
        } finally {
            engine.release()
        }
    }
}
