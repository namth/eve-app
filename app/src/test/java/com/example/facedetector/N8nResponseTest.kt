package com.example.facedetector

import com.example.facedetector.network.N8nService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class N8nResponseTest {

    @Test
    fun testParseWebhookJsonArrayPayload() {
        val jsonPayload = """
            [
              {
                "output": {
                  "status": "success",
                  "person": {
                    "name": "Nam",
                    "age": null,
                    "gender": "male",
                    "preferred_pronoun": "Anh"
                  },
                  "reply_text": "Chào anh Nam! Anh có điều gì đặc biệt muốn nói với em không, hay chỉ muốn gây sự chú ý thôi ạ?",
                  "emotion": "happy"
                }
              }
            ]
        """.trimIndent()

        val response = N8nService.parseN8nJson(jsonPayload, "Chào em anh là Nam", null)

        assertEquals("Chào anh Nam! Anh có điều gì đặc biệt muốn nói với em không, hay chỉ muốn gây sự chú ý thôi ạ?", response.replyText)
        assertEquals("happy", response.emotion)

        assertNotNull("Person must be parsed", response.detectedPerson)
        val person = response.detectedPerson!!
        assertEquals("Nam", person.name)
        assertEquals("Anh", person.preferredPronoun)
        assertEquals("male", person.gender)
        assertEquals(null, person.age)
        assertEquals("admin", person.role)
    }

    @Test
    fun testChuTieuNullNameDoesNotExtractDummyName() {
        // Đúng theo trường hợp lỗi của user: n8n trả về name: null và preferred_pronoun: "Chú"
        val jsonPayload = """
            [
              {
                "output": {
                  "status": "success",
                  "person": {
                    "name": null,
                    "age": null,
                    "gender": "male",
                    "preferred_pronoun": "Chú"
                  },
                  "reply_text": "Dạ, chú tiểu ơi! Chú có điều gì muốn chia sẻ không ạ?",
                  "emotion": "happy"
                }
              }
            ]
        """.trimIndent()

        val response = N8nService.parseN8nJson(jsonPayload, "mình là chú tiểu đây", null)

        assertNotNull("Person must be parsed", response.detectedPerson)
        val person = response.detectedPerson!!
        // Tên TUYỆT ĐỐI KHÔNG ĐƯỢC LÀ "Ti" hay "Chú"!
        assertEquals("", person.name)
        assertEquals("Chú", person.preferredPronoun)
        assertEquals("male", person.gender)
    }

    @Test
    fun testChuTieuLocalRegexFallback() {
        // Fallback khi n8n không có trường person: "mình là chú tiểu đây"
        val extracted = N8nService.extractNameFromMessage("mình là chú tiểu đây")
        assertNotNull("Extracted person must not be null", extracted)
        assertEquals("", extracted!!.name) // Không được coi là tên riêng "Ti"
        assertEquals("Chú", extracted.preferredPronoun)
        assertEquals("male", extracted.gender)
    }

    @Test
    fun testVietnameseNamesWithToneMarks() {
        // Kiểm tra chữ "tiểu" không bị cắt cụt thành "Ti"
        val hoangAnh = N8nService.extractNameFromMessage("mình là Hoàng Anh đây nhé")
        assertNotNull(hoangAnh)
        assertEquals("Hoàng Anh", hoangAnh!!.name)

        val hoaiAn = N8nService.extractNameFromMessage("tôi là Hoài An nè")
        assertNotNull(hoaiAn)
        assertEquals("Hoài An", hoaiAn!!.name)

        val nguyenNam = N8nService.extractNameFromMessage("anh là Nguyễn Hoàng Nam ạ")
        assertNotNull(nguyenNam)
        assertEquals("Nguyễn Hoàng Nam", nguyenNam!!.name)
        assertEquals("Anh", nguyenNam.preferredPronoun)

        val tuanKiet = N8nService.extractNameFromMessage("cứ gọi tôi là Đỗ Tuấn Kiệt nha")
        assertNotNull(tuanKiet)
        assertEquals("Đỗ Tuấn Kiệt", tuanKiet!!.name)
    }
}
