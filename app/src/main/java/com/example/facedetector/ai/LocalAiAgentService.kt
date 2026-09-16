package com.example.facedetector.ai

import android.util.Log
import com.example.facedetector.MainActivity
import com.example.facedetector.data.EveDatabaseHelper
import com.example.facedetector.data.NotificationItem
import com.example.facedetector.network.IncomingPerson
import com.example.facedetector.network.N8nService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LocalAiActionResult(
    val replyText: String,
    val emotion: String = "speaking",
    val action: String? = null
)

object LocalAiAgentService {

    private const val TAG = "LocalAiAgentService"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    // Fast client for LLM REST API calls
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * Executes a chat completion request against OpenRouter or OpenAI-compatible endpoint.
     */
    private suspend fun callLlmApi(
        systemPrompt: String,
        userPrompt: String,
        dbHelper: EveDatabaseHelper,
        temperature: Double = 0.3,
        maxTokens: Int = 300
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = dbHelper.getAiApiKey()
        val model = dbHelper.getAiModel()
        val baseUrl = dbHelper.getAiBaseUrl()

        if (apiKey.isBlank() || baseUrl.isBlank()) {
            Log.w(TAG, "Missing AI API key or Base URL in settings")
            return@withContext null
        }

        try {
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            }

            val payload = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("temperature", temperature)
                put("max_tokens", maxTokens)
            }

