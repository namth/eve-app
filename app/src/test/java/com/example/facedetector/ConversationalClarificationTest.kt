package com.example.facedetector

import com.example.facedetector.ai.LocalAiAgentService
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConversationalClarificationTest {

    @Before
    fun setUp() {
        LocalAiAgentService.clearSessionMemory()
    }

    @Test
    fun testRecordAssistantSpeechIntoContext() {
        // Ghi nhận câu kịch bản chào hỏi của EVE
        val scriptedGreeting = "Em chào anh/chị ạ! Cho em biết tên được không ạ?"
        LocalAiAgentService.recordAssistantSpeech(scriptedGreeting)

        // Kiểm tra qua getHistorySnapshot bằng reflection
        val getHistoryMethod = LocalAiAgentService::class.java.getDeclaredMethod("getHistorySnapshot")
        getHistoryMethod.isAccessible = true
        val history = getHistoryMethod.invoke(LocalAiAgentService) as JSONArray

        assertEquals(1, history.length())
        val firstMsg = history.getJSONObject(0)
        assertEquals("assistant", firstMsg.getString("role"))
        assertEquals(scriptedGreeting, firstMsg.getString("content"))

        // Kiểm tra chống trùng lặp liên tiếp
        LocalAiAgentService.recordAssistantSpeech(scriptedGreeting)
        val historyAfterDupe = getHistoryMethod.invoke(LocalAiAgentService) as JSONArray
        assertEquals(1, historyAfterDupe.length())

        // Thêm câu kịch bản tiếp theo
        val scriptedConfirm = "Em nhìn anh quen lắm, anh có phải là anh Nam không ạ?"
        LocalAiAgentService.recordAssistantSpeech(scriptedConfirm)
        val historyAfterSecond = getHistoryMethod.invoke(LocalAiAgentService) as JSONArray
        assertEquals(2, historyAfterSecond.length())
        assertEquals(scriptedConfirm, historyAfterSecond.getJSONObject(1).getString("content"))
    }

    @Test
    fun testClarificationEmotionResolution() {
        // Cảm xúc tò mò / bối rối khi gặp câu hỏi/thắc mắc
        assertEquals("curious", LocalAiAgentService.resolveEffectiveEmotion("curious", null, "huh cái gì cơ", "admin"))
        assertEquals("shrug", LocalAiAgentService.resolveEffectiveEmotion("shrug", null, "ơ...", "admin"))
        assertEquals("curious", LocalAiAgentService.resolveEffectiveEmotion("curious", null, "gì cơ", "friend"))
    }
}
