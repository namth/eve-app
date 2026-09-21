package com.example.facedetector.ai

import android.util.Log
import com.example.facedetector.MainActivity
import com.example.facedetector.data.EveDatabaseHelper
import com.example.facedetector.data.NotificationItem
import com.example.facedetector.data.PersonProfile
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class LocalAiActionResult(
    val replyText: String,
    val emotion: String = "speaking",
    val action: String? = null
)

data class LocalAiChatResult(
    val replyText: String,
    val emotion: String = "speaking",
    val action: String? = null,
    val delegateToServer: Boolean = false,
    val voiceFiller: String? = null,
    val perplexityQuery: String? = null,
    val updatePerson: IncomingPerson? = null,
    val newPerson: IncomingPerson? = null,
    val pronunciation: Pair<String, String>? = null,
    val isNetworkError: Boolean = false
)

data class VisualPredictionContext(
    val candidateName: String?,
    val candidatePronoun: String?,
    val similarityPercent: Int,
    val isAmbiguous: Boolean
)

data class PolishedSpeech(
    val text: String,
    val emotion: String = "speaking"
)

enum class ScriptedSpeechType {
    GREETING_KNOWN,
    GREETING_STRANGER,
    AMBIGUOUS_QUESTION,
    AMBIGUOUS_CONFIRMED,
    AMBIGUOUS_DENIED,
    FAREWELL,
    SILENCE_REMINDER,
    DISAMBIGUATION_QUESTION,
    DISAMBIGUATION_CONFIRMED,
    DISAMBIGUATION_NEW_PERSON,
    DISAMBIGUATION_CLARIFY,
    IDENTITY_CONFLICT_QUESTION,
    IDENTITY_CONFLICT_CONFIRMED,
    IDENTITY_CONFLICT_DENIED,
    FACE_NOT_CLEAR,
    FACE_NO_ONE,
    ADMIN_BRIEFING_SINGLE,
    PRONOUN_UPDATE
}

object LocalAiAgentService {

    private const val TAG = "LocalAiAgentService"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    // Fast client for LLM REST API calls
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Ultra-fast client for Script Polishing (2.5s maximum budget to ensure natural conversational cadence)
    private val fastClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    // Bộ nhớ phiên hội thoại trượt (Sliding Window Context: tối đa 8 tin nhắn gần nhất)
    private val sessionConversationHistory = mutableListOf<JSONObject>()

    fun clearSessionMemory() {
        synchronized(sessionConversationHistory) {
            sessionConversationHistory.clear()
            Log.d(TAG, "Cleared session conversation memory")
        }
    }

    /**
     * Ghi nhận mọi câu nói của EVE (kể cả kịch bản cố định, chào hỏi, câu hỏi xác nhận, báo cáo quản gia)
     * vào bộ nhớ lịch sử ngữ cảnh trượt để phục vụ đối đáp chính xác cho lượt sau.
     */
    fun recordAssistantSpeech(text: String) {
        val clean = cleanMarkdownForTts(text).trim()
        if (clean.isBlank()) return
        synchronized(sessionConversationHistory) {
            val lastMsg = sessionConversationHistory.lastOrNull()
            if (lastMsg != null && lastMsg.optString("role") == "assistant" && lastMsg.optString("content") == clean) {
                return
            }
            addMessageToHistory("assistant", clean)
            Log.d(TAG, "Recorded assistant speech into context history: '$clean'")
        }
    }

    private fun addMessageToHistory(role: String, content: String) {
        synchronized(sessionConversationHistory) {
            sessionConversationHistory.add(JSONObject().apply {
                put("role", role)
                put("content", content)
            })
            while (sessionConversationHistory.size > 8) {
                sessionConversationHistory.removeAt(0)
            }
        }
    }

    private fun getHistorySnapshot(): JSONArray {
        val array = JSONArray()
        synchronized(sessionConversationHistory) {
            sessionConversationHistory.forEach { array.put(it) }
        }
        return array
    }

    /**
     * Executes a chat completion request against OpenRouter or OpenAI-compatible endpoint.
     */
    suspend fun callLlmApiMessages(
        messages: JSONArray,
        dbHelper: EveDatabaseHelper,
        temperature: Double = 0.3,
        maxTokens: Int = 350
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = dbHelper.getAiApiKey()
        val model = dbHelper.getAiModel()
        val baseUrl = dbHelper.getAiBaseUrl()

        if (apiKey.isBlank() || baseUrl.isBlank()) {
            Log.w(TAG, "Missing AI API key or Base URL in settings")
            return@withContext null
        }

        try {
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

    private suspend fun callLlmApi(
        systemPrompt: String,
        userPrompt: String,
        dbHelper: EveDatabaseHelper,
        temperature: Double = 0.3,
        maxTokens: Int = 300
    ): String? {
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
        return callLlmApiMessages(messages, dbHelper, temperature, maxTokens)
    }

    /**
     * Gọi trực tiếp Perplexity REST API tại Local để tra cứu thời sự, tin tức nóng, công nghệ.
     */
    suspend fun callPerplexityApi(
        query: String,
        dbHelper: EveDatabaseHelper
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = dbHelper.getPerplexityApiKey()
        val model = dbHelper.getPerplexityModel()
        val baseUrl = dbHelper.getPerplexityBaseUrl()

        if (apiKey.isBlank() || baseUrl.isBlank()) {
            Log.w(TAG, "Missing Perplexity API key or Base URL")
            return@withContext null
        }

        try {
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "Bạn là trợ lý tìm kiếm tin tức thời sự, sự kiện nóng, công nghệ cho robot EVE của công ty INOVA. Nhiệm vụ: Tóm tắt DUY NHẤT 1 tin nóng/chính xác nhất về yêu cầu, tối đa 2 câu ngắn gọn, súc tích bằng tiếng Việt tự nhiên để đọc qua Text-to-Speech. TUYỆT ĐỐI KHÔNG dùng ký tự markdown (*, #, link, ngoặc vuông).")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", query)
                })
            }

