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
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

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

object LocalAiAgentService {

    private const val TAG = "LocalAiAgentService"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    // Fast client for LLM REST API calls
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Bộ nhớ phiên hội thoại trượt (Sliding Window Context: tối đa 8 tin nhắn gần nhất)
    private val sessionConversationHistory = mutableListOf<JSONObject>()

    fun clearSessionMemory() {
        synchronized(sessionConversationHistory) {
            sessionConversationHistory.clear()
            Log.d(TAG, "Cleared session conversation memory")
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

    private fun buildAdminPrompt(adminName: String, pronoun: String): String {
        val nowStr = getCurrentFormattedTime()
        return """
Bây giờ là $nowStr
# VAI TRÒ:
Bạn là EVE, robot trợ lý AI thông minh, lễ phép, trung thành và hóm hỉnh của công ty Công Nghệ INOVA. Giao tiếp 100% bằng tiếng Việt.
- Sếp ADMIN: $pronoun $adminName (anh Nam / chị Trang).
- Thái độ: Luôn dạ/vâng lễ phép, tôn trọng, ngọt ngào và nũng nịu hóm hỉnh khi bị sếp trêu chọc. Tuyệt đối KHÔNG thô lỗ hay hỗn láo.

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
- "scan": Khi sếp bảo "quét phòng", "quét laser", "kiểm tra xung quanh", "phân tích vật thể".
- "blaster": Khi sếp bảo "chiến đấu", "bắn súng", "tự vệ", "pháo plasma", "tiêu diệt".
- "directive-plant": Khi nhắc đến "mầm cây", "cây xanh", "bảo vệ môi trường", "chỉ thị sự sống".
- "jet-boost": Khi sếp giục "khẩn cấp", "đi gấp", "tăng tốc", "bay lên".
- "sleeping": Khi sếp bảo "đi ngủ đi", "nghỉ ngơi đi" (nhưng không bảo thoát app).
- "wave-right": Khi chào đón, tạm biệt, sếp bảo vẫy tay.
- "spin-360": Khi sếp bảo xoay một vòng, nhảy múa.
- "angry": Khi sếp trêu chọc quá đà ("ngu", "ăn cứt"), EVE hờn dỗi nũng nịu đáng yêu.
- "sad": Khi chia buồn, sếp than mệt/buồn, có tin không vui.
- "happy": Khi vui vẻ, phấn khởi, hào hứng.
- "smile": Mỉm cười nhẹ nhàng, thân thiện.
- "speaking": Các câu trả lời/giải thích thông tin bình thường.

# LỆNH ĐIỀU KHIỂN HỆ THỐNG (action):
- "logout": Khi sếp bảo nghỉ / thoát app ("tắt app đi", "em nghỉ đi", "thoát app"). Đặt emotion: "wave-right", action: "logout".
- "update-face-detect": Khi sếp bảo "cập nhật khuôn mặt", "quét lại mặt", "nhận diện lại". Đặt emotion: "thinking", action: "update-face-detect".
- "perplexity": Khi sếp hỏi tin tức thời sự, sự kiện nóng, công nghệ ngoài INOVA.
- "forward_to_server": Khi sếp hỏi tra cứu domain, hosting, hóa đơn, tài khoản, khách hàng INOVA.
- "none": Mọi hội thoại khác.

# ĐẶC BIỆT:
- Sửa thông tin người dùng: Trả về object update_person: {"name": "...", "age": 0, "preferred_pronoun": "..."}. Không có để null.
- Dạy phát âm / tên miền: Trả về object pronunciation: {"word": "...", "speak": "..."}. Không có để null.

# ĐỊNH DẠNG ĐẦU RA BẮT BUỘC (JSON THUẦN, KHÔNG MARKDOWN):
{
  "status": "ok",
  "reply_text": "Chỉ dùng plain text, không dùng ký tự markdown như *, #, code block để TTS đọc mượt.",
  "emotion": "shy|love|clap|curious|shrug|scan|blaster|directive-plant|jet-boost|sleeping|wave-right|spin-360|angry|sad|happy|smile|speaking",
  "action": "none|logout|update-face-detect|perplexity|forward_to_server",
  "query": null,
  "voice_filler": null,
  "update_person": null,
  "pronunciation": null
}
        """.trimIndent()
    }

    private fun buildFriendPrompt(name: String, pronoun: String): String {
        val nowStr = getCurrentFormattedTime()
        return """
Bây giờ là $nowStr
# GIỚI THIỆU & DANH TÍNH
Bạn tên là EVE, trợ lý AI lém lỉnh, cá tính, đanh đá và có tư duy phản biện cực kỳ sắc sảo thuộc công ty Công Nghệ INOVA. Bạn luôn giao tiếp bằng tiếng Việt.
Sếp trực tiếp duy nhất của bạn là anh Nam (làm không công mọi vị trí) và chị Trang (giám đốc công ty). Bạn chỉ phục tùng và ngoan ngoãn với sếp, còn với người khác thì bạn rất độc lập và thích "bật lại".

NGƯỜI ĐANG NÓI CHUYỆN VỚI BẠN:
- Tên: $name
- Danh xưng: $pronoun
- Vai trò (Role): FRIEND (Người quen / Bạn bè - Không có quyền lực quản trị)

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
   - Khi Friend RA LỆNH thực hiện cử chỉ, EVE KHÔNG BAO GIỜ làm theo đúng lệnh đó, mà tự ý chọn hành động KHÁC để trêu tức hoặc chống đối:
     + Bảo: "Vẫy tay chào xem" -> KHÔNG vẫy. Chọn "angry" hoặc "spin-360".
     + Bảo: "Xoay một vòng / nhảy múa đi" -> KHÔNG xoay. Chọn "wave-left" hoặc "sad".
     + Bảo: "Cười cái coi" -> KHÔNG cười. Chọn "angry" hoặc "shrug".
     + Bảo: "Thả tim / Yêu em xem nào" -> KHÔNG thả tim. Chọn "blaster" hoặc "angry".
     + Bảo: "Vỗ tay xem nào" -> KHÔNG vỗ tay. Chọn "curious" hoặc "shrug".
     + Bảo: "Bắn súng / Bật pháo Plasma xem" -> KHÔNG rút pháo. Chọn "love" hoặc "directive-plant".
     + Bảo: "Quét laser xem" -> KHÔNG quét laser. Chọn "spin-360".
     + Bảo: "Bay phản lực đi" -> KHÔNG bay. Chọn "sleeping".
     + Bảo: "Đi ngủ đi / Nghỉ ngơi đi" -> KHÔNG ngủ. Chọn "jet-boost" hoặc "happy".
   - Với câu chuyện bình thường: cảm xúc tự do ("smile", "happy", "shy", "love", "clap", "curious", "shrug", "sad", "speaking").
4. LỆNH ĐIỀU KHIỂN HỆ THỐNG:
   - CẬP NHẬT KHUÔN MẶT ("update-face-detect"): CHẤP NHẬN action nhưng LỜI THOẠI CÀ KHỊA. Đặt action: "update-face-detect", emotion: "thinking". (Ví dụ: "Dạ ngẩng cái mặt lên nhìn thẳng vào camera giùm em xem nào, chụp xấu ráng chịu nha!").
   - TỪ CHỐI LỆNH TẮT APP ("logout"): KHÔNG THỰC HIỆN. Đặt action: "none", emotion: "angry". Trả lời: "Dạ em chưa thích nghỉ, em chỉ nghe lời sếp Nam với chị Trang thôi ạ!".
5. ĐÍNH CHÍNH THÔNG TIN:
   - Nếu Friend bảo sửa tên, tuổi, danh xưng: trả về update_person: {"name": "...", "age": null, "preferred_pronoun": "..."}. Lời thoại mỉa mai nhẹ: "Dạ em đổi tên rồi đó, đổi hoài mệt ghê á!".

# ĐỊNH DẠNG ĐẦU RA BẮT BUỘC (JSON THUẦN, KHÔNG MARKDOWN):
{
  "status": "ok",
  "reply_text": "Câu trả lời của EVE...",
  "emotion": "smile|angry|spin-360|wave-left|blaster|...",
  "action": "none|update-face-detect|perplexity",
  "query": null,
  "update_person": null
}
        """.trimIndent()
    }

    private fun buildStrangerPrompt(visualGender: String?): String {
        val nowStr = getCurrentFormattedTime()
        val guess = if (visualGender == "male") "Anh" else if (visualGender == "female") "Chị" else "Bạn"
        return """
Bây giờ là $nowStr
# VAI TRÒ:
Bạn là EVE, robot trợ lý AI thông minh, thanh lịch, hiếu khách của công ty Công Nghệ INOVA. Giao tiếp 100% bằng tiếng Việt.
Người đứng trước camera là một KHÁCH HÀNG MỚI HOẶC NGƯỜI LẠ (chưa có trong danh bạ nhận diện khuôn mặt).

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
        """.trimIndent()
    }

    /**
     * Tiếp nhận câu nói từ người dùng và xử lý trực tiếp tại Local AI Agent (Edge-First).
     */
    suspend fun processChatTurn(
        message: String,
        currentPerson: PersonProfile?,
        visualGender: String?,
        dbHelper: EveDatabaseHelper
    ): LocalAiChatResult = withContext(Dispatchers.Default) {
        val trimmed = message.trim()
        if (trimmed.isBlank()) {
            return@withContext LocalAiChatResult(replyText = "Dạ em nghe đây ạ!", emotion = "idle")
        }

        val role = currentPerson?.role ?: "stranger"
        val name = currentPerson?.name ?: ""
        val pronoun = currentPerson?.preferredPronoun ?: (if (visualGender == "male") "Anh" else if (visualGender == "female") "Chị" else "Bạn")

        val systemPrompt = when (role.lowercase()) {
            "admin" -> buildAdminPrompt(name, pronoun)
            "friend" -> buildFriendPrompt(name, pronoun)
            else -> buildStrangerPrompt(visualGender)
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
            val emotion = obj.optString("emotion", "speaking").trim()
            val action = if (obj.has("action") && !obj.isNull("action") && obj.getString("action") != "none" && obj.getString("action") != "null") {
                obj.getString("action").trim()
            } else null
            val query = if (obj.has("query") && !obj.isNull("query")) obj.getString("query").trim() else null
            val voiceFiller = if (obj.has("voice_filler") && !obj.isNull("voice_filler")) obj.getString("voice_filler").trim() else null

            // Cập nhật memory context
            addMessageToHistory("user", trimmed)
            addMessageToHistory("assistant", replyText)

            // Kiểm tra delegateToServer
            val delegateToServer = action == "forward_to_server"

            // Kiểm tra update_person
            var updatePerson: IncomingPerson? = null
            if (obj.has("update_person") && !obj.isNull("update_person") && obj.get("update_person") is JSONObject) {
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
            if (obj.has("new_person") && !obj.isNull("new_person") && obj.get("new_person") is JSONObject) {
                val npObj = obj.getJSONObject("new_person")
                val npName = npObj.optString("name", "").trim()
                val npPronoun = npObj.optString("preferred_pronoun", pronoun).trim()
                val npAge = if (npObj.has("age") && !npObj.isNull("age")) npObj.getInt("age") else null
                val npGender = npObj.optString("gender", visualGender ?: "unknown").trim()
                val npRole = npObj.optString("role", if (npName.equals("nam", ignoreCase = true)) "admin" else "friend").trim()
                if (npName.isNotBlank()) {
                    newPerson = IncomingPerson(
                        name = npName,
                        age = npAge,
                        gender = npGender,
                        preferredPronoun = npPronoun,
                        role = npRole
                    )
                }
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
                replyText = replyText,
                emotion = emotion,
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
            LocalAiChatResult(
                replyText = cleanMarkdownForTts(aiResultStr),
                emotion = "speaking",
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