            val requestBuilder = Request.Builder()
                .url(baseUrl)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "https://eve-ai.local")
                .addHeader("X-Title", "EVE Face Detector Assistant")

            val response = client.newCall(requestBuilder.build()).execute()
            val bodyString = response.body?.string() ?: ""

            if (!response.isSuccessful || bodyString.isBlank()) {
                Log.w(TAG, "OpenRouter LLM request failed (code: ${response.code}): $bodyString")
                return@withContext null
            }

            val rootJson = JSONObject(bodyString)
            val choices = rootJson.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val messageObj = choices.getJSONObject(0).optJSONObject("message")
                return@withContext messageObj?.optString("content")?.trim()
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Exception calling Local AI Agent (${e.message})", e)
            null
        }
    }

    /**
     * Tác vụ 1: Gộp & Tóm tắt Ngữ nghĩa Thông báo khi Admin xuất hiện (Executive Briefing).
     * Phân tích ngữ nghĩa để nhóm các thông báo tương đồng/liên quan và tạo câu nói tự nhiên.
     */
    suspend fun summarizeNotifications(
        adminName: String,
        pronoun: String,
        notifications: List<NotificationItem>,
        dbHelper: EveDatabaseHelper
    ): String = withContext(Dispatchers.Default) {
        if (notifications.isEmpty()) {
            return@withContext "Dạ em chào $pronoun $adminName! Không có thông báo mới nào ạ."
        }

        if (notifications.size == 1) {
            val item = notifications[0]
            val content = item.body.ifBlank { item.title }
            return@withContext "Dạ em chào $pronoun $adminName! $pronoun có một thông báo mới: $content. Hết ạ!"
        }

        val rawListJson = JSONArray().apply {
            notifications.forEachIndexed { index, notif ->
                put(JSONObject().apply {
                    put("id", notif.id)
                    put("index", index + 1)
                    put("title", notif.title)
                    put("body", notif.body)
                    put("time_millis", notif.receivedAt)
                })
            }
        }

        val systemPrompt = """
            Bạn là EVE - Nữ trợ lý quản gia AI thông minh, tinh tế và lễ phép.
            Người dùng hiện tại là Quản trị viên (Admin) tên là: "$adminName", danh xưng: "$pronoun" (ví dụ: Anh Nam, Chị Linh).
            
            NHIỆM VỤ:
            Bạn sẽ nhận được danh sách các thông báo nhận được trong lúc Admin vắng mặt. Các thông báo này do AI hoặc cảm biến khác nhau tạo ra, có thể diễn đạt bằng câu từ khác nhau nhưng cùng nói về một sự kiện hoặc chủ đề liên quan (ví dụ: phát hiện chuyển động ở cửa, shipper giao hàng, cảnh báo nhiệt độ, lỗi hệ thống).
            
            YÊU CẦU:
            1. PHÂN TÍCH NGỮ NGHĨA (SEMANTIC CLUSTERING):
               - Tự động gom các thông báo cùng bản chất hoặc liên quan vào 1 sự việc chung.
               - Đếm số lần lặp lại nếu có (ví dụ: "có 3 cảnh báo về cửa chính mở").
            2. TẠO BẢN TÓM TẮT ĐỌC BẰNG GIỌNG NÓI (TTS):
               - Tối đa 2 đến 3 câu văn liền mạch, mạch lạc, xúc tích.
               - Mở đầu tự nhiên: "Dạ $pronoun $adminName ơi, em xin phép báo cáo..."
               - Liệt kê các sự việc chính đã được gộp.
               - Kết thúc: "Hết ạ!" hoặc "Em xin hết ạ!".
            3. TUYỆT ĐỐI KHÔNG dùng ký tự markdown (như dấu sao **, gạch đầu dòng -, ngoặc vuông) vì văn bản này sẽ được truyền thẳng vào bộ đọc Text-to-Speech phát ra loa.
        """.trimIndent()

        val userPrompt = """
            Dưới đây là danh sách ${notifications.size} thông báo thô:
            ${rawListJson.toString(2)}
            
            Hãy tạo bản tin tóm tắt cho $pronoun $adminName ngay:
        """.trimIndent()

        val aiResult = callLlmApi(systemPrompt, userPrompt, dbHelper, temperature = 0.3, maxTokens = 250)
        if (!aiResult.isNullOrBlank()) {
            return@withContext cleanMarkdownForTts(aiResult)
        }

        // Fallback tại chỗ nếu AI timeout hoặc offline
        fallbackLocalNotificationSummary(adminName, pronoun, notifications)
    }

    /**
     * Tác vụ 2: Phân loại Ý định Xác nhận / Từ chối bằng ngữ nghĩa tự nhiên (Confirmation Intent).
     * Hiểu các câu nói tiếng lóng, tiếng đệm tiếng Việt ("chuẩn cơm mẹ nấu", "nhầm to rồi", "anh chứ ai").
     */
    suspend fun classifyConfirmationIntent(
        transcript: String,
        contextQuestion: String?,
        dbHelper: EveDatabaseHelper
    ): MainActivity.ConfirmationIntent = withContext(Dispatchers.Default) {
        val trimmed = transcript.trim()
        if (trimmed.isBlank()) return@withContext MainActivity.ConfirmationIntent.AMBIGUOUS

        val systemPrompt = """
            Bạn là bộ phân loại ý định xác nhận (Intent Classifier) cho trợ lý tiếng Việt.
            Nhiệm vụ: Dựa vào ngữ cảnh câu hỏi của robot và câu trả lời của người dùng, phân loại vào ĐÚNG 1 TRONG 3 NHÃN:
            - CONFIRMED: Người dùng đồng ý, xác nhận đúng người, chuẩn xác, tán thành (ví dụ: "chuẩn", "chuẩn rồi", "đúng đấy", "chuẩn cơm mẹ nấu", "anh đây chứ ai", "đúng người rồi", "ok em", "chuẩn đét", "không, đúng rồi").
            - DENIED: Người dùng phủ nhận, phản đối, bảo nhầm người, từ chối (ví dụ: "sai rồi", "nhầm to rồi", "không phải", "nhầm người rồi bé ơi", "anh khác cơ", "chưa từng gặp bao giờ", "lộn rồi").
            - AMBIGUOUS: Người dùng nói lạc đề, câu nói mơ hồ hoặc tiếng ồn không rõ ý.
            
            CHỈ TRẢ VỀ ĐÚNG 1 TỪ DUY NHẤT: CONFIRMED, DENIED, hoặc AMBIGUOUS.
        """.trimIndent()

        val userPrompt = """
            Câu hỏi của Robot: "${contextQuestion ?: "Có phải bạn là người quen không?"}"
            Câu trả lời của Người: "$trimmed"
            Nhãn:
        """.trimIndent()

        val aiResult = callLlmApi(systemPrompt, userPrompt, dbHelper, temperature = 0.0, maxTokens = 10)
        if (!aiResult.isNullOrBlank()) {
            val upper = aiResult.uppercase()
            when {
                upper.contains("CONFIRMED") -> return@withContext MainActivity.ConfirmationIntent.CONFIRMED
                upper.contains("DENIED") -> return@withContext MainActivity.ConfirmationIntent.DENIED
                upper.contains("AMBIGUOUS") -> return@withContext MainActivity.ConfirmationIntent.AMBIGUOUS
            }
        }

        // Fallback về bộ luật cục bộ nếu AI không phản hồi kịp
        fallbackClassifyIntent(trimmed)
    }

    /**
     * Tác vụ 3: Trích xuất Danh tính & Xưng hô Tự nhiên (Identity & Pronoun Extraction).
     * Bóc tách tên và danh xưng từ các câu nói giới thiệu phức tạp bằng LLM.
     */
    suspend fun extractIdentityAndPronoun(
        utterance: String,
        visualGender: String?,
        dbHelper: EveDatabaseHelper
    ): IncomingPerson? = withContext(Dispatchers.Default) {
        val trimmed = utterance.trim()
        if (trimmed.isBlank()) return@withContext null

        val systemPrompt = """
            Bạn là chuyên gia ngôn ngữ tiếng Việt trích xuất thông tin người dùng từ câu nói tự giới thiệu.
            Trích xuất các trường sau dưới dạng JSON duy nhất:
            - name: Tên riêng (viết hoa chữ cái đầu, ví dụ: "Nam", "Hoàng Anh", "Mai"). Nếu người dùng chỉ nói danh xưng mà không có tên (ví dụ: "gọi tôi là chú nhé") thì để name = "".
            - preferredPronoun: Đại từ xưng hô mong muốn (ví dụ: "Anh", "Chị", "Chú", "Bác", "Cô", "Dì", "Em", "Bạn"). Mặc định: "Bạn".
            - gender: "male", "female", hoặc "unknown". (Nếu có visualGender tham khảo: ${visualGender ?: "unknown"}).
            - role: "admin" nếu tên là "Nam" hoặc câu nói thể hiện quyền quản trị/chủ nhân; còn lại là "friend".
            
            CHỈ TRẢ VỀ JSON HỢP LỆ, KHÔNG KÈM GIẢI THÍCH:
            {"name": "...", "preferredPronoun": "...", "gender": "...", "role": "..."}
        """.trimIndent()

        val userPrompt = "Câu nói của người dùng: \"$trimmed\""

        val aiResult = callLlmApi(systemPrompt, userPrompt, dbHelper, temperature = 0.1, maxTokens = 100)
        if (!aiResult.isNullOrBlank()) {
            try {
                val cleanJson = aiResult.substringAfter("{").substringBeforeLast("}")
                val obj = JSONObject("{$cleanJson}")
                val name = obj.optString("name", "").trim()
                val pronoun = obj.optString("preferredPronoun", "Bạn").trim()
                val gender = obj.optString("gender", visualGender ?: "unknown").trim()
                val role = obj.optString("role", if (name.equals("nam", ignoreCase = true)) "admin" else "friend").trim()

                if (name.isNotBlank() || pronoun.isNotBlank()) {
                    return@withContext IncomingPerson(
                        name = name,
                        gender = gender,
                        preferredPronoun = pronoun.ifBlank { "Bạn" },
                        role = role
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing AI identity JSON: ${e.message}")
            }
        }

        // Fallback về regex local nếu AI lỗi
        N8nService.extractNameFromMessage(trimmed)
    }

    /**
     * Tác vụ 4: Phân tích Hành động Hệ thống (Logout, Quét lại mặt, Cử chỉ) & Fallback Chat.
     */
    suspend fun parseSystemAction(
        utterance: String,
        currentPersonName: String?,
        currentPronoun: String?,
        dbHelper: EveDatabaseHelper
    ): LocalAiActionResult = withContext(Dispatchers.Default) {
        val p = currentPronoun ?: "bạn"
        val name = currentPersonName ?: ""

        val systemPrompt = """
            Bạn là EVE - Robot trợ lý thông minh. Hãy phân loại câu nói của người dùng ($p $name):
            1. Lệnh hệ thống (action):
               - "logout": người dùng bảo đi ngủ, tắt app, nghỉ đi, out app, tạm biệt.
               - "update-face-detect": người dùng bảo quét lại mặt, cập nhật khuôn mặt, nhìn lại mặt.
               - "wave-right", "wave-left", "spin-360", "scan", "directive-plant", "blaster", "curious", "love", "shrug", "clap", "jet-boost", "happy", "smile", "sad", "angry", "shy": cử chỉ, biểu cảm, kỹ năng.
               - null nếu chỉ là trò chuyện thông thường.
            2. Câu phản hồi (replyText): ngắn gọn (1 câu), tự nhiên, thân thiện và lễ phép xưng "em" gọi "$p $name".
            3. Emotion tương ứng: nếu câu nói của người dùng yêu cầu biểu cảm hay hành động nào thì đặt emotion đúng hành động/biểu cảm đó ("love", "clap", "shrug", "curious", "scan", "directive-plant", "blaster", "jet-boost", "happy", "smile", "sad", "angry", "shy", "wave-right", "wave-left", "spin-360"), nếu chỉ là câu nói chuyện thông thường thì dùng "speaking".
            
            TRẢ VỀ JSON:
            {"action": "logout|update-face-detect|...|null", "replyText": "...", "emotion": "speaking|happy|love|..."}
        """.trimIndent()

        val aiResult = callLlmApi(systemPrompt, utterance, dbHelper, temperature = 0.2, maxTokens = 120)
        if (!aiResult.isNullOrBlank()) {
            try {
                val clean = aiResult.substringAfter("{").substringBeforeLast("}")
                val obj = JSONObject("{$clean}")
                val act = if (obj.has("action") && !obj.isNull("action") && obj.getString("action") != "null") {
                    obj.getString("action").trim()
                } else null
                val reply = obj.optString("replyText", "Dạ em nghe rõ rồi ạ!")
                val emotion = obj.optString("emotion", "speaking")
                return@withContext LocalAiActionResult(reply, emotion, act)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing AI action JSON: ${e.message}")
            }
        }

        // Fallback local
        val localFallback = N8nService.fallbackLocalParser(utterance, null)
        LocalAiActionResult(localFallback.replyText, localFallback.emotion, localFallback.action)
    }

    // ==========================================
    // OFFLINE & GRACEFUL FALLBACKS
    // ==========================================

    private fun cleanMarkdownForTts(text: String): String {
        return text.replace(Regex("""[\*\_#`~\[\]\(\)\{\}\<\>]"""), "")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun fallbackLocalNotificationSummary(
        adminName: String,
        pronoun: String,
        notifications: List<NotificationItem>
    ): String {
        val n = notifications.size
        val sb = java.lang.StringBuilder()
        sb.append("Dạ $pronoun $adminName ơi, em xin phép báo cáo có $n thông báo mới được gửi đến $pronoun: ")
        notifications.take(4).forEachIndexed { index, notif ->
            val countWord = when (index) {
                0 -> "Một là"
                1 -> "Hai là"
                2 -> "Ba là"
                else -> "Bốn là"
            }
            val text = notif.body.ifBlank { notif.title }
            sb.append("$countWord: $text. ")
        }
        if (n > 4) {
            sb.append("và ${n - 4} thông báo khác nữa ạ. ")
        }
        sb.append("Em xin hết ạ!")
        return sb.toString()
    }

    private fun fallbackClassifyIntent(transcript: String): MainActivity.ConfirmationIntent {
        val lower = transcript.lowercase().trim()
        val confirmPhrases = listOf(
            "chuẩn", "đúng", "phải", "chính xác", "anh đây", "mình đây", "tôi đây", "em đây", "ok", "oke", "dạ đúng", "ừ đúng"
        )
        val denyPhrases = listOf(
            "sai", "nhầm", "lộn", "không phải", "đâu phải", "chưa phải", "người khác", "ai đấy"
        )
        val hasDeny = denyPhrases.any { lower.contains(it) }
        val hasConfirm = confirmPhrases.any { lower.contains(it) }

        return when {
            hasConfirm && !hasDeny -> MainActivity.ConfirmationIntent.CONFIRMED
            hasDeny && !hasConfirm -> MainActivity.ConfirmationIntent.DENIED
            else -> MainActivity.ConfirmationIntent.AMBIGUOUS
        }
    }
}
