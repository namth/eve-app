package com.example.facedetector

import com.example.facedetector.ai.LocalAiAgentService
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAiAgentGestureTest {

    @Test
    fun testActionContainsRobotGestureHarmonized() {
        // When LLM puts gesture in action, it should resolve to that gesture
        val emotion1 = LocalAiAgentService.resolveEffectiveEmotion("speaking", "wave-right", "vẫy tay chào anh", "admin")
        assertEquals("wave-right", emotion1)

        val emotion2 = LocalAiAgentService.resolveEffectiveEmotion("idle", "spin-360", "xoay tròn", "admin")
        assertEquals("spin-360", emotion2)

        val emotion3 = LocalAiAgentService.resolveEffectiveEmotion("speaking", "blaster", "bắn súng", "admin")
        assertEquals("blaster", emotion3)

        val emotion4 = LocalAiAgentService.resolveEffectiveEmotion("speaking", "scan", "quét phòng", "admin")
        assertEquals("scan", emotion4)
    }

    @Test
    fun testAdminDeterministicGestureFallbacks() {
        // When LLM returned "speaking" or "idle", keywords in user message should resolve to respective gestures for admin
        assertEquals("wave-right", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "vẫy tay chào anh", "admin"))
        assertEquals("wave-left", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "vẫy tay trái chào em", "admin"))
        assertEquals("spin-360", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "xoay một vòng cho anh xem", "admin"))
        assertEquals("scan", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "quét xung quanh xem có ai không", "admin"))
        assertEquals("blaster", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "sẵn sàng chiến đấu tiêu diệt kẻ địch", "admin"))
        assertEquals("love", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "thả tim yêu em nhé", "admin"))
        assertEquals("clap", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "anh vừa ký hợp đồng lớn chúc mừng anh đi", "admin"))
        assertEquals("shrug", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "nhún vai bối rối xem", "admin"))
        assertEquals("curious", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "kể chuyện tò mò nghiêng đầu", "admin"))
        assertEquals("directive-plant", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "tìm mầm cây sự sống", "admin"))
        assertEquals("jet-boost", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "bay phản lực siêu thanh lên nào", "admin"))
    }

    @Test
    fun testFriendDefiantGesturesOnCommand() {
        // For Friend: commanding gestures should trigger defiant/contrary gestures
        assertEquals("spin-360", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "vẫy tay chào bạn xem", "friend"))
        assertEquals("wave-left", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "xoay một vòng cho chị xem", "friend"))
        assertEquals("blaster", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "thả tim cho anh xem nào", "friend"))
        assertEquals("curious", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "vỗ tay xem nào", "friend"))
        assertEquals("directive-plant", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "bắn súng pháo plasma xem", "friend"))
        assertEquals("spin-360", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "quét laser phòng này xem", "friend"))
        assertEquals("sleeping", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "bay phản lực lên đi", "friend"))
        assertEquals("jet-boost", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "đi ngủ đi bạn ơi", "friend"))
    }

    @Test
    fun testExplicitEmotionPreservedIfValid() {
        // If LLM already provided a valid non-speaking emotion, keep it
        assertEquals("shy", LocalAiAgentService.resolveEffectiveEmotion("shy", null, "hôm nay xinh thế", "admin"))
        assertEquals("happy", LocalAiAgentService.resolveEffectiveEmotion("happy", null, "chào em", "friend"))
    }

    @Test
    fun testScanAndDirectivePlantGestures() {
        // 1. Admin commands: "bay scan môi trường" and "bảo vệ mầm cây"
        assertEquals("scan", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "bay scan môi trường xem thế nào em", "admin"))
        assertEquals("scan", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "scan môi trường xung quanh đi", "admin"))
        assertEquals("scan", LocalAiAgentService.resolveEffectiveEmotion("smile", null, "bay scan môi trường", "admin"))
        assertEquals("directive-plant", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "bảo vệ mầm cây sự sống đi em", "admin"))
        assertEquals("directive-plant", LocalAiAgentService.resolveEffectiveEmotion("smile", null, "bảo vệ mầm cây", "admin"))
        assertEquals("directive-plant", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "gieo mầm cây xanh", "admin"))

        // 2. When LLM mistakenly returns gesture in action instead of emotion
        assertEquals("scan", LocalAiAgentService.resolveEffectiveEmotion("speaking", "bay scan môi trường", "bay scan môi trường", "admin"))
        assertEquals("scan", LocalAiAgentService.resolveEffectiveEmotion("speaking", "scan_environment", "quét môi trường", "admin"))
        assertEquals("directive-plant", LocalAiAgentService.resolveEffectiveEmotion("speaking", "bảo vệ mầm cây", "bảo vệ mầm cây", "admin"))
        assertEquals("directive-plant", LocalAiAgentService.resolveEffectiveEmotion("speaking", "directive_plant", "mầm cây sự sống", "admin"))

        // 3. Friend mode: "bay scan môi trường" -> spin-360 (defiant), "bảo vệ mầm cây" -> directive-plant (nature lover)
        assertEquals("spin-360", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "bay scan môi trường xem", "friend"))
        assertEquals("directive-plant", LocalAiAgentService.resolveEffectiveEmotion("speaking", null, "bảo vệ mầm cây đi", "friend"))
    }

    @Test
    fun testStrangerNewPersonNameExtractionFallback() {
        // Verify Vietnamese introduction phrases extract correct names and pronouns
        val p1 = com.example.facedetector.network.N8nService.extractNameFromMessage("Chào em, anh là Tuấn")
        org.junit.Assert.assertNotNull(p1)
        assertEquals("Tuấn", p1!!.name)
        assertEquals("Anh", p1.preferredPronoun)

        val p2 = com.example.facedetector.network.N8nService.extractNameFromMessage("Chị tên là Hoàng Mai ạ")
        org.junit.Assert.assertNotNull(p2)
        assertEquals("Hoàng Mai", p2!!.name)
        assertEquals("Chị", p2.preferredPronoun)

        val p3 = com.example.facedetector.network.N8nService.extractNameFromMessage("Cứ gọi tôi là chú Ba")
        org.junit.Assert.assertNotNull(p3)
        assertEquals("Ba", p3!!.name)
        assertEquals("Chú", p3.preferredPronoun)

        val p4 = com.example.facedetector.network.N8nService.extractNameFromMessage("Mình là Linh nhé")
        org.junit.Assert.assertNotNull(p4)
        assertEquals("Linh", p4!!.name)
    }
}
