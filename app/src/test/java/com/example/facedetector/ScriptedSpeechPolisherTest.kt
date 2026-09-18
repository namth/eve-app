package com.example.facedetector

import com.example.facedetector.ai.LocalAiAgentService
import com.example.facedetector.ai.ScriptedSpeechType
import com.example.facedetector.data.PersonProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptedSpeechPolisherTest {

    private val validEmotions = setOf(
        "speaking", "happy", "curious", "shy", "wave-right", "wave-left",
        "thinking", "love", "clap", "shrug"
    )

    private val samplePerson = PersonProfile(
        id = "test_user_01",
        name = "Thành Nam",
        age = 28,
        gender = "male",
        preferredPronoun = "Anh",
        role = "admin",
        avatarBase64 = null,
        faceEmbeddings = emptyList(),
        createdAt = System.currentTimeMillis(),
        lastSeenAt = System.currentTimeMillis()
    )

    @Test
    fun testAllScriptedSpeechTypesHaveValidFallbacks() {
        val params = mapOf(
            "name" to "Thành Nam",
            "pronoun" to "Anh",
            "gender" to "male",
            "timeStr" to "hôm qua",
            "oldPronoun" to "Anh",
            "oldName" to "Nam",
            "newName" to "Hoàng",
            "newPronoun" to "Anh",
            "content" to "Có 1 email công việc mới",
            "similarityPercent" to "75"
        )

        for (type in ScriptedSpeechType.values()) {
            val result = LocalAiAgentService.getMultiVariantFallback(type, samplePerson, params)
            assertFalse("Speech text for $type should not be blank", result.text.isBlank())
            assertTrue("Emotion '${result.emotion}' for $type must be in valid emotions set", validEmotions.contains(result.emotion))
        }
    }

    @Test
    fun testMultiVariantFallbackRandomness_ProducesMultipleDistinctPhrases() {
        val typesToTest = listOf(
            ScriptedSpeechType.GREETING_KNOWN,
            ScriptedSpeechType.GREETING_STRANGER,
            ScriptedSpeechType.AMBIGUOUS_QUESTION,
            ScriptedSpeechType.AMBIGUOUS_CONFIRMED,
            ScriptedSpeechType.AMBIGUOUS_DENIED,
            ScriptedSpeechType.FAREWELL,
            ScriptedSpeechType.SILENCE_REMINDER
        )

        val params = mapOf("gender" to "male", "similarityPercent" to "72")

        for (type in typesToTest) {
            val generatedTexts = mutableSetOf<String>()
            for (i in 0 until 30) {
                val result = LocalAiAgentService.getMultiVariantFallback(type, samplePerson, params)
                generatedTexts.add(result.text)
            }
            assertTrue(
                "Expected multiple distinct variants for $type over 30 runs, but got only ${generatedTexts.size}: $generatedTexts",
                generatedTexts.size >= 2
            )
        }
    }

    @Test
    fun testPreservationOfPersonDetails() {
        val friendPerson = PersonProfile(
            id = "friend_01",
            name = "Thảo Trang",
            age = 25,
            gender = "female",
            preferredPronoun = "Chị",
            role = "friend",
            avatarBase64 = null,
            faceEmbeddings = emptyList(),
            createdAt = System.currentTimeMillis(),
            lastSeenAt = System.currentTimeMillis()
        )

        val result = LocalAiAgentService.getMultiVariantFallback(
            ScriptedSpeechType.GREETING_KNOWN,
            friendPerson,
            emptyMap()
        )

        assertTrue(
            "Greeting should contain either name or pronoun of friend: ${result.text}",
            result.text.contains("Thảo Trang") || result.text.contains("Chị")
        )
    }

    @Test
    fun testAdminBriefingContainsContent() {
        val notifContent = "Máy chủ sao lưu hoàn tất lúc 23h"
        val result = LocalAiAgentService.getMultiVariantFallback(
            ScriptedSpeechType.ADMIN_BRIEFING_SINGLE,
            samplePerson,
            mapOf("content" to notifContent)
        )

        assertTrue(
            "Admin single briefing must contain the notification content",
            result.text.contains(notifContent)
        )
    }

    @Test
    fun testDisambiguationContainsTimeStr() {
        val timeStr = "3 ngày trước"
        val result = LocalAiAgentService.getMultiVariantFallback(
            ScriptedSpeechType.DISAMBIGUATION_QUESTION,
            samplePerson,
            mapOf("timeStr" to timeStr)
        )

        assertTrue(
            "Disambiguation question must mention the relative time string",
            result.text.contains(timeStr)
        )
    }
}
