package com.example.facedetector.network

import android.util.Log
import com.example.facedetector.data.PersonProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class N8nChatResponse(
    val replyText: String,
    val emotion: String = "speaking",
    val audioUrl: String? = null,
    val sessionId: String? = null,
    val detectedPerson: IncomingPerson? = null,
    val pronunciation: Pair<String, String>? = null,
    val action: String? = null
)

data class IncomingPerson(
    val name: String,
    val age: Int? = null,
    val gender: String = "unknown",
    val preferredPronoun: String = "Bạn",
    val role: String = "friend"
)

object N8nService {

    private const val TAG = "N8nService"
    private const val DEFAULT_WEBHOOK_URL = "https://ai.oa.io.vn/webhook/eve-chat"
    private const val DEFAULT_USER_ID = "user_default_01"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * Sends voice transcription to n8n AI webhook and returns structured response.
     */
    suspend fun sendMessage(
        message: String,
        currentPerson: PersonProfile?,
        sessionId: String? = null,
        webhookUrl: String = DEFAULT_WEBHOOK_URL
    ): N8nChatResponse = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("user_id", DEFAULT_USER_ID)
                put("message", message)
                put("context", "chat")
                put("timestamp", System.currentTimeMillis() / 1000)
                put("client_locale", "vi-VN")
                if (sessionId != null) put("session_id", sessionId)