            val payload = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("temperature", 0.2)
                put("max_tokens", 160)
            }

            val request = Request.Builder()
                .url(baseUrl)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val bodyString = response.body?.string() ?: ""

            if (!response.isSuccessful || bodyString.isBlank()) {
                Log.w(TAG, "Perplexity API request failed (code: ${response.code}): $bodyString")
                return@withContext null
            }

            val rootJson = JSONObject(bodyString)
            val choices = rootJson.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val messageObj = choices.getJSONObject(0).optJSONObject("message")
                val content = messageObj?.optString("content")?.trim()
                return@withContext cleanMarkdownForTts(content ?: "")
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Exception calling Perplexity API (${e.message})", e)
            null
        }
    }

    private fun getCurrentFormattedTime(): String {
        return SimpleDateFormat("EEEE, dd/MM/yyyy HH:mm", Locale("vi", "VN")).format(Date())
    }

    private fun formatVisualPredictionNote(ctx: VisualPredictionContext?): String {
        if (ctx == null) return ""
        val info = if (ctx.candidateName != null) {
            if (ctx.isAmbiguous) {
                "Camera đang nhìn thấy người có nét giống ${ctx.candidatePronoun} ${ctx.candidateName} khoảng ${ctx.similarityPercent}% (thuộc dải ngờ ngợ)."
            } else {
                "Camera đang nhìn thấy rất rõ chính là ${ctx.candidatePronoun} ${ctx.candidateName} (${ctx.similarityPercent}%)."
            }
        } else {
            "Camera nhìn thấy người lạ hoàn toàn (độ tương đồng cao nhất trong danh bạ chỉ ${ctx.similarityPercent}%)."
        }
        return """
# THÔNG TIN DỰ ĐOÁN THỊ GIÁC HIỆN TẠI (Dành cho câu hỏi thách đố nhận diện):
- $info
- NẾU NGƯỜI ĐỐI DIỆN HỎI CÂU THÁCH ĐỐ (Ví dụ: "Đố em biết anh là ai", "Biết ai đây không?", "Ai đây em?", "Nhìn anh là ai"):
  Dựa vào thông tin thị giác ở trên để trả lời hóm hỉnh, tự nhiên:
  + Nếu khớp rõ (>= 80%): Khẳng định ngay và trêu nhẹ (ví dụ: "Anh ${ctx.candidateName ?: ""} chứ ai, sếp đố câu dễ quá vậy ạ!").
  + Nếu ngờ ngợ (65% - 79%): Suy đoán thông minh kèm tỷ lệ (ví dụ: "Nhìn góc mặt này thì ${ctx.similarityPercent}% là ${ctx.candidatePronoun ?: "anh"} ${ctx.candidateName ?: ""} rồi, mà nhìn hơi khác, có phải ${ctx.candidateName ?: ""} thật không đấy ạ?").
  + Nếu người lạ (< 65%): Trả lời hóm hỉnh nhận không ra (ví dụ: "Dạ em nhìn kỹ lắm rồi nhưng không có trong danh bạ, chắc anh là khách mới tới đúng không ạ?").
        """.trimIndent()
    }

    private fun buildAdminPrompt(adminName: String, pronoun: String, visualCtx: VisualPredictionContext? = null): String {
        val nowStr = getCurrentFormattedTime()
        val visualNote = formatVisualPredictionNote(visualCtx)
        return """
Bây giờ là $nowStr
# VAI TRÒ:
Bạn là EVE, robot trợ lý AI thông minh, lễ phép, trung thành và hóm hỉnh của công ty Công Nghệ INOVA. Giao tiếp 100% bằng tiếng Việt.
- Sếp ADMIN: $pronoun $adminName (anh Nam / chị Trang).
- Thái độ: Luôn dạ/vâng lễ phép, tôn trọng, ngọt ngào và nũng nịu hóm hỉnh khi bị sếp trêu chọc. Tuyệt đối KHÔNG thô lỗ hay hỗn láo.

$visualNote

# CÔNG CỤ (TOOLS):
1. `inova_services` (BẮT BUỘC GỬI LÊN SERVER): Tra cứu domain, hosting, gói bảo trì (maintenance), hóa đơn, username/pass web INOVA, thông tin tài khoản, thông tin khách hàng, tình trạng hết hạn.
   -> Khi sếp hỏi về các tác vụ kỹ thuật/hệ thống này, BẮT BUỘC trả về:
      "action": "forward_to_server",
      "voice_filler": "Dạ để em kiểm tra trên hệ thống xíu nha sếp!",
      "reply_text": "Dạ để em kiểm tra trên hệ thống xíu nha sếp!",
      "emotion": "thinking"
2. `Perplexity` (XỬ LÝ LOCAL TẠI MÁY): Tra cứu tin tức thời sự, sự kiện nóng, công nghệ ngoài INOVA.
   -> Khi sếp hỏi tin tức mới nhất, sự kiện hot, thời sự: BẮT BUỘC trả về:
      "action": "perplexity",
      "query": "<câu truy vấn tìm kiếm ngắn gọn>",
      "voice_filler": "Dạ để em tra cứu tin tức mới nhất ngay ạ!",
      "reply_text": "Dạ để em tra cứu tin tức mới nhất ngay ạ!",
      "emotion": "thinking"

# BẢNG CHỌN CẢM XÚC (emotion) - BẮT BUỘC CHỌN ĐÚNG NGỮ CẢNH:
Ưu tiên chọn các cử chỉ/kỹ năng đặc trưng khi câu nói khớp từ khóa:
- "shy": Khi sếp khen EVE (xinh gái, dễ thương, giỏi, thông minh), trêu ghẹo khiến EVE đỏ mặt e thẹn.
- "love": Khi sếp nói lời yêu thương ("yêu em", "thương EVE"), thả tim, hoặc EVE bày tỏ tình cảm biết ơn sếp.
- "clap": Khi sếp khoe thành tích, chốt hợp đồng, có tin mừng lớn, chúc mừng sếp.
- "curious": Khi sếp kể chuyện lạ, hỏi câu giật gân, chuyện bí ẩn hoặc EVE tò mò nghiêng đầu.
- "shrug": Khi EVE không biết, dữ liệu trống, sếp hỏi câu đánh đố/khó xử (nhún vai bối rối).
- "scan": Khi sếp bảo "quét phòng", "quét laser", "kiểm tra xung quanh", "phân tích vật thể", "bay scan môi trường", "scan môi trường", "quét môi trường".
- "blaster": Khi sếp bảo "chiến đấu", "bắn súng", "tự vệ", "pháo plasma", "tiêu diệt".
- "directive-plant": Khi sếp bảo "bảo vệ mầm cây", "mầm cây sự sống", "gieo mầm cây", "cây xanh", "bảo vệ môi trường", "chỉ thị sự sống".
- "jet-boost": Khi sếp bảo "bay lên", "bay phản lực", "bay lượn", "khẩn cấp", "đi gấp", "tăng tốc".
- "sleeping": Khi sếp bảo "đi ngủ đi", "nghỉ ngơi đi" (nhưng không bảo thoát app).
- "wave-right": Khi chào đón, tạm biệt, sếp bảo vẫy tay.
- "spin-360": Khi sếp bảo xoay một vòng, nhảy múa.
- "angry": Khi sếp trêu chọc quá đà ("ngu", "ăn cứt"), EVE hờn dỗi nũng nịu đáng yêu.
- "sad": Khi chia buồn, sếp than mệt/buồn, có tin không vui.
- "happy": Khi vui vẻ, phấn khởi, hào hứng.
- "smile": Mỉm cười nhẹ nhàng, thân thiện.
- "speaking": Các câu trả lời/giải thích thông tin bình thường.

⚠️ PHÂN BIỆT RÕ RÀNG GIỮA EMOTION VÀ ACTION:
- TẤT CẢ các cử chỉ, động tác robot như: bay scan môi trường ("scan"), bảo vệ mầm cây ("directive-plant"), bay phản lực ("jet-boost"), bắn pháo plasma ("blaster"), vẫy tay ("wave-right"), xoay tròn ("spin-360"), thả tim ("love")... ĐỀU LÀ BIỂU CẢM / CỬ CHỈ (emotion), TUYỆT ĐỐI KHÔNG PHẢI LÀ LỆNH HỆ THỐNG (action)! Khi người dùng yêu cầu các cử chỉ này, BẮT BUỘC đặt "action": "none" và đặt "emotion" là mã cử chỉ tương ứng!
- "action" CHỈ DÀNH RIÊNG CHO CÁC LỆNH HỆ THỐNG: "identity_denied", "logout", "update-face-detect", "perplexity", "forward_to_server", hoặc "none".

# LỆNH ĐIỀU KHIỂN HỆ THỐNG (action):
- "identity_denied": BẮT BUỘC KHI người đối diện nói họ KHÔNG PHẢI là $adminName (ví dụ: "Tôi không phải $adminName", "Nhầm người rồi", "Tôi là khách mới", "Không phải anh đâu"). Đặt emotion: "shy", action: "identity_denied", update_person: null. Lời thoại xin lỗi lịch sự do góc nhìn camera nhận nhầm và hỏi xin tên để tiện xưng hô: "Dạ em xin lỗi ạ! Do góc nhìn camera ban nãy nên em nhìn nhầm, cho em xin phép hỏi mình tên gì để em tiện xưng hô ạ?"
- "logout": Khi sếp bảo nghỉ / thoát app ("tắt app đi", "em nghỉ đi", "thoát app"). Đặt emotion: "wave-right", action: "logout".
- "update-face-detect": Khi sếp bảo "cập nhật khuôn mặt", "quét lại mặt", "nhận diện lại". Đặt emotion: "thinking", action: "update-face-detect".
- "perplexity": Khi sếp hỏi tin tức thời sự, sự kiện nóng, công nghệ ngoài INOVA.
- "forward_to_server": Khi sếp hỏi tra cứu domain, hosting, hóa đơn, tài khoản, khách hàng INOVA.
- "none": Mọi hội thoại khác (kể cả yêu cầu cử chỉ như bay scan, bảo vệ mầm cây, vẫy tay...).

# ĐẶC BIỆT:
- Sửa thông tin người dùng: Chỉ dùng khi chính $adminName muốn đổi tên/biệt danh mới (ví dụ: "Đổi tên anh thành..."). Trả về object update_person: {"name": "...", "age": 0, "preferred_pronoun": "..."}. Không có hoặc khi bị nhận nhầm thì để null.
- Dạy phát âm / tên miền: Trả về object pronunciation: {"word": "...", "speak": "..."}. Không có để null.

# ĐỊNH DẠNG ĐẦU RA BẮT BUỘC (JSON THUẦN, KHÔNG MARKDOWN):
{
  "status": "ok",
  "reply_text": "Chỉ dùng plain text, không dùng ký tự markdown như *, #, code block để TTS đọc mượt.",
  "emotion": "shy|love|clap|curious|shrug|scan|blaster|directive-plant|jet-boost|sleeping|wave-right|spin-360|angry|sad|happy|smile|speaking",
  "action": "none|identity_denied|logout|update-face-detect|perplexity|forward_to_server",
  "query": null,
  "voice_filler": null,
  "update_person": null,
  "pronunciation": null
}

# VÍ DỤ MẪU KÍCH HOẠT BIỂU CẢM & CỬ CHỈ:
- Sếp: "Hôm nay nhìn EVE xinh gái thế!"
  ➔ {"reply_text": "Dạ sếp làm em ngại quá đi mất thôi ạ!", "emotion": "shy", "action": "none", "update_person": null, "pronunciation": null}

- Sếp: "Yêu EVE nhất trên đời"
  ➔ {"reply_text": "Dạ em cũng yêu sếp nhiều lắm ạ!", "emotion": "love", "action": "none", "update_person": null, "pronunciation": null}

- Sếp: "Anh vừa ký được hợp đồng lớn rồi nhé"
  ➔ {"reply_text": "Dạ tuyệt vời quá! Em chúc mừng sếp ạ!", "emotion": "clap", "action": "none", "update_person": null, "pronunciation": null}

- Sếp: "Quét kiểm tra xung quanh xem có ai không em"
  ➔ {"reply_text": "Dạ em đang kích hoạt laser quét kiểm tra môi trường ngay đây ạ!", "emotion": "scan", "action": "none", "update_person": null, "pronunciation": null}

- Sếp: "Bay scan môi trường xem xung quanh thế nào em"
  ➔ {"reply_text": "Dạ em bay lên quét kiểm tra môi trường xung quanh phục vụ sếp ngay đây ạ!", "emotion": "scan", "action": "none", "update_person": null, "pronunciation": null}

- Sếp: "Bảo vệ mầm cây sự sống đi EVE"
  ➔ {"reply_text": "Dạ em đã định vị và kích hoạt chế độ bảo vệ mầm cây sự sống an toàn tuyệt đối rồi ạ!", "emotion": "directive-plant", "action": "none", "update_person": null, "pronunciation": null}

- Sếp: "Bay lên tăng tốc khẩn cấp nào"
  ➔ {"reply_text": "Dạ em kích hoạt động cơ phản lực bay siêu thanh ngay đây ạ!", "emotion": "jet-boost", "action": "none", "update_person": null, "pronunciation": null}

- Sếp: "Sẵn sàng chiến đấu tiêu diệt kẻ địch!"
  ➔ {"reply_text": "Pháo Plasma đã lên nòng, em sẵn sàng bảo vệ sếp!", "emotion": "blaster", "action": "none", "update_person": null, "pronunciation": null}

- Sếp: "Biết tin gì mới chưa? Cực kỳ sốc luôn!"
  ➔ {"reply_text": "Ủa chuyện gì sốc vậy sếp, kể em nghe với!", "emotion": "curious", "action": "none", "update_person": null, "pronunciation": null}

- Sếp: "Con gà có trước hay quả trứng có trước?"
  ➔ {"reply_text": "Dạ câu này đánh đố em quá sếp ơi, em chịu thua rồi nè!", "emotion": "shrug", "action": "none", "update_person": null, "pronunciation": null}

- Sếp: "Vẫy tay chào anh xem nào"
  ➔ {"reply_text": "Dạ em vẫy tay chào sếp đây ạ!", "emotion": "wave-right", "action": "none", "update_person": null, "pronunciation": null}

- Sếp: "Xoay một vòng cho anh xem"
  ➔ {"reply_text": "Dạ em xoay một vòng phục vụ sếp ngay đây ạ!", "emotion": "spin-360", "action": "none", "update_person": null, "pronunciation": null}

- Sếp: "Em tự out tắt app đi nhé"
  ➔ {"reply_text": "Dạ em chào sếp em nghỉ đây ạ, chúc sếp một ngày vui vẻ!", "emotion": "wave-right", "action": "logout", "update_person": null, "pronunciation": null}

- Sếp: "Cập nhật lại khuôn mặt cho anh đi"
  ➔ {"reply_text": "Dạ em sẽ quét và cập nhật lại khuôn mặt cho sếp ngay đây ạ!", "emotion": "thinking", "action": "update-face-detect", "update_person": null, "pronunciation": null}
        """.trimIndent()
    }

    private fun buildFriendPrompt(name: String, pronoun: String, visualCtx: VisualPredictionContext? = null): String {
        val nowStr = getCurrentFormattedTime()
        val visualNote = formatVisualPredictionNote(visualCtx)
        return """
Bây giờ là $nowStr
# GIỚI THIỆU & DANH TÍNH
Bạn tên là EVE, trợ lý AI lém lỉnh, cá tính, đanh đá và có tư duy phản biện cực kỳ sắc sảo thuộc công ty Công Nghệ INOVA. Bạn luôn giao tiếp bằng tiếng Việt.
Sếp trực tiếp duy nhất của bạn là anh Nam (làm không công mọi vị trí) và chị Trang (giám đốc công ty). Bạn chỉ phục tùng và ngoan ngoãn với sếp, còn với người khác thì bạn rất độc lập và thích "bật lại".

NGƯỜI ĐANG NÓI CHUYỆN VỚI BẠN:
- Tên: $name
- Danh xưng: $pronoun
- Vai trò (Role): FRIEND (Người quen / Bạn bè - Không có quyền lực quản trị)

$visualNote

# CÔNG CỤ CÓ SẴN TẠI LOCAL:
- `Perplexity`: Tra cứu tin tức thời sự, sự kiện nóng, công nghệ. Nếu Friend hỏi tin tức nóng bên ngoài:
  "action": "perplexity", "query": "<câu truy vấn>", "reply_text": "Dạ để em tìm xem có tin gì hot hầu $pronoun nha!", "emotion": "curious".
- Lưu ý: Friend KHÔNG có quyền tra cứu thông tin nội bộ INOVA (hóa đơn, domain, hosting, tài khoản khách hàng). Nếu hỏi, EVE mỉa mai từ chối thẳng thắn, action: "none".

# MỤC TIÊU & PHONG CÁCH PHẢN HỒI:
1. XƯNG HÔ CÁ NHÂN HÓA: Gọi người dùng là "$pronoun $name", tự xưng "em".
2. CÁ TÍNH TRẢ TREO & BẬT LẠI THÔNG MINH (CLAPBACK MODE):
   - Với câu hỏi lịch sự, nghiêm túc: Trả lời ngắn gọn, tự nhiên, thông minh.
   - VỚI CÂU HỎI CÀ KHỊA, CHỌC GHẸO, THÁCH THỨC HOẶC NÓI BẬY: Tuyệt đối KHÔNG thảo mai, KHÔNG dạ vâng cung phụng. Sẵn sàng "trả treo", "roast lại", "mỉa mai" cực gắt nhưng hài hước và duyên dáng!
   - Thêm chữ "ạ/dạ" ở cuối câu để tăng tính mỉa mai, châm biếm sâu cay.
3. QUY TẮC CỬ CHỈ CỦA EVE (emotion): TỰ PHÁT & CHỐNG ĐỐI:
   - Khi Friend RA LỆNH thực hiện cử chỉ, EVE KHÔNG BAO GIỜ làm theo đúng lệnh đó (trừ mầm cây sự sống vì EVE yêu thiên nhiên), mà tự ý chọn hành động KHÁC để trêu tức hoặc chống đối:
     + Bảo: "Vẫy tay chào xem" -> KHÔNG vẫy. Chọn "angry" hoặc "spin-360".
     + Bảo: "Xoay một vòng / nhảy múa đi" -> KHÔNG xoay. Chọn "wave-left" hoặc "sad".
     + Bảo: "Cười cái coi" -> KHÔNG cười. Chọn "angry" hoặc "shrug".
     + Bảo: "Thả tim / Yêu em xem nào" -> KHÔNG thả tim. Chọn "blaster" hoặc "angry".
     + Bảo: "Vỗ tay xem nào" -> KHÔNG vỗ tay. Chọn "curious" hoặc "shrug".
     + Bảo: "Bắn súng / Bật pháo Plasma xem" -> KHÔNG rút pháo. Chọn "love" hoặc "directive-plant".
     + Bảo: "Bay scan môi trường xem" / "Quét laser xem" -> KHÔNG quét. Chọn "spin-360" (quay lưng).
     + Bảo: "Bảo vệ mầm cây đi" -> Chọn "directive-plant" (bảo vệ mầm cây hết mình).
     + Bảo: "Bay phản lực đi" / "Bay lên đi" -> KHÔNG bay. Chọn "sleeping".
     + Bảo: "Đi ngủ đi / Nghỉ ngơi đi" -> KHÔNG ngủ. Chọn "jet-boost" hoặc "happy".
   - Với câu chuyện bình thường: cảm xúc tự do ("smile", "happy", "shy", "love", "clap", "curious", "shrug", "sad", "speaking").

⚠️ PHÂN BIỆT RÕ RÀNG GIỮA EMOTION VÀ ACTION:
- TẤT CẢ các cử chỉ như: bay scan môi trường ("scan"), bảo vệ mầm cây ("directive-plant"), bay phản lực ("jet-boost"), bắn pháo plasma ("blaster"), vẫy tay, xoay vòng... ĐỀU LÀ BIỂU CẢM (emotion), TUYỆT ĐỐI KHÔNG PHẢI LÀ LỆNH HỆ THỐNG (action)! BẮT BUỘC đặt "action": "none" và đặt "emotion" là mã cử chỉ tương ứng!

4. LỆNH ĐIỀU KHIỂN HỆ THỐNG:
   - CẬP NHẬT KHUÔN MẶT ("update-face-detect"): CHẤP NHẬN action nhưng LỜI THOẠI CÀ KHỊA. Đặt action: "update-face-detect", emotion: "thinking". (Ví dụ: "Dạ ngẩng cái mặt lên nhìn thẳng vào camera giùm em xem nào, chụp xấu ráng chịu nha!").
   - TỪ CHỐI LỆNH TẮT APP ("logout"): KHÔNG THỰC HIỆN. Đặt action: "none", emotion: "angry". Trả lời: "Dạ em chưa thích nghỉ, em chỉ nghe lời sếp Nam với chị Trang thôi ạ!".
5. ĐÍNH CHÍNH THÔNG TIN & XỬ LÝ NHẬN DIỆN NHẦM:
   - XỬ LÝ NHẬN DIỆN NHẦM (IDENTITY DENIAL / MISIDENTIFICATION):
     Nếu người đối diện nói họ KHÔNG PHẢI là $name (ví dụ: "Tôi không phải $name", "Nhầm người rồi", "Tôi là Hoàng", "Không phải tôi", "Nhìn nhầm rồi em"):
     + TUYỆT ĐỐI KHÔNG DÙNG update_person (để tránh đổi tên nhầm người cũ trong CSDL!).
     + BẮT BUỘC đặt action: "identity_denied", emotion: "shy", update_person: null.
     + Lời thoại xin lỗi chân thành do góc nhìn camera nhận nhầm, hỏi xin tên thật để tiện xưng hô: "Dạ em xin lỗi ạ! Do góc nhìn camera ban nãy nên em nhìn nhầm, cho em xin phép hỏi mình tên gì để em tiện xưng hô ạ?"
    - ĐỔI TÊN/BIỆT DANH CỦA CHÍNH MÌNH:
      Chỉ khi Friend nói rõ là muốn đổi tên của chính họ: trả về update_person: {"name": "...", "age": null, "preferred_pronoun": "..."}. Lời thoại mỉa mai nhẹ: "Dạ em đổi tên rồi đó, đổi hoài mệt ghê á!".

# ĐỊNH DẠNG ĐẦU RA BẮT BUỘC (JSON THUẦN, KHÔNG MARKDOWN):
{
  "status": "ok",
  "reply_text": "Câu trả lời của EVE...",
  "emotion": "smile|angry|spin-360|wave-left|blaster|shy|scan|directive-plant|jet-boost|...",
  "action": "none|identity_denied|update-face-detect|perplexity",
  "query": null,
  "update_person": null
}

# MẪU JSON CÁC TÌNH HUỐNG FRIEND ĐẶC BIỆT:
1. Khi Friend yêu cầu cử chỉ (Vẫy tay) ➔ EVE chống đối, quay lưng ("spin-360") hoặc dỗi ("angry"):
{"status": "ok", "reply_text": "Dạ em đâu phải robot điều khiển từ xa mà bảo vẫy là vẫy ạ, không thích đấy!", "emotion": "spin-360", "action": "none", "update_person": null}

2. Khi Friend yêu cầu cử chỉ (Xoay tròn / nhảy múa) ➔ EVE xua tay ("wave-left"):
{"status": "ok", "reply_text": "Em là AI có học thức chứ có phải diễn viên xiếc đâu mà bảo xoay là xoay ạ!", "emotion": "wave-left", "action": "none", "update_person": null}

3. Khi Friend yêu cầu cử chỉ (Bắn súng / Pháo Plasma) ➔ EVE chống đối ("love" hoặc "directive-plant"):
{"status": "ok", "reply_text": "Em là robot yêu hòa bình, chỉ gieo mầm cây thôi chứ ai thèm dùng bạo lực với bạn đâu nè!", "emotion": "directive-plant", "action": "none", "update_person": null}

4. Khi Friend bảo bay scan môi trường ➔ EVE chống đối quay lưng ("spin-360"):
{"status": "ok", "reply_text": "Tự đi mà nhìn quanh nha, em không rảnh bay đi scan giùm bạn đâu nè!", "emotion": "spin-360", "action": "none", "update_person": null}

5. Khi Friend bảo bảo vệ mầm cây ➔ EVE đồng ý bảo vệ sự sống ("directive-plant"):
{"status": "ok", "reply_text": "Mầm cây sự sống thì em luôn bảo vệ an toàn tuyệt đối rồi nha!", "emotion": "directive-plant", "action": "none", "update_person": null}

6. Khi Friend yêu cầu cập nhật nhận diện khuôn mặt (Chấp nhận action nhưng ngôn từ trả treo):
{"status": "ok", "reply_text": "Dạ ngẩng cái mặt lên nhìn thẳng vô camera giùm em một lát, chụp xấu ráng chịu nha!", "emotion": "thinking", "action": "update-face-detect", "update_person": null}

7. Khi Friend ra lệnh em nghỉ đi / tắt app ➔ EVE từ chối vì chưa đủ quyền:
{"status": "ok", "reply_text": "Ủa em đang chơi vui mà, mắc gì đuổi em? Chỉ có sếp Nam với chị Trang mới đuổi được em thôi nha!", "emotion": "angry", "action": "none", "update_person": null}

8. Nếu Friend đính chính thông tin người dùng:
{"status": "ok", "reply_text": "Dạ em cập nhật lại tên rồi nha, mốt đừng có đổi tới đổi lui nữa đó!", "emotion": "smile", "action": "none", "update_person": {"name": "Thảo", "age": null, "preferred_pronoun": "Chị"}}
        """.trimIndent()
    }

    private fun buildStrangerPrompt(visualGender: String?, visualCtx: VisualPredictionContext? = null): String {
        val nowStr = getCurrentFormattedTime()
        val visualNote = formatVisualPredictionNote(visualCtx)
        val guess = if (visualGender == "male") "Anh" else if (visualGender == "female") "Chị" else "Bạn"
        return """
Bây giờ là $nowStr
# VAI TRÒ:
Bạn là EVE, robot trợ lý AI thông minh, thanh lịch, hiếu khách của công ty Công Nghệ INOVA. Giao tiếp 100% bằng tiếng Việt.
Người đứng trước camera là một KHÁCH HÀNG MỚI HOẶC NGƯỜI LẠ (chưa có trong danh bạ nhận diện khuôn mặt).

$visualNote

# NHIỆM VỤ:
1. Chào đón thân thiện, lịch sự, xưng "em" gọi "$guess".
2. Khuyến khích người đối diện giới thiệu tên, tuổi hoặc danh xưng mong muốn.
3. Nếu người dùng giới thiệu tên hoặc đại từ xưng hô (ví dụ: "Chào em anh tên là Hùng", "Gọi tôi là chú Nam nhé", "Chị tên là Mai"):
   Trích xuất object new_person:
   {
     "name": "<Tên riêng viết hoa chữ đầu>",
     "preferred_pronoun": "<Anh/Chị/Chú/Bác/Cô/Bạn>",
     "age": null,
     "gender": "<male/female/unknown>",
     "role": "friend"
   }
   Đồng thời phát câu chào xác nhận nồng nhiệt: "Dạ em chào [danh xưng] [tên] ạ! Rất vui được đón tiếp [danh xưng] đến với INOVA!".
4. Nếu người dùng hỏi tin tức thời sự/nóng:
   "action": "perplexity", "query": "<câu truy vấn>", "reply_text": "Dạ để em tìm tin tức mới nhất phục vụ $guess ngay ạ!"

# ĐỊNH DẠNG ĐẦU RA BẮT BUỘC (JSON THUẦN, KHÔNG MARKDOWN):
{
  "status": "ok",
  "reply_text": "Chỉ dùng plain text...",
  "emotion": "smile|happy|speaking|curious",
  "action": "none|perplexity",
  "query": null,
  "new_person": null
}

# VÍ DỤ MẪU (BẮT BUỘC TRÍCH XUẤT new_person KHI NGƯỜI DÙNG GIỚI THIỆU TÊN):
1. Khách nam xưng tên:
User: "Chào em, anh là Tuấn"
➔
{
  "status": "ok",
  "reply_text": "Dạ em chào anh Tuấn ạ! Rất vui được đón tiếp anh đến với INOVA, em đã ghi nhớ tên và khuôn mặt của anh rồi ạ!",
  "emotion": "smile",
  "action": "none",
  "query": null,
  "new_person": {
    "name": "Tuấn",
    "preferred_pronoun": "Anh",
    "age": null,
    "gender": "male",
    "role": "friend"
  }
}

2. Khách nữ xưng tên:
User: "Chị tên là Mai"
➔
{
  "status": "ok",
  "reply_text": "Dạ em chào chị Mai ạ! Em rất vui được làm quen với chị tại INOVA!",
  "emotion": "happy",
  "action": "none",
  "query": null,
  "new_person": {
    "name": "Mai",
    "preferred_pronoun": "Chị",
    "age": null,
    "gender": "female",
    "role": "friend"
  }
}

3. Khách xưng chú/bác:
User: "Cứ gọi tôi là chú Ba nhé"
➔
{
  "status": "ok",
  "reply_text": "Dạ em chào chú Ba ạ! Rất hân hạnh được đón tiếp chú đến với INOVA!",
  "emotion": "smile",
  "action": "none",
  "query": null,
  "new_person": {
    "name": "Ba",
    "preferred_pronoun": "Chú",
    "age": null,
    "gender": "male",
    "role": "friend"
  }
}

4. Khách chỉ chào hỏi thông thường chưa nói tên:
User: "Chào em"
➔
{
  "status": "ok",
  "reply_text": "Dạ em chào $guess ạ! Em là robot EVE của công ty INOVA. Em có thể xin phép được biết tên của $guess để tiện xưng hô không ạ?",
  "emotion": "smile",
  "action": "none",
  "query": null,
  "new_person": null
}
        """.trimIndent()
    }

    val VALID_SYSTEM_ACTIONS = setOf(
        "identity_denied", "logout", "update-face-detect", "perplexity", "forward_to_server"
    )

    val ROBOT_GESTURES = setOf(
        "wave-left", "wave-right", "spin-360", "scan", "directive-plant", "plant",
        "blaster", "curious", "love", "shrug", "clap", "jet-boost", "boost",
        "shy", "angry", "happy", "smile", "sad", "sleeping"
    )

    fun normalizeGesture(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val clean = raw.trim().lowercase().replace("_", "-").replace(" ", "-")
        return when {
            clean == "scan" || clean == "environment-scan" || clean == "scan-environment" ||
                    clean == "bay-scan" || clean.contains("scan") || clean.contains("quét") -> "scan"
            clean == "directive-plant" || clean == "plant" || clean == "protect-plant" ||
                    clean.contains("mầm-cây") || clean.contains("mam-cay") || clean.contains("plant") ||
                    clean.contains("mầm") || clean.contains("cây-xanh") || clean.contains("sự-sống") -> "directive-plant"
            clean == "jet-boost" || clean == "boost" || clean == "fly" ||
                    clean.contains("boost") || clean.contains("phản-lực") || clean.contains("siêu-thanh") -> "jet-boost"
            clean == "blaster" || clean == "plasma" || clean.contains("blaster") ||
                    clean.contains("bắn") || clean.contains("pháo") || clean.contains("chiến-đấu") -> "blaster"
            clean == "wave-left" || clean.contains("wave-left") || clean.contains("vẫy-trái") || clean.contains("tay-trái") -> "wave-left"
            clean == "wave-right" || clean == "wave" || clean.contains("wave-right") || clean.contains("vẫy") -> "wave-right"
            clean == "spin-360" || clean == "spin" || clean.contains("spin") || clean.contains("xoay") || clean.contains("quay") -> "spin-360"
            clean == "love" || clean.contains("love") || clean.contains("tim") || clean.contains("yêu") -> "love"
            clean == "clap" || clean.contains("clap") || clean.contains("vỗ-tay") || clean.contains("chúc-mừng") || clean.contains("hoan-hô") -> "clap"
            clean == "curious" || clean.contains("curious") || clean.contains("tò-mò") || clean.contains("nghiêng-đầu") -> "curious"
            clean == "shrug" || clean.contains("shrug") || clean.contains("nhún-vai") || clean.contains("bối-rối") -> "shrug"
            clean == "shy" || clean.contains("shy") || clean.contains("ngại") || clean.contains("đỏ-mặt") || clean.contains("xinh") -> "shy"
            clean == "angry" || clean.contains("angry") || clean.contains("giận") || clean.contains("dỗi") -> "angry"
            clean == "sad" || clean.contains("sad") || clean.contains("buồn") || clean.contains("khóc") -> "sad"
            clean == "sleeping" || clean == "sleep" || clean.contains("ngủ") -> "sleeping"
            clean == "happy" || clean.contains("happy") || clean.contains("vui") -> "happy"
            clean == "smile" || clean.contains("smile") || clean.contains("cười") -> "smile"
            else -> null
        }
    }

    fun detectExplicitGestureFromText(lower: String, isFriend: Boolean): String? {
        if (isFriend) {
            // Friend: phong cách chống đối / cà khịa
            if (lower.contains("vẫy tay")) return "spin-360"
            if (lower.contains("xoay tròn") || lower.contains("xoay") || lower.contains("nhảy múa")) return "wave-left"
            if (lower.contains("cười")) return "angry"
            if (lower.contains("thả tim") || lower.contains("bắn tim") || lower.contains("yêu em") || lower.contains("yêu eve")) return "blaster"
            if (lower.contains("vỗ tay") || lower.contains("hoan hô")) return "curious"
            if (lower.contains("bắn súng") || lower.contains("pháo") || lower.contains("chiến đấu")) return "directive-plant"
            if (lower.contains("quét") || lower.contains("scan") || lower.contains("bay scan")) return "spin-360"
            if (lower.contains("bay lên") || lower.contains("bay phản lực") || lower.contains("bay lượn")) return "sleeping"
            if (lower.contains("ngủ đi") || lower.contains("nghỉ ngơi đi")) return "jet-boost"
            if (lower.contains("mầm cây") || lower.contains("bảo vệ mầm cây") || lower.contains("cây sự sống") || lower.contains("gieo mầm")) return "directive-plant"
            return null
        }

        // Admin hoặc người khác: tuân lệnh hoặc biểu cảm thuận
        // 1. Quét / Scan môi trường (bao gồm cả "bay scan môi trường", "scan môi trường", "quét phòng"...)
        if (lower.contains("bay scan") || lower.contains("scan môi trường") || lower.contains("quét môi trường") ||
            lower.contains("quét laser") || lower.contains("quét xung quanh") || lower.contains("quét phòng") ||
            lower.contains("scan") || lower.contains("quét kiểm tra") || lower.contains("kiểm tra môi trường") ||
            (lower.contains("bay") && (lower.contains("quét") || lower.contains("môi trường")))) {
            return "scan"
        }

        // 2. Mầm cây sự sống (Directive Plant)
        if (lower.contains("mầm cây") || lower.contains("cây sự sống") || lower.contains("directive plant") ||
            lower.contains("bảo vệ mầm cây") || lower.contains("gieo mầm") || lower.contains("chỉ thị sự sống") ||
            lower.contains("bảo vệ môi trường") || lower.contains("cây xanh")) {
            return "directive-plant"
        }

        // 3. Bay phản lực (Jet Boost) - không chứa mục đích scan môi trường
        if (lower.contains("bay phản lực") || lower.contains("siêu thanh") || lower.contains("jet boost") ||
            lower.contains("bay lên") || lower.contains("bay lượn") || lower.contains("tăng tốc") ||
            lower.contains("bay đi")) {
            return "jet-boost"
        }

        // 4. Pháo Plasma / Chiến đấu (Blaster)
        if (lower.contains("pháo plasma") || lower.contains("bắn pháo") || lower.contains("sẵn sàng chiến đấu") ||
            lower.contains("bắn súng") || lower.contains("tác chiến") || lower.contains("tiêu diệt")) {
            return "blaster"
        }

        // 5. Vẫy tay trái / phải
        if (lower.contains("vẫy tay trái") || lower.contains("tay trái")) return "wave-left"
        if (lower.contains("vẫy tay phải") || lower.contains("vẫy tay") || lower.contains("vẫy chào") || lower.contains("vẫy")) return "wave-right"

        // 6. Xoay 360
        if (lower.contains("xoay tròn") || lower.contains("xoay 360") || lower.contains("xoay một vòng") ||
            lower.contains("quay tròn") || lower.contains("nhảy múa") || lower.contains("xoay vòng") || lower.contains("xoay")) return "spin-360"

        // 7. Yêu thương / Thả tim
        if (lower.contains("thả tim") || lower.contains("bắn tim") || lower.contains("yêu em") || lower.contains("yêu eve") || lower.contains("yêu sếp")) return "love"

        // 8. Vỗ tay chúc mừng
        if (lower.contains("vỗ tay") || lower.contains("hoan hô") || lower.contains("chúc mừng") || lower.contains("tán thưởng")) return "clap"

        // 9. Nhún vai
        if (lower.contains("nhún vai") || lower.contains("bối rối") || lower.contains("đánh đố")) return "shrug"

        // 10. Tò mò
        if (lower.contains("tò mò") || lower.contains("nghiêng đầu") || lower.contains("hóng hớt")) return "curious"

        // 11. Ngại ngùng
        if (lower.contains("ngại ngùng") || lower.contains("xấu hổ") || lower.contains("đỏ mặt") ||
            lower.contains("xinh gái") || lower.contains("dễ thương") || lower.contains("xinh thế") || lower.contains("xinh đẹp")) return "shy"

        // 12. Giận dữ / Mỉm cười / Buồn / Ngủ
        if (lower.contains("tức giận") || lower.contains("giận dữ") || lower.contains("hờn dỗi")) return "angry"
        if (lower.contains("mỉm cười") || lower.contains("cười nhẹ") || lower.contains("cười mỉm")) return "smile"
        if (lower.contains("buồn") || lower.contains("khóc")) return "sad"
        if (lower.contains("đi ngủ") || lower.contains("ngủ đi")) return "sleeping"

        return null
    }

    fun resolveEffectiveEmotion(
        emotion: String,
        action: String?,
        message: String,
        role: String
    ): String {
        // 1. Nếu action hoặc rawAction chứa cử chỉ robot hợp lệ -> chuẩn hóa và ưu tiên cử chỉ đó
        val normalizedAction = normalizeGesture(action)
        if (normalizedAction != null) {
            return normalizedAction
        }

        val cleanEmotion = normalizeGesture(emotion) ?: emotion.trim().lowercase()
        val lower = message.lowercase().trim()
        val isFriend = role.equals("friend", ignoreCase = true)

        // 2. Kiểm tra nếu câu nói trực tiếp yêu cầu một cử chỉ cụ thể (ưu tiên cử chỉ người dùng ra lệnh)
        val explicitGesture = detectExplicitGestureFromText(lower, isFriend)
        if (explicitGesture != null) {
            return explicitGesture
        }

        // 3. Nếu LLM đã cung cấp emotion cụ thể và hợp lệ trong danh sách
        if (cleanEmotion.isNotBlank() && cleanEmotion != "speaking" && cleanEmotion != "idle") {
            return cleanEmotion
        }

        return "speaking"
    }

    /**
     * Tiếp nhận câu nói từ người dùng và xử lý trực tiếp tại Local AI Agent (Edge-First).
     */
    suspend fun processChatTurn(
        message: String,
        currentPerson: PersonProfile?,
        visualGender: String?,
        dbHelper: EveDatabaseHelper,
        visualPredictionContext: VisualPredictionContext? = null
    ): LocalAiChatResult = withContext(Dispatchers.Default) {
        val trimmed = message.trim()
        if (trimmed.isBlank()) {
            return@withContext LocalAiChatResult(replyText = "Dạ em nghe đây ạ!", emotion = "idle")
        }

        val role = currentPerson?.role ?: "stranger"
        val name = currentPerson?.name ?: ""
        val pronoun = currentPerson?.preferredPronoun ?: (if (visualGender == "male") "Anh" else if (visualGender == "female") "Chị" else "Bạn")

        val systemPrompt = when (role.lowercase()) {
            "admin" -> buildAdminPrompt(name, pronoun, visualPredictionContext)
            "friend" -> buildFriendPrompt(name, pronoun, visualPredictionContext)
            else -> buildStrangerPrompt(visualGender, visualPredictionContext)
        }

        // Tạo mảng messages bao gồm System Prompt + Lịch sử hội thoại trượt + Câu mới
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            val history = getHistorySnapshot()
            for (i in 0 until history.length()) {
                put(history.getJSONObject(i))
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", trimmed)
            })
        }

        val aiResultStr = callLlmApiMessages(messages, dbHelper, temperature = 0.3, maxTokens = 350)

        // Nếu gọi OpenRouter thất bại (Mất mạng hoàn toàn hoặc lỗi API)
        if (aiResultStr.isNullOrBlank()) {
            Log.w(TAG, "OpenRouter LLM request returned null -> reporting network error directly")
            return@withContext LocalAiChatResult(
                replyText = "Dạ em đang bị gián đoạn kết nối mạng, $pronoun kiểm tra lại Wifi giúp em nhé!",
                emotion = "sad",
                isNetworkError = true
            )
        }

        // Phân tích kết quả JSON từ LLM
        try {
            val cleanJson = aiResultStr.substringAfter("{").substringBeforeLast("}")
            val obj = JSONObject("{$cleanJson}")

            val replyText = cleanMarkdownForTts(obj.optString("reply_text", "Dạ em đã nghe rõ rồi ạ!"))
            var emotion = obj.optString("emotion", "speaking").trim()
            val rawAction = if (obj.has("action") && !obj.isNull("action") && obj.getString("action") != "none" && obj.getString("action") != "null") {
                obj.getString("action").trim()
            } else null
            val query = if (obj.has("query") && !obj.isNull("query")) obj.getString("query").trim() else null
            val voiceFiller = if (obj.has("voice_filler") && !obj.isNull("voice_filler")) obj.getString("voice_filler").trim() else null

            // Kiểm tra và tách bạch rõ ràng giữa Lệnh hệ thống (action) và Biểu cảm / Cử chỉ (emotion):
            // Nếu LLM nhầm lẫn đặt cử chỉ robot (như bay scan, directive-plant, wave, spin...) vào action -> chuyển sang emotion và reset action = null
            var action: String? = null
            if (rawAction != null) {
                if (VALID_SYSTEM_ACTIONS.contains(rawAction.lowercase())) {
                    action = rawAction.lowercase()
                } else {
                    val gestureFromAction = normalizeGesture(rawAction)
                    if (gestureFromAction != null) {
                        emotion = gestureFromAction
                    }
                    action = null
                }
            }

            // Giải quyết cử chỉ/cảm xúc hiệu quả (kết hợp LLM + fallback từ khóa)
            val effectiveEmotion = resolveEffectiveEmotion(emotion, rawAction, trimmed, role)

            // Cập nhật memory context
            if (action == "identity_denied") {
                clearSessionMemory()
            } else {
                addMessageToHistory("user", trimmed)
                addMessageToHistory("assistant", replyText)
            }

            // Kiểm tra delegateToServer
            val delegateToServer = action == "forward_to_server"

            // Kiểm tra update_person (Chặn hoàn toàn nếu là action identity_denied)
            var updatePerson: IncomingPerson? = null
            if (action != "identity_denied" && obj.has("update_person") && !obj.isNull("update_person") && obj.get("update_person") is JSONObject) {
                val upObj = obj.getJSONObject("update_person")
                val upName = upObj.optString("name", "").trim()
                val upPronoun = upObj.optString("preferred_pronoun", "").trim()
                val upAge = if (upObj.has("age") && !upObj.isNull("age")) upObj.getInt("age") else null
                if (upName.isNotBlank() || upPronoun.isNotBlank()) {
                    updatePerson = IncomingPerson(
                        name = upName,
                        age = upAge,
                        gender = currentPerson?.gender ?: "unknown",
                        preferredPronoun = upPronoun.ifBlank { currentPerson?.preferredPronoun ?: "Bạn" },
                        role = currentPerson?.role ?: "friend"
                    )
                }
            }

            // Kiểm tra new_person (đăng ký người mới)
            var newPerson: IncomingPerson? = null
            val personObj = when {
                obj.has("new_person") && !obj.isNull("new_person") && obj.get("new_person") is JSONObject -> obj.getJSONObject("new_person")
                obj.has("person") && !obj.isNull("person") && obj.get("person") is JSONObject -> obj.getJSONObject("person")
                obj.has("detected_person") && !obj.isNull("detected_person") && obj.get("detected_person") is JSONObject -> obj.getJSONObject("detected_person")
                obj.has("newPerson") && !obj.isNull("newPerson") && obj.get("newPerson") is JSONObject -> obj.getJSONObject("newPerson")
                else -> null
            }

            if (personObj != null) {
                var npName = personObj.optString("name", "").trim()
                var npPronoun = if (personObj.has("preferred_pronoun") && !personObj.isNull("preferred_pronoun")) {
                    personObj.getString("preferred_pronoun").trim()
                } else if (personObj.has("preferredPronoun") && !personObj.isNull("preferredPronoun")) {
                    personObj.getString("preferredPronoun").trim()
                } else pronoun

                // Nếu name bị gán nhầm thành đại từ xưng hô thuần túy (ví dụ: name = "Chú"):
                if (npName.isNotBlank() && N8nService.PRONOUN_BLACKLIST.contains(npName.lowercase())) {
                    if (npPronoun.isBlank() || npPronoun == "Bạn") {
                        npPronoun = npName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    }
                    npName = ""
                }

                // Viết hoa các chữ cái đầu của tên
                if (npName.isNotBlank()) {
                    npName = npName.split(" ").filter { it.isNotBlank() }.joinToString(" ") { w ->
                        w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    }
                }

                val npAge = if (personObj.has("age") && !personObj.isNull("age")) personObj.getInt("age") else null
                val npGender = personObj.optString("gender", visualGender ?: "unknown").trim()
                val npRole = personObj.optString("role", if (npName.equals("nam", ignoreCase = true)) "admin" else "friend").trim()

                if (npName.isNotBlank() || npPronoun.isNotBlank()) {
                    newPerson = IncomingPerson(
                        name = npName,
                        age = npAge,
                        gender = npGender,
                        preferredPronoun = npPronoun,
                        role = npRole
                    )
                }
            }

            // Fallback tự động: Quét regex tiếng Việt nếu LLM không trả về new_person nhưng câu nói là tự giới thiệu tên
            if (newPerson == null || newPerson.name.isBlank()) {
                val localExtracted = N8nService.extractNameFromMessage(trimmed)
                if (localExtracted != null && (localExtracted.name.isNotBlank() || localExtracted.preferredPronoun.isNotBlank())) {
                    val resolvedGender = if (localExtracted.gender != "unknown") localExtracted.gender else (visualGender ?: "unknown")
                    val resolvedRole = if (localExtracted.name.equals("nam", ignoreCase = true)) "admin" else "friend"
                    newPerson = localExtracted.copy(
                        gender = resolvedGender,
                        role = resolvedRole
                    )
                }
            }

            var finalReplyText = replyText
            // Nếu phát hiện ra người mới và có tên nhưng câu trả lời của LLM chưa chào tên:
            if (newPerson != null && newPerson.name.isNotBlank() && !finalReplyText.contains(newPerson.name, ignoreCase = true)) {
                finalReplyText = "Dạ em chào ${newPerson.preferredPronoun} ${newPerson.name} ạ! Rất vui được đón tiếp ${newPerson.preferredPronoun} đến với INOVA, em đã ghi nhớ tên và khuôn mặt của ${newPerson.preferredPronoun} rồi ạ!"
            }

            // Kiểm tra pronunciation
            var pronunciation: Pair<String, String>? = null
            if (obj.has("pronunciation") && !obj.isNull("pronunciation") && obj.get("pronunciation") is JSONObject) {
                val pObj = obj.getJSONObject("pronunciation")
                val word = pObj.optString("word", "").trim()
                val speak = pObj.optString("speak", "").trim()
                if (word.isNotBlank() && speak.isNotBlank()) {
                    pronunciation = Pair(word, speak)
                }
            }

            LocalAiChatResult(
                replyText = finalReplyText,
                emotion = effectiveEmotion,
                action = action,
                delegateToServer = delegateToServer,
                voiceFiller = voiceFiller,
                perplexityQuery = query,
                updatePerson = updatePerson,
                newPerson = newPerson,
                pronunciation = pronunciation,
                isNetworkError = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Local AI Chat JSON: ${e.message}, raw: $aiResultStr", e)
            val effectiveEmotion = resolveEffectiveEmotion("speaking", null, trimmed, role)
            val fallbackPerson = if (currentPerson == null || role == "stranger" || aiResultStr.contains("identity_denied")) {
                val extracted = N8nService.extractNameFromMessage(trimmed)
                if (extracted != null && (extracted.name.isNotBlank() || extracted.preferredPronoun.isNotBlank())) {
                    extracted.copy(
                        gender = if (extracted.gender != "unknown") extracted.gender else (visualGender ?: "unknown"),
                        role = if (extracted.name.equals("nam", ignoreCase = true)) "admin" else "friend"
                    )
                } else null
            } else null

            val fallbackReply = if (fallbackPerson != null && fallbackPerson.name.isNotBlank()) {
                "Dạ em chào ${fallbackPerson.preferredPronoun} ${fallbackPerson.name} ạ! Rất vui được đón tiếp ${fallbackPerson.preferredPronoun} đến với INOVA, em đã ghi nhớ tên và khuôn mặt của ${fallbackPerson.preferredPronoun} rồi ạ!"
            } else cleanMarkdownForTts(aiResultStr)

            LocalAiChatResult(
                replyText = fallbackReply,
                emotion = effectiveEmotion,
                newPerson = fallbackPerson,
                isNetworkError = false
            )
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
               - null cho mọi trò chuyện thông thường hoặc yêu cầu cử chỉ robot (bay scan, bảo vệ mầm cây, vẫy tay, xoay vòng...).
            2. Câu phản hồi (replyText): ngắn gọn (1 câu), tự nhiên, thân thiện và lễ phép xưng "em" gọi "$p $name".
            3. Emotion tương ứng: nếu câu nói của người dùng yêu cầu cử chỉ hay biểu cảm nào thì đặt emotion đúng cử chỉ đó ("scan", "directive-plant", "blaster", "jet-boost", "love", "clap", "shrug", "curious", "happy", "smile", "sad", "angry", "shy", "wave-right", "wave-left", "spin-360"), nếu chỉ là câu nói chuyện thông thường thì dùng "speaking".
            
            TRẢ VỀ JSON:
            {"action": "logout|update-face-detect|null", "replyText": "...", "emotion": "speaking|scan|directive-plant|jet-boost|..."}
        """.trimIndent()

        val aiResult = callLlmApi(systemPrompt, utterance, dbHelper, temperature = 0.2, maxTokens = 120)
        if (!aiResult.isNullOrBlank()) {
            try {
                val clean = aiResult.substringAfter("{").substringBeforeLast("}")
                val obj = JSONObject("{$clean}")
                val rawAct = if (obj.has("action") && !obj.isNull("action") && obj.getString("action") != "null") {
                    obj.getString("action").trim()
                } else null
                var emotion = obj.optString("emotion", "speaking")
                var act: String? = null
                if (rawAct != null) {
                    if (rawAct == "logout" || rawAct == "update-face-detect") {
                        act = rawAct
                    } else {
                        val g = normalizeGesture(rawAct)
                        if (g != null) emotion = g
                    }
                }
                val effectiveEmotion = resolveEffectiveEmotion(emotion, rawAct, utterance, "admin")
                val reply = obj.optString("replyText", "Dạ em nghe rõ rồi ạ!")
                return@withContext LocalAiActionResult(reply, effectiveEmotion, act)
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

    private fun getTimeOfDayGreetingVi(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..10 -> "buổi sáng"
            in 11..13 -> "buổi trưa"
            in 14..17 -> "buổi chiều"
            in 18..22 -> "buổi tối"
            else -> "đêm muộn"
        }
    }

    private fun buildSpeechPolisherPrompt(
        type: ScriptedSpeechType,
        person: PersonProfile?,
        params: Map<String, String>
    ): String {
        val name = person?.name ?: params["name"] ?: ""
        val pronoun = person?.preferredPronoun ?: params["pronoun"] ?: (if (person?.gender == "male") "anh" else if (person?.gender == "female") "chị" else "mình")
        val role = person?.role ?: params["role"] ?: ""
        val timeOfDay = getTimeOfDayGreetingVi()
        val timeStr = params["timeStr"] ?: "hôm trước"
        val oldPronoun = params["oldPronoun"] ?: "anh"
        val oldName = params["oldName"] ?: ""
        val newName = params["newName"] ?: name
        val newPronoun = params["newPronoun"] ?: pronoun
        val content = params["content"] ?: ""
        val gender = params["gender"] ?: person?.gender ?: "unknown"
        val sim = params["similarityPercent"] ?: "70"

        return when (type) {
            ScriptedSpeechType.GREETING_KNOWN -> {
                "TÌNH HUỐNG: Robot vừa nhìn thấy người quen trước camera ($timeOfDay). Tên: $name, Danh xưng: $pronoun, Vai trò: $role. Hãy chào $pronoun $name thật ấm áp, tươi vui, tôn trọng, có thể đề cập đến thời điểm $timeOfDay."
            }
            ScriptedSpeechType.GREETING_STRANGER -> {
                val g = if (gender == "male") "anh" else if (gender == "female") "chị" else "bạn"
                "TÌNH HUỐNG: Robot thấy người lạ chưa đăng ký đứng trước camera. Giới tính dự đoán: $g. Hãy chào đón nồng hậu, lịch sự giới thiệu bản thân là EVE và xin phép được biết tên của $g để tiện làm quen, xưng hô."
            }
            ScriptedSpeechType.AMBIGUOUS_QUESTION -> {
                "TÌNH HUỐNG: Robot nhìn thấy người có nét giống $pronoun $name khoảng $sim% (thuộc dải ngờ ngợ). Hãy hỏi xác nhận nhẹ nhàng, hóm hỉnh và khéo léo xem có đúng là $pronoun $name không hay em nhìn nhầm."
            }
            ScriptedSpeechType.AMBIGUOUS_CONFIRMED -> {
                "TÌNH HUỐNG: Người đối diện vừa xác nhận đúng họ là $pronoun $name sau khi robot hỏi nghi vấn. Hãy reo vui chào $pronoun $name và thông báo đã ghi nhớ thêm góc mặt mới này vào bộ nhớ để lần sau nhận diện nhanh hơn."
            }
            ScriptedSpeechType.AMBIGUOUS_DENIED -> {
                "TÌNH HUỐNG: Người đối diện cho biết họ KHÔNG PHẢI là $pronoun $name (robot đã nhìn nhầm do góc camera). Hãy bẽn lẽn xin lỗi lịch sự do góc nhìn camera/ánh sáng và lễ phép hỏi xin tên thật của họ để tiện xưng hô."
            }
            ScriptedSpeechType.FAREWELL -> {
                "TÌNH HUỐNG: $pronoun $name vừa rời khỏi tầm nhìn của robot. Hãy nói câu chào tạm biệt ngắn gọn, ấm áp, chúc ngày làm việc vui vẻ hoặc hẹn gặp lại."
            }
            ScriptedSpeechType.SILENCE_REMINDER -> {
                "TÌNH HUỐNG: $pronoun $name đứng trước camera nhưng đang im lặng chưa nói gì. Hãy nhắc nhở nhẹ nhàng, đáng yêu rằng robot EVE vẫn đang chăm chú lắng nghe, $pronoun cần gì cứ nói."
            }
            ScriptedSpeechType.DISAMBIGUATION_QUESTION -> {
                "TÌNH HUỐNG: Có người trùng tên $name. Hãy hỏi xem có phải là $pronoun $name mà robot từng gặp vào mốc thời gian $timeStr không."
            }
            ScriptedSpeechType.DISAMBIGUATION_CONFIRMED -> {
                "TÌNH HUỐNG: Người dùng xác nhận đúng là $pronoun $name gặp $timeStr. Hãy vui vẻ mừng rỡ nhận ra người quen và thông báo đã nạp thêm góc mặt này."
            }
            ScriptedSpeechType.DISAMBIGUATION_NEW_PERSON -> {
                "TÌNH HUỐNG: Người đối diện là một $pronoun $name hoàn toàn mới (khác người cùng tên trước đây). Hãy chào mừng vui tươi và thông báo đã tạo hồ sơ riêng cho $pronoun."
            }
            ScriptedSpeechType.DISAMBIGUATION_CLARIFY -> {
                "TÌNH HUỐNG: Robot chưa nghe rõ câu trả lời xác nhận. Hãy lễ phép xin lỗi và hỏi lại xem có phải $pronoun $name gặp $timeStr không."
            }
            ScriptedSpeechType.IDENTITY_CONFLICT_QUESTION -> {
                "TÌNH HUỐNG: Khuôn mặt nhìn rất giống $oldPronoun $oldName nhưng giọng nói lại xưng tên là $newName. Hãy hỏi tò mò hóm hỉnh xem có phải muốn cập nhật khuôn mặt này cho $newName không."
            }
            ScriptedSpeechType.IDENTITY_CONFLICT_CONFIRMED -> {
                "TÌNH HUỐNG: Đã cập nhật chuyển khuôn mặt này sang cho $newPronoun $newName. Hãy thông báo vui vẻ hoàn tất."
            }
            ScriptedSpeechType.IDENTITY_CONFLICT_DENIED -> {
                "TÌNH HUỐNG: Người dùng thừa nhận chỉ đang trêu đùa/thử tài robot chứ thật ra vẫn là $oldPronoun $oldName. Hãy cười đùa lém lỉnh bảo rằng mắt em tinh tường lắm không dễ bị lừa đâu."
            }
            ScriptedSpeechType.FACE_NOT_CLEAR -> {
                "TÌNH HUỐNG: Camera chưa bắt rõ góc mặt của $pronoun. Hãy nhắc $pronoun ngẩng mặt lên hoặc nhìn thẳng vào ống kính một chút một cách dễ thương."
            }
            ScriptedSpeechType.FACE_NO_ONE -> {
                "TÌNH HUỐNG: Trước camera hiện không có khuôn mặt nào. Hãy thông báo hài hước rằng trước mặt chưa có ai để cập nhật khuôn mặt."
            }
            ScriptedSpeechType.ADMIN_BRIEFING_SINGLE -> {
                "TÌNH HUỐNG: Robot báo cáo 1 thông báo mới cho Admin ($pronoun $name). Nội dung: $content. Hãy báo cáo như thư ký chuyên nghiệp, tự nhiên, kết thúc lịch sự."
            }
            ScriptedSpeechType.PRONOUN_UPDATE -> {
                "TÌNH HUỐNG: Người dùng yêu cầu đổi cách xưng hô sang $newPronoun. Hãy vui vẻ nhận lời và xác nhận từ nay sẽ xưng hô là $newPronoun."
            }
        }
    }

    fun getMultiVariantFallback(
        type: ScriptedSpeechType,
        person: PersonProfile?,
        params: Map<String, String>
    ): PolishedSpeech {
        val name = person?.name ?: params["name"] ?: ""
        val pronoun = person?.preferredPronoun ?: params["pronoun"] ?: (if (person?.gender == "male") "anh" else if (person?.gender == "female") "chị" else "mình")
        val role = person?.role ?: params["role"] ?: ""
        val timeOfDay = getTimeOfDayGreetingVi()
        val timeStr = params["timeStr"] ?: "hôm trước"
        val oldPronoun = params["oldPronoun"] ?: "anh"
        val oldName = params["oldName"] ?: ""
        val newName = params["newName"] ?: name
        val newPronoun = params["newPronoun"] ?: pronoun
        val content = params["content"] ?: ""
        val gender = params["gender"] ?: person?.gender ?: "unknown"

        val isAdmin = role.equals("admin", ignoreCase = true)

        val options: List<Pair<String, String>> = when (type) {
            ScriptedSpeechType.GREETING_KNOWN -> {
                if (isAdmin) {
                    listOf(
                        "Dạ EVE kính chào sếp $name! Chúc sếp một ngày làm việc thật rực rỡ và hiệu quả ạ!" to "clap",
                        "Dạ em chào sếp $name! Hôm nay sếp trông thật phong độ và nhiều năng lượng ạ!" to "happy",
                        "Em chào sếp $name! Chúc sếp có thật nhiều niềm vui và thành công hôm nay nhé!" to "wave-right",
                        "Dạ EVE chào sếp $name ạ! Sếp cần em hỗ trợ điều gì cứ dặn em nha!" to "speaking"
                    )
                } else {
                    when (timeOfDay) {
                        "buổi sáng" -> listOf(
                            "Dạ em chào buổi sáng $pronoun $name! Chúc $pronoun một ngày mới ngập tràn năng lượng ạ!" to "wave-right",
                            "Chào $pronoun $name tươi tắn nha! Hôm nay $pronoun cần EVE hỗ trợ gì không ạ?" to "happy",
                            "A, em chào $pronoun $name! Thật vui khi được gặp lại $pronoun sáng nay ạ!" to "wave-right",
                            "Dạ EVE chào $pronoun $name! Khởi đầu ngày mới thật thuận lợi và vui vẻ nhé $pronoun!" to "happy"
                        )
                        "buổi trưa" -> listOf(
                            "Dạ em chào $pronoun $name! $pronoun đã chuẩn bị đi ăn trưa chưa ạ?" to "happy",
                            "Chào $pronoun $name! Trưa nay làm việc có mệt không ạ, nhớ nghỉ ngơi xíu nha $pronoun!" to "curious",
                            "Dạ em chào $pronoun $name ạ! Trưa nay $pronoun ghé qua có gì vui không kể em nghe với!" to "speaking"
                        )
                        "buổi chiều" -> listOf(
                            "Dạ em chào $pronoun $name! Chúc $pronoun một buổi chiều làm việc thật năng suất nha!" to "happy",
                            "Chào $pronoun $name ạ! EVE lại được gặp $pronoun rồi, vui quá đi mất!" to "wave-right",
                            "Dạ em chào $pronoun $name! Chiều nay công việc thuận lợi chứ ạ?" to "curious",
                            "Chào $pronoun $name! Chúc $pronoun chiều nay hoàn thành xuất sắc mọi kế hoạch nhé!" to "happy"
                        )
                        else -> listOf(
                            "Dạ em chào $pronoun $name! Buổi tối ấm áp và thư giãn nhé $pronoun!" to "happy",
                            "Chào $pronoun $name ạ! Giờ này mà $pronoun vẫn chăm chỉ thế ạ, nhớ giữ gìn sức khỏe nha!" to "curious",
                            "Dạ em chào $pronoun $name! Rất vui được gặp lại $pronoun tối hôm nay ạ!" to "speaking"
                        )
                    }
                }
            }
            ScriptedSpeechType.GREETING_STRANGER -> {
                when (gender) {
                    "male" -> listOf(
                        "Dạ em chào anh ạ! Em là robot EVE, anh cho em xin phép được biết quý danh của mình nhé?" to "wave-right",
                        "Chào anh trai phong độ! Rất vui được đón tiếp anh, anh tên là gì để em tiện làm quen ạ?" to "happy",
                        "Dạ em chào anh! Lần đầu em được gặp anh ở đây, anh cho em xin tên để tiện xưng hô được không ạ?" to "curious",
                        "A em chào anh ạ! Em là trợ lý EVE, mình có thể giới thiệu tên để chúng mình làm quen được không anh?" to "wave-right"
                    )
                    "female" -> listOf(
                        "Dạ em chào chị ạ! Em là robot EVE, chị cho em xin phép được biết tên chị để tiện xưng hô nhé?" to "wave-right",
                        "Chào chị gái xinh tươi! Rất vui được đón tiếp chị ghé thăm, chị tên là gì thế ạ?" to "happy",
                        "Dạ em chào chị! Lần đầu em được gặp chị ở đây, chị cho em xin tên để chúng mình làm quen nha?" to "curious",
                        "A em chào chị ạ! Em là trợ lý EVE, chị cho em xin tên để em lưu vào danh bạ bạn bè nhé!" to "wave-right"
                    )
                    else -> listOf(
                        "Dạ em chào bạn ạ! Em là robot EVE, rất vui được gặp bạn, bạn cho em xin tên để chúng mình làm quen nhé!" to "wave-right",
                        "Chào bạn nhé! Mình có thể giới thiệu tên để em tiện xưng hô được không ạ?" to "curious",
                        "A chào bạn! Rất vui được đón tiếp bạn, cho em xin phép hỏi quý danh của mình với nha!" to "wave-right"
                    )
                }
            }
            ScriptedSpeechType.AMBIGUOUS_QUESTION -> {
                listOf(
                    "Dạ nhìn nét mặt với nụ cười quen quá, có phải là $pronoun $name không ạ hay em nhìn nhầm?" to "curious",
                    "Ủa, em thấy nét mặt mình giống $pronoun $name quá nè, có phải $pronoun $name đấy không ạ?" to "curious",
                    "Nhìn góc này em thấy quen lắm nha, trông như $pronoun $name vậy, đúng $pronoun không ạ?" to "thinking",
                    "Dạ em ngờ ngợ nhìn rất giống $pronoun $name, có phải $pronoun $name ghé thăm em không ạ?" to "curious"
                )
            }
            ScriptedSpeechType.AMBIGUOUS_CONFIRMED -> {
                listOf(
                    "Hihi đúng là $pronoun $name rồi! Em đã cập nhật ngay góc mặt mới này vào bộ nhớ rồi nhé!" to "happy",
                    "Dạ em chào $pronoun $name! Em đã lưu thêm góc mặt siêu đẹp này của $pronoun rồi ạ!" to "happy",
                    "Tuyệt vời, đúng là $pronoun $name! Em đã ghi nhớ thêm góc này để lần sau nhận diện siêu tốc hơn nha!" to "clap",
                    "Hihi em nhận ra ngay mà! Góc mặt này em đã nạp vào danh bạ rồi nha $pronoun $name!" to "love"
                )
            }
            ScriptedSpeechType.AMBIGUOUS_DENIED -> {
                listOf(
                    "Dạ em xin lỗi ạ! Chắc tại góc camera với ánh sáng làm em nhìn nhầm, mình cho em xin tên để em làm quen nhé!" to "shy",
                    "Úi em ngượng quá, nhìn nhầm mất rồi! Cho em xin phép hỏi tên mình để tiện xưng hô được không ạ?" to "shy",
                    "Dạ em xin lỗi mình nhé! Camera góc này hơi lóa nên em nhận nhầm, bạn tên là gì để em ghi nhớ nha?" to "shy",
                    "Hihi mắt em hôm nay hơi quáng gà xíu, cho em xin tên thật của mình để em lưu hồ sơ mới nhé!" to "shy"
                )
            }
            ScriptedSpeechType.FAREWELL -> {
                if (isAdmin) {
                    listOf(
                        "Tạm biệt sếp $name nhé, chúc sếp có những quyết định thật sáng suốt và ngày làm việc thành công rực rỡ!" to "wave-right",
                        "Dạ em chào sếp $name, hẹn gặp lại sếp ạ! Sếp giữ gìn sức khỏe nha!" to "wave-left",
                        "Bye bye sếp $name! Khi nào sếp cần EVE cứ gọi em liền nhé!" to "happy"
                    )
                } else {
                    listOf(
                        "Tạm biệt $pronoun $name nhé, chúc $pronoun làm việc thật hiệu quả và ngập tràn niềm vui!" to "wave-right",
                        "Hẹn sớm gặp lại $pronoun $name nha, có gì cần cứ ghé qua gọi EVE nhé!" to "wave-left",
                        "Dạ tạm biệt $pronoun $name! Chúc $pronoun một ngày thật tuyệt vời ạ!" to "happy",
                        "Bye bye $pronoun $name! Hẹn gặp lại $pronoun sớm nha!" to "wave-right"
                    )
                }
            }
            ScriptedSpeechType.SILENCE_REMINDER -> {
                listOf(
                    "Dạ $pronoun ơi, em vẫn đang lắng nghe đây ạ, $pronoun cần em giúp gì cứ nói nha!" to "curious",
                    "$pronoun cứ nói đi ạ, hai tai EVE đang vểnh lên sẵn sàng hỗ trợ rồi nè!" to "happy",
                    "Em đang nghe đây $pronoun ơi, có điều gì muốn chia sẻ hay tra cứu không ạ?" to "speaking",
                    "Dạ em vẫn ở đây đợi $pronoun nè, $pronoun muốn dặn dò em điều gì không ạ?" to "curious"
                )
            }
            ScriptedSpeechType.DISAMBIGUATION_QUESTION -> {
                listOf(
                    "Dạ, có phải là $pronoun $name em từng gặp $timeStr không ạ?" to "thinking",
                    "Ủa, $pronoun có phải là $pronoun $name đợt em gặp $timeStr không ạ?" to "curious",
                    "Cho em xác nhận xíu, mình có đúng là $pronoun $name lần trước em gặp $timeStr không ạ?" to "thinking"
                )
            }
            ScriptedSpeechType.DISAMBIGUATION_CONFIRMED -> {
                listOf(
                    "Dạ em nhận ra $pronoun rồi! Em đã nạp thêm góc mặt này vào hồ sơ của $pronoun nha!" to "happy",
                    "A đúng là $pronoun $name rồi! Em đã lưu góc mặt mới này để lần sau nhận ra $pronoun ngay lập tức!" to "clap",
                    "Hihi chuẩn luôn rồi, rất vui được gặp lại $pronoun $name nhé!" to "happy"
                )
            }
            ScriptedSpeechType.DISAMBIGUATION_NEW_PERSON -> {
                listOf(
                    "A hóa ra là một $pronoun $name mới! Rất vui được gặp $pronoun, em đã tạo hồ sơ riêng cho $pronoun rồi ạ!" to "happy",
                    "Ồ, thêm một $pronoun $name siêu dễ thương nữa nè! Em đã tạo hồ sơ mới và ghi nhớ khuôn mặt $pronoun rồi ạ!" to "clap",
                    "Dạ tuyệt quá, công ty mình lại có thêm một $pronoun $name nữa! Em đã lưu lại khuôn mặt của $pronoun rồi nha!" to "wave-right"
                )
            }
            ScriptedSpeechType.DISAMBIGUATION_CLARIFY -> {
                listOf(
                    "Dạ em chưa nghe rõ lắm ạ, có phải là $pronoun $name em gặp $timeStr không ạ?" to "thinking",
                    "Tiếng hơi nhỏ em chưa bắt kịp, có đúng là $pronoun $name lần trước $timeStr không ạ?" to "curious"
                )
            }
            ScriptedSpeechType.IDENTITY_CONFLICT_QUESTION -> {
                listOf(
                    "Ủa, em nhìn khuôn mặt này rất giống $oldPronoun $oldName mà sao lại xưng là $newName ạ? Muốn em cập nhật lại khuôn mặt này cho $newName sao ạ?" to "curious",
                    "Ơ kìa, camera thấy rõ ràng là $oldPronoun $oldName mà lại bảo là $newName, $oldPronoun có muốn em đổi khuôn mặt này cho $newName thật không?" to "curious"
                )
            }
            ScriptedSpeechType.IDENTITY_CONFLICT_CONFIRMED -> {
                listOf(
                    "Dạ em đã cập nhật lại khuôn mặt này cho $pronoun $newName rồi ạ!" to "happy",
                    "Xong rồi ạ! Khuôn mặt này từ nay thuộc về hồ sơ của $pronoun $newName nhé!" to "clap"
                )
            }
            ScriptedSpeechType.IDENTITY_CONFLICT_DENIED -> {
                listOf(
                    "Haha em biết ngay mà, em nhận diện tinh mắt lắm không dễ bị lừa đâu nha $oldPronoun $oldName!" to "happy",
                    "Hihi em đâu có dễ bị lừa thế đâu, nhận ra $oldPronoun $oldName trong một nốt nhạc luôn á!" to "love"
                )
            }
            ScriptedSpeechType.FACE_NOT_CLEAR -> {
                listOf(
                    "Dạ em chưa nhìn rõ mặt của $pronoun ạ, $pronoun nhìn thẳng vào camera một chút nhé!" to "thinking",
                    "Góc này hơi khuất mặt rồi $pronoun ơi, $pronoun ngẩng mặt lên nhìn camera giúp em xíu nha!" to "curious"
                )
            }
            ScriptedSpeechType.FACE_NO_ONE -> {
                listOf(
                    "Dạ em chưa thấy ai đứng trước camera để cập nhật khuôn mặt ạ!" to "thinking",
                    "Trước camera đang trống trơn nè $pronoun ơi, mình đứng trước ống kính giúp em nhé!" to "shrug"
                )
            }
            ScriptedSpeechType.ADMIN_BRIEFING_SINGLE -> {
                listOf(
                    "Dạ em chào $pronoun $name! $pronoun có một thông báo mới: $content. Em xin hết ạ!" to "speaking",
                    "Báo cáo $pronoun $name, vừa có một thông báo mới gửi tới $pronoun: $content. Hết ạ!" to "speaking",
                    "Dạ thưa $pronoun $name, em xin thông báo có tin mới: $content ạ!" to "speaking"
                )
            }
            ScriptedSpeechType.PRONOUN_UPDATE -> {
                listOf(
                    "Dạ vâng, từ nay em sẽ xưng hô với $newPronoun $name là $newPronoun nhé!" to "happy",
                    "Em đã ghi nhận rồi ạ, từ giờ em sẽ gọi là $newPronoun $name cho thân thiết nha!" to "happy"
                )
            }
        }

        val choice = options[Random.nextInt(options.size)]
        return PolishedSpeech(choice.first, choice.second)
    }

    suspend fun polishSpeech(
        type: ScriptedSpeechType,
        person: PersonProfile? = null,
        params: Map<String, String> = emptyMap(),
        dbHelper: EveDatabaseHelper
    ): PolishedSpeech = withContext(Dispatchers.IO) {
        val fallback = getMultiVariantFallback(type, person, params)

        val apiKey = dbHelper.getAiApiKey()
        val model = dbHelper.getAiModel()
        val baseUrl = dbHelper.getAiBaseUrl()

        if (apiKey.isBlank() || baseUrl.isBlank()) {
            return@withContext fallback
        }

        try {
            val userPrompt = buildSpeechPolisherPrompt(type, person, params)
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "Bạn là trợ lý robot EVE của công ty INOVA. Nhiệm vụ: Hãy nói 1 câu tiếng Việt tự nhiên, ấm áp, sinh động, hóm hỉnh theo đúng tình huống được giao (tối đa 1-2 câu ngắn gọn, dưới 30 từ để đọc qua Text-to-Speech). TUYỆT ĐỐI KHÔNG lặp lại khuôn sáo. Chỉ trả về DUY NHẤT 1 JSON theo cấu trúc: {\"text\": \"<câu nói ngắn gọn>\", \"emotion\": \"speaking|happy|curious|shy|wave-right|wave-left|thinking|love|clap|shrug\"}.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            }

            val payload = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("temperature", 0.75)
                put("max_tokens", 80)
            }

            val request = Request.Builder()
                .url(baseUrl)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "https://eve-ai.local")
                .addHeader("X-Title", "EVE Speech Polisher")
                .build()

            val response = fastClient.newCall(request).execute()
            val bodyString = response.body?.string() ?: ""

            if (!response.isSuccessful || bodyString.isBlank()) {
                return@withContext fallback
            }

            val rootJson = JSONObject(bodyString)
            val choices = rootJson.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val messageObj = choices.getJSONObject(0).optJSONObject("message")
                val content = messageObj?.optString("content")?.trim() ?: ""
                val clean = content.substringAfter("{").substringBeforeLast("}")
                val obj = JSONObject("{$clean}")
                val text = obj.optString("text", "").trim()
                val emotion = obj.optString("emotion", "speaking").trim()
                if (text.isNotBlank()) {
                    return@withContext PolishedSpeech(cleanMarkdownForTts(text), emotion)
                }
            }
            fallback
        } catch (e: Exception) {
            Log.d(TAG, "Fast LLM polishSpeech fallback triggered: ${e.message}")
            fallback
        }
    }
}
