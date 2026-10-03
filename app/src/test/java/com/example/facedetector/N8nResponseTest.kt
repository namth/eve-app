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

    @Test
    fun testShortDirectNameResponses() {
        // Trả lời ngắn gọn trực tiếp chỉ có tên
        val tuan = N8nService.extractNameFromMessage("Tuấn")
        assertNotNull(tuan)
        assertEquals("Tuấn", tuan!!.name)

        val nam = N8nService.extractNameFromMessage("Nam")
        assertNotNull(nam)
        assertEquals("Nam", nam!!.name)
        assertEquals("admin", nam.role)

        // Trả lời Đại từ + Tên
        val anhTuan = N8nService.extractNameFromMessage("Anh Tuấn")
        assertNotNull(anhTuan)
        assertEquals("Tuấn", anhTuan!!.name)
        assertEquals("Anh", anhTuan.preferredPronoun)
        assertEquals("male", anhTuan.gender)

        val chiMai = N8nService.extractNameFromMessage("Chị Mai")
        assertNotNull(chiMai)
        assertEquals("Mai", chiMai!!.name)
        assertEquals("Chị", chiMai.preferredPronoun)
        assertEquals("female", chiMai.gender)

        val chuBa = N8nService.extractNameFromMessage("Chú Ba")
        assertNotNull(chuBa)
        assertEquals("Ba", chuBa!!.name)
        assertEquals("Chú", chuBa.preferredPronoun)

        val bacHung = N8nService.extractNameFromMessage("Bác Hùng nhé")
        assertNotNull(bacHung)
        assertEquals("Hùng", bacHung!!.name)
        assertEquals("Bác", bacHung.preferredPronoun)

        // Các dạng câu "Anh tên Nam", "Anh tên là Nam", "Tên anh là Nam"
        val anhTenNam = N8nService.extractNameFromMessage("Anh tên Nam")
        assertNotNull(anhTenNam)
        assertEquals("Nam", anhTenNam!!.name)
        assertEquals("Anh", anhTenNam.preferredPronoun)

        val anhTenLaHung = N8nService.extractNameFromMessage("anh tên là Hùng")
        assertNotNull(anhTenLaHung)
        assertEquals("Hùng", anhTenLaHung!!.name)
        assertEquals("Anh", anhTenLaHung.preferredPronoun)

        val tenLaTuan = N8nService.extractNameFromMessage("Tên là Tuấn")
        assertNotNull(tenLaTuan)
        assertEquals("Tuấn", tenLaTuan!!.name)

        val laNam = N8nService.extractNameFromMessage("Là Nam đây")
        assertNotNull(laNam)
        assertEquals("Nam", laNam!!.name)

        // Câu không chứa tên
        val nonName = N8nService.extractNameFromMessage("không có gì đâu")
        org.junit.Assert.assertNull(nonName)

        val weather = N8nService.extractNameFromMessage("thời tiết hôm nay thế nào")
        org.junit.Assert.assertNull(weather)
    }
}