                if (currentPerson != null) {
                    val personObj = JSONObject().apply {
                        put("id", currentPerson.id)
                        put("name", currentPerson.name)
                        put("preferred_pronoun", currentPerson.preferredPronoun)
                        put("role", currentPerson.role)
                    }
                    put("current_person", personObj)
                }
            }

            val request = Request.Builder()
                .url(webhookUrl)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful || responseBody.isBlank()) {
                Log.w(TAG, "n8n request failed (code: ${response.code}), falling back to local parser")
                return@withContext fallbackLocalParser(message, currentPerson)
            }

            parseN8nJson(responseBody, message, currentPerson)
        } catch (e: Exception) {
            Log.w(TAG, "Exception contacting n8n: ${e.message}, using local parser fallback")
            fallbackLocalParser(message, currentPerson)
        }
    }

    fun parseN8nJson(jsonStr: String, originalMessage: String, currentPerson: PersonProfile?): N8nChatResponse {
        return try {
            val trimmed = jsonStr.trim()
            val root = if (trimmed.startsWith("[")) {
                val array = org.json.JSONArray(trimmed)
                if (array.length() > 0) array.getJSONObject(0) else JSONObject()
            } else {
                JSONObject(trimmed)
            }

            val output = if (root.has("output") && !root.isNull("output") && root.get("output") is JSONObject) {
                root.getJSONObject("output")
            } else {
                root
            }

            val replyText = if (output.has("reply_text") && !output.isNull("reply_text")) {
                output.getString("reply_text")
            } else if (output.has("message") && !output.isNull("message")) {
                output.getString("message")
            } else {
                "Dạ em đã nghe rõ rồi ạ!"
            }

            val emotion = when {
                output.has("emotion") && !output.isNull("emotion") && output.getString("emotion").isNotBlank() -> output.getString("emotion").trim()
                output.has("expression") && !output.isNull("expression") && output.getString("expression").isNotBlank() -> output.getString("expression").trim()
                root.has("emotion") && !root.isNull("emotion") && root.getString("emotion").isNotBlank() -> root.getString("emotion").trim()
                root.has("expression") && !root.isNull("expression") && root.getString("expression").isNotBlank() -> root.getString("expression").trim()
                output.has("action") && !output.isNull("action") && output.getString("action").isNotBlank() && output.getString("action") != "logout" && output.getString("action") != "update-face-detect" -> output.getString("action").trim()
                else -> "speaking"
            }

            val audioUrl = if (output.has("audio_url") && !output.isNull("audio_url")) output.getString("audio_url") else null
            val sessionId = if (output.has("session_id") && !output.isNull("session_id")) output.getString("session_id") else null

            var incomingPerson: IncomingPerson? = null
            val personObj = when {
                output.has("person") && !output.isNull("person") && output.get("person") is JSONObject -> output.getJSONObject("person")
                output.has("update_person") && !output.isNull("update_person") && output.get("update_person") is JSONObject -> output.getJSONObject("update_person")
                else -> null
            }

            if (personObj != null) {
                var name = if (personObj.has("name") && !personObj.isNull("name")) {
                    personObj.getString("name").trim()
                } else ""

                val age = if (personObj.has("age") && !personObj.isNull("age")) personObj.getInt("age") else null
                var gender = if (personObj.has("gender") && !personObj.isNull("gender")) personObj.getString("gender") else "unknown"
                var pronoun = if (personObj.has("preferred_pronoun") && !personObj.isNull("preferred_pronoun")) {
                    personObj.getString("preferred_pronoun").trim()
                } else if (personObj.has("preferredPronoun") && !personObj.isNull("preferredPronoun")) {
                    personObj.getString("preferredPronoun").trim()
                } else {
                    ""
                }

                // Nếu name bị n8n gán nhầm thành đại từ xưng hô thuần túy (ví dụ: name = "Chú"):
                if (name.isNotBlank() && PRONOUN_BLACKLIST.contains(name.lowercase())) {
                    if (pronoun.isBlank()) pronoun = name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    name = ""
                }

                // Nếu name là hoặc chứa danh xưng "chú tiểu":
                if (name.lowercase().contains("chú tiểu")) {
                    if (pronoun.isBlank()) pronoun = "Chú"
                    name = ""
                }

                val role = if (personObj.has("role") && !personObj.isNull("role")) {
                    personObj.getString("role")
                } else {
                    if (name.equals("nam", ignoreCase = true)) "admin" else "friend"
                }

                if (name.isNotBlank() || pronoun.isNotBlank()) {
                    incomingPerson = IncomingPerson(
                        name = name,
                        age = age,
                        gender = gender,
                        preferredPronoun = if (pronoun.isNotBlank()) pronoun else "Bạn",
                        role = role
                    )
                }
            } else {
                // If n8n didn't return person object, also check message with local regex
                val localExtracted = extractNameFromMessage(originalMessage)
                if (localExtracted != null) {
                    incomingPerson = localExtracted
                }
            }

            var pronunciation: Pair<String, String>? = null
            if (output.has("pronunciation") && !output.isNull("pronunciation") && output.get("pronunciation") is JSONObject) {
                val pObj = output.getJSONObject("pronunciation")
                val word = pObj.optString("word")
                val speak = pObj.optString("speak")
                if (word.isNotBlank() && speak.isNotBlank()) {
                    pronunciation = Pair(word, speak)
                }
            }

            var action = if (output.has("action") && !output.isNull("action")) {
                val act = output.getString("action").trim()
                if (act.isNotBlank() && !act.equals("none", ignoreCase = true)) act else null
            } else null

            // Fallback nhận diện action từ câu nói người dùng nếu webhook n8n chưa trả về trường action
            if (action == null) {
                val lowerMsg = originalMessage.lowercase().trim()
                when {
                    lowerMsg.contains("nghỉ đi") || lowerMsg.contains("tự out") || lowerMsg.contains("tắt app") ||
                    lowerMsg.contains("thoát app") || lowerMsg.contains("tắt ứng dụng") || lowerMsg.contains("đi ngủ đi") ||
                    lowerMsg.contains("out đi") -> {
                        action = "logout"
                    }
                    lowerMsg.contains("cập nhật lại nhận diện") || lowerMsg.contains("cập nhật nhận diện") ||
                    lowerMsg.contains("cập nhật lại khuôn mặt") || lowerMsg.contains("cập nhật khuôn mặt") ||
                    lowerMsg.contains("quét lại mặt") || lowerMsg.contains("nhận diện lại mặt") ||
                    lowerMsg.contains("cập nhật lại mặt") -> {
                        action = "update-face-detect"
                    }
                }
            }

            N8nChatResponse(replyText, emotion, audioUrl, sessionId, incomingPerson, pronunciation, action)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing n8n response: ${e.message}", e)
            fallbackLocalParser(originalMessage, currentPerson)
        }
    }

    /**
     * Fallback parser if n8n webhook is offline: detects self-introduction phrases,
     * system actions (logout, update-face-detect), and physical gesture commands.
     */
    fun fallbackLocalParser(message: String, currentPerson: PersonProfile?): N8nChatResponse {
        val lower = message.lowercase().trim()
        val pronoun = currentPerson?.preferredPronoun ?: "bạn"

        // 1. Kiểm tra Lệnh Hệ Thống: Thoát ứng dụng (logout)
        val isLogout = lower.contains("nghỉ đi") || lower.contains("tự out") || lower.contains("tắt app") ||
                       lower.contains("thoát app") || lower.contains("tắt ứng dụng") || lower.contains("đi ngủ đi")
        if (isLogout) {
            val reply = "Dạ em đi nghỉ đây ạ, hẹn sớm gặp lại $pronoun nhé!"
            return N8nChatResponse(reply, "wave-right", null, null, null, null, "logout")
        }

        // 2. Kiểm tra Lệnh Hệ Thống: Cập nhật nhận diện khuôn mặt (update-face-detect)
        val isUpdateFace = lower.contains("cập nhật lại nhận diện") || lower.contains("cập nhật nhận diện") ||
                           lower.contains("cập nhật lại khuôn mặt") || lower.contains("cập nhật khuôn mặt") ||
                           lower.contains("quét lại mặt") || lower.contains("nhận diện lại mặt") ||
                           lower.contains("cập nhật lại mặt")
        if (isUpdateFace) {
            val reply = "Dạ em sẽ quét và cập nhật lại khuôn mặt cho $pronoun ngay đây ạ!"
            return N8nChatResponse(reply, "happy", null, null, null, null, "update-face-detect")
        }

        // 3. Kiểm tra Lệnh Cử chỉ / Emotion trực tiếp
        if (lower.contains("vẫy tay trái")) {
            return N8nChatResponse("Dạ em vẫy tay trái chào $pronoun nè!", "wave-left")
        }
        if (lower.contains("vẫy tay phải") || lower.contains("vẫy tay chào") || lower.contains("vẫy tay")) {
            return N8nChatResponse("Dạ em vẫy tay chào $pronoun đây ạ!", "wave-right")
        }
        if (lower.contains("xoay tròn") || lower.contains("xoay 360") || lower.contains("xoay một vòng") || lower.contains("quay tròn")) {
            return N8nChatResponse("Em xoay một vòng cho $pronoun xem nè!", "spin-360")
        }
        if (lower.contains("thả tim") || lower.contains("bắn tim") || lower.contains("yêu em") || lower.contains("yêu eve") || lower.contains("yêu bạn")) {
            return N8nChatResponse("EVE cũng yêu $pronoun nhiều lắm!", "love")
        }
        if (lower.contains("vỗ tay") || lower.contains("hoan hô") || lower.contains("tuyệt vời") || lower.contains("chúc mừng")) {
            return N8nChatResponse("Hoan hô $pronoun!", "clap")
        }
        if (lower.contains("nhún vai") || lower.contains("bối rối")) {
            return N8nChatResponse("Em cũng chưa rõ nữa nè $pronoun ơi...", "shrug")
        }
        if (lower.contains("tò mò") || lower.contains("nghiêng đầu")) {
            return N8nChatResponse("Ủa, có chuyện gì thú vị vậy $pronoun?", "curious")
        }
        if (lower.contains("bay scan") || lower.contains("scan môi trường") || lower.contains("quét môi trường") ||
            lower.contains("quét laser") || lower.contains("quét xung quanh") || lower.contains("quét phòng") ||
            lower.contains("scan") || lower.contains("quét") || (lower.contains("bay") && (lower.contains("quét") || lower.contains("môi trường")))) {
            return N8nChatResponse("Dạ em đang bay lên kích hoạt cảm biến quét kiểm tra môi trường đây ạ!", "scan")
        }
        if (lower.contains("mầm cây") || lower.contains("cây sự sống") || lower.contains("directive plant") ||
            lower.contains("bảo vệ mầm cây") || lower.contains("gieo mầm") || lower.contains("bảo vệ môi trường") || lower.contains("cây xanh")) {
            return N8nChatResponse("Dạ em đã định vị và kích hoạt chế độ bảo vệ mầm cây sự sống rồi ạ!", "directive-plant")
        }
        if (lower.contains("pháo plasma") || lower.contains("bắn pháo") || lower.contains("sẵn sàng chiến đấu") || lower.contains("tác chiến") || lower.contains("bắn súng")) {
            return N8nChatResponse("Pháo Plasma đã sẵn sàng tác chiến!", "blaster")
        }
        if (lower.contains("bay lượn") || lower.contains("bay phản lực") || lower.contains("siêu thanh") || lower.contains("jet boost") || lower.contains("bay lên")) {
            return N8nChatResponse("Kích hoạt chế độ bay phản lực siêu thanh!", "jet-boost")
        }
        if (lower.contains("ngại ngùng") || lower.contains("xấu hổ") || lower.contains("đỏ mặt")) {
            return N8nChatResponse("Dạ em đang ngại lắm nè...", "shy")
        }
        if (lower.contains("tức giận") || lower.contains("giận dữ") || lower.contains("nổi giận") || lower.contains("hờn dỗi")) {
            return N8nChatResponse("Em đang giận dỗi rồi nha!", "angry")
        }
        if (lower.contains("cười mỉm") || lower.contains("cười nhẹ") || lower.contains("mỉm cười")) {
            return N8nChatResponse("Em mỉm cười với $pronoun nè!", "smile")
        }
        if (lower.contains("buồn") || lower.contains("khóc")) {
            return N8nChatResponse("Huhu em đang buồn lắm...", "sad")
        }

        // 4. Kiểm tra giới thiệu tên
        val extractedPerson = extractNameFromMessage(message)
        return if (extractedPerson != null) {
            val p = extractedPerson.preferredPronoun
            val reply = "Dạ em chào $p ${extractedPerson.name}! Em rất vui được gặp $p ạ."
            N8nChatResponse(reply, "speaking", null, null, extractedPerson)
        } else {
            val reply = if (currentPerson != null) {
                "Dạ em đang lắng nghe ${currentPerson.preferredPronoun.lowercase()} ${currentPerson.name} đây ạ!"
            } else {
                "Dạ em đang lắng nghe bạn đây ạ! Bạn có thể giới thiệu tên để em ghi nhớ nhé."
            }
            N8nChatResponse(reply, "speaking", null, null, null)
        }
    }

    val PRONOUN_BLACKLIST = setOf(
        "anh", "chị", "em", "chú", "bác", "cô", "dì", "cậu", "mợ", "cháu",
        "tôi", "mình", "tao", "mày", "bạn", "ông", "bà", "nó", "họ", "người"
    )

    val STOP_WORDS = setOf(
        "đây", "đó", "nè", "này", "nhé", "nha", "ạ", "ơi", "nhe", "thôi", "mà",
        "nhá", "nhen", "nhỉ", "nhở", "với", "cho", "luôn", "nào", "gì", "ai", "không", "chưa"
    )

    /**
     * Extracts name and pronoun from Vietnamese introduction sentences.
     * Supports compound names ("Hoàng Anh", "Hoài An"), full names ("Nguyễn Hoàng Nam"),
     * diverse pronouns ("chú", "bác", "cô", "dì", "cậu", "mợ", "anh", "chị", "cháu", "mày/tao"),
     * and special monikers like "chú tiểu".
     */
    fun extractNameFromMessage(message: String): IncomingPerson? {
        val lower = message.lowercase().trim()

        // 1. Xác định đại từ xưng hô và giới tính tiếng Việt phong phú
        var pronoun = "Bạn"
        var gender = "unknown"

        when {
            lower.contains("bác") -> { pronoun = "Bác"; gender = "unknown" }
            lower.contains("chú") -> { pronoun = "Chú"; gender = "male" }
            lower.contains("cô") -> { pronoun = "Cô"; gender = "female" }
            lower.contains("dì") -> { pronoun = "Dì"; gender = "female" }
            lower.contains("cậu") -> { pronoun = "Cậu"; gender = "male" }
            lower.contains("mợ") -> { pronoun = "Mợ"; gender = "female" }
            lower.contains("chị") -> { pronoun = "Chị"; gender = "female" }
            lower.contains("anh") -> { pronoun = "Anh"; gender = "male" }
            lower.contains("cháu") -> { pronoun = "Cháu"; gender = "unknown" }
            lower.contains("em") -> { pronoun = "Em"; gender = "unknown" }
            lower.contains("tao") || lower.contains("mày") -> {
                // Người dùng xưng mày/tao -> EVE luôn lịch thiệp gọi là Anh/Bạn, tuyệt đối không xưng tao/mày
                pronoun = "Anh"
                gender = "male"
            }
        }

        // 2. Mẫu câu xưng tên đa dạng, hỗ trợ tên kép (Hoàng Anh, Hoài An) & họ tên đầy đủ với mọi dấu tiếng Việt
        val introRegex = Regex(
            """(?:tôi là|mình là|anh là|chị là|em là|chú là|bác là|cô là|dì là|cậu là|mợ là|cháu là|tao là|tên(?: tôi| mình| anh| chị| em| chú| bác| cô)?(?: là)?|gọi (?:tôi|mình|anh|chị|em|chú|bác|cô|tao) là|cứ gọi (?:tôi|mình|anh|chị|em|chú|bác|cô|tao) là)\s+([\p{L}\s]+)""",
            RegexOption.IGNORE_CASE
        )

        val match = introRegex.find(message)
        if (match != null && match.groupValues.size > 1) {
            val rawCaptured = match.groupValues[1].trim()
            val rawWords = rawCaptured.split("\\s+".toRegex()).filter { it.isNotBlank() }.toMutableList()

            // Loại bỏ các từ đệm ở cuối câu: "đây", "nè", "nhé", "ạ", "ơi"...
            while (rawWords.isNotEmpty() && STOP_WORDS.contains(rawWords.last().lowercase())) {
                rawWords.removeAt(rawWords.size - 1)
            }

            if (rawWords.isEmpty()) {
                // Người dùng chỉ xưng đại từ, không có tên
                return IncomingPerson(
                    name = "",
                    gender = gender,
                    preferredPronoun = pronoun,
                    role = "friend"
                )
            }

            // Trường hợp đặc biệt: Danh xưng xưng hô "chú tiểu" -> Danh xưng: "Chú", Tên: "" (không tạo hồ sơ tên riêng)
            val combinedLower = rawWords.joinToString(" ") { it.lowercase() }
            if (combinedLower == "chú tiểu" || combinedLower.contains("chú tiểu")) {
                return IncomingPerson(
                    name = "",
                    gender = "male",
                    preferredPronoun = "Chú",
                    role = "friend"
                )
            }

            // Nếu từ đầu tiên là đại từ và có từ phía sau (ví dụ: "mình là anh Nam" -> từ đầu là "anh")
            if (rawWords.size > 1 && PRONOUN_BLACKLIST.contains(rawWords.first().lowercase())) {
                val leadingPronounWord = rawWords.first().lowercase()
                when (leadingPronounWord) {
                    "chú" -> { pronoun = "Chú"; gender = "male" }
                    "bác" -> { pronoun = "Bác" }
                    "cô" -> { pronoun = "Cô"; gender = "female" }
                    "dì" -> { pronoun = "Dì"; gender = "female" }
                    "cậu" -> { pronoun = "Cậu"; gender = "male" }
                    "mợ" -> { pronoun = "Mợ"; gender = "female" }
                    "anh" -> { pronoun = "Anh"; gender = "male" }
                    "chị" -> { pronoun = "Chị"; gender = "female" }
                    "em" -> { pronoun = "Em" }
                }
                rawWords.removeAt(0)
            }

            // Nếu chỉ có 1 từ duy nhất và từ đó lại là đại từ (ví dụ câu nói: "gọi tôi là chú")
            if (rawWords.size == 1 && PRONOUN_BLACKLIST.contains(rawWords.first().lowercase())) {
                val word = rawWords.first().lowercase()
                val detectedPronoun = when (word) {
                    "chú" -> "Chú"
                    "bác" -> "Bác"
                    "cô" -> "Cô"
                    "dì" -> "Dì"
                    "cậu" -> "Cậu"
                    "mợ" -> "Mợ"
                    "anh" -> "Anh"
                    "chị" -> "Chị"
                    "em" -> "Em"
                    "cháu" -> "Cháu"
                    else -> pronoun
                }
                return IncomingPerson(
                    name = "",
                    gender = gender,
                    preferredPronoun = detectedPronoun,
                    role = "friend"
                )
            }

            // Viết hoa chuẩn cho tất cả các từ trong tên (hỗ trợ tên kép: "Hoàng Anh", "Hoài An", "Nguyễn Hoàng Nam")
            val formattedName = rawWords.joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }

            val role = if (formattedName.equals("nam", ignoreCase = true)) "admin" else "friend"

            return IncomingPerson(
                name = formattedName,
                gender = gender,
                preferredPronoun = pronoun,
                role = role
            )
        }
        return null
    }
}
