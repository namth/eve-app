package com.example.facedetector

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import com.example.facedetector.ai.LocalAiAgentService
import com.example.facedetector.ai.ScriptedSpeechType
import com.example.facedetector.ai.VisualPredictionContext
import com.example.facedetector.data.NotificationItem
import com.example.facedetector.notifications.EveFirebaseMessagingService
import com.google.firebase.messaging.FirebaseMessaging
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.facedetector.ai.VectorMath
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.facedetector.ui.UserAdapter
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.facedetector.ai.EveVisionTracker
import com.example.facedetector.ai.FaceNetModel
import com.example.facedetector.ai.GenderClassifier
import com.example.facedetector.ai.ImageUtils
import com.example.facedetector.ai.VisionTier
import com.example.facedetector.data.EveDatabaseHelper
import com.example.facedetector.data.PersonProfile
import com.example.facedetector.databinding.ActivityMainBinding
import com.example.facedetector.network.N8nService
import com.example.facedetector.ui.EveWebViewHelper
import com.example.facedetector.voice.VoiceAssistantManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), EveVisionTracker.Listener, VoiceAssistantManager.VoiceListener {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    // Unified SQLite Database
    private lateinit var dbHelper: EveDatabaseHelper

    // AI & Vision Layer
    private lateinit var faceNetModel: FaceNetModel
    private lateinit var genderClassifier: GenderClassifier
    private lateinit var faceDetector: FaceDetector
    private lateinit var visionTracker: EveVisionTracker

    // Vision Session State
    private var sessionPredictedGender: String? = null
    private var lastGreetedPersonId: String? = null
    private var lastGreetedTimestamp: Long = 0L
    private val GREETED_COOLDOWN_MS: Long = 60000L

    // UI & Voice Layer
    private lateinit var eveWebView: EveWebViewHelper
    private lateinit var voiceManager: VoiceAssistantManager
    private var isWaitingForAiResponse = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bannerHideRunnable = Runnable {
        binding.cardSpeechBanner.visibility = View.GONE
    }

    // Camera settings
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    private var isFrontFacing: Boolean = true
    private var isAnalyzingFrame = false
    private var isPipMinimized = false
    private var currentSessionId: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false

        if (cameraGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Cần quyền Camera để EVE có thị giác!", Toast.LENGTH_SHORT).show()
        }
        if (!audioGranted) {
            Toast.makeText(this, "Cần quyền Micro để nói chuyện với EVE!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Khóa tính năng tự động tắt màn hình khi đang mở app (luôn giữ màn hình sáng)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 1. Initialize Unified SQLite Database
        dbHelper = EveDatabaseHelper(this)

        // 2. Initialize TFLite FaceNet Model, Gender Classifier & ML Kit Detector
        faceNetModel = FaceNetModel(this)
        genderClassifier = GenderClassifier(this)
        val faceOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .enableTracking()
            .setMinFaceSize(0.15f)
            .build()
        faceDetector = FaceDetection.getClient(faceOptions)

        // 3. Initialize 2-Tier Vision Tracker with Gender & Anti-Flicker Support
        visionTracker = EveVisionTracker(faceNetModel, genderClassifier, dbHelper, this)

        // 4. Initialize EVE HTML Robot WebView (60fps Canvas/SVG)
        eveWebView = EveWebViewHelper(binding.eveWebView) {
            // Khi người dùng bấm vào EVE: Không cho nói nữa, thay bằng 1 hành động bất kỳ
            voiceManager.isListeningPaused = false
            voiceManager.isUserPresent = true
            voiceManager.resetNoSpeechAttempt()

            val randomActions = listOf(
                "wave-left", "wave-right", "spin-360", "scan",
                "directive-plant", "blaster", "curious", "love",
                "shrug", "clap", "jet-boost", "happy", "shy"
            )
            val chosenAction = randomActions.random()
            eveWebView.triggerAction(chosenAction)
        }

        // 5. Initialize Voice Assistant (VAD, STT & TTS with unified DB)
        voiceManager = VoiceAssistantManager(this, dbHelper, this)

        // 6. Setup UI Controls
        setupUI()

        // 7. Start periodic timeout loop for 2.5s departure debounce
        startTimeoutChecker()

        // 8. Request Permissions
        checkAndRequestPermissions()

        // 9. Fetch and persist FCM Registration Token for n8n
        fetchFcmToken()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val fromNotif = intent?.getBooleanExtra(EveFirebaseMessagingService.EXTRA_FROM_NOTIFICATION, false) ?: false
        if (fromNotif) {
            Log.d(TAG, "MainActivity opened via FCM Notification click!")
            lastGreetedTimestamp = 0L // Reset greeting cooldown
            val active = visionTracker.activePerson
            if (active != null && active.role.equals("admin", ignoreCase = true)) {
                val pending = dbHelper.getPendingNotifications()
                if (pending.isNotEmpty()) {
                    triggerAdminBriefing(active, pending)
                }
            }
        }
    }

    private fun fetchFcmToken() {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    Log.d(TAG, "Current FCM Token: $token")
                    dbHelper.setFcmToken(token)
                } else {
                    Log.w(TAG, "Fetching FCM registration token failed", task.exception)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Firebase Messaging: ${e.message}")
        }
    }

    private fun setupUI() {
        // Camera Switch Button
        binding.btnSwitchCamera.setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                isFrontFacing = false
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                isFrontFacing = true
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            startCamera()
        }

        // PiP Minimize/Expand Toggle
        binding.btnTogglePiP.setOnClickListener {
            togglePipWindow()
        }

        // Settings Button
        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        // Apply right-side control panel preference
        val prefs = getSharedPreferences("eve_settings", Context.MODE_PRIVATE)
        val showRightControls = prefs.getBoolean("pref_show_right_controls", false)
        eveWebView.setRightControlsVisibility(showRightControls)

        // Mic Button: Tap to Talk & Instant Interruption
        binding.fabMic.setOnClickListener {
            if (voiceManager.isTtsSpeaking) {
                voiceManager.stopSpeaking()
                voiceManager.isListeningPaused = false
                voiceManager.isUserPresent = true
                voiceManager.resetNoSpeechAttempt()
                voiceManager.startListening(force = true)
            } else if (voiceManager.isListening) {
                voiceManager.stopListening()
            } else {
                voiceManager.isListeningPaused = false
                voiceManager.isUserPresent = true
                voiceManager.resetNoSpeechAttempt()
                voiceManager.startListening(force = true)
            }
        }
    }

    // ==========================================
    // SETTINGS & USER MANAGEMENT DIALOGS
    // ==========================================

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("eve_settings", Context.MODE_PRIVATE)
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val switchControls = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchRightControls)
        val cardUserManagement = dialogView.findViewById<View>(R.id.cardUserManagement)
        val btnClose = dialogView.findViewById<View>(R.id.btnCloseSettings)
        val tvAdminStatus = dialogView.findViewById<TextView>(R.id.tvAdminStatus)

        val isRightControlsVisible = prefs.getBoolean("pref_show_right_controls", false)
        switchControls.isChecked = isRightControlsVisible

        switchControls.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_show_right_controls", isChecked).apply()
            eveWebView.setRightControlsVisibility(isChecked)
        }

        val currentPerson = visionTracker.activePerson
        val isAdmin = currentPerson?.role.equals("admin", ignoreCase = true)
        if (isAdmin) {
            tvAdminStatus.text = "✓ Đã xác thực: ${currentPerson?.preferredPronoun} ${currentPerson?.name} (Admin)"
            tvAdminStatus.setTextColor(Color.parseColor("#10B981"))
        } else {
            tvAdminStatus.text = "Chỉ dành cho Admin (Nhấn để mở)"
            tvAdminStatus.setTextColor(Color.parseColor("#94A3B8"))
        }

        cardUserManagement.setOnClickListener {
            dialog.dismiss()
            if (isAdmin) {
                openUserManagementDialog()
            } else {
                showAdminPinDialog {
                    openUserManagementDialog()
                }
            }
        }

        // Local AI Agent & FCM Token Views
        val tvAiModelInfo = dialogView.findViewById<TextView>(R.id.tvAiModelInfo)
        val tvFcmTokenPreview = dialogView.findViewById<TextView>(R.id.tvFcmTokenPreview)
        val btnCopyFcmToken = dialogView.findViewById<View>(R.id.btnCopyFcmToken)

        val activeModel = dbHelper.getAiModel()
        tvAiModelInfo.text = "Mô hình: $activeModel (OpenRouter)"

        val fcmToken = dbHelper.getFcmToken()
        if (!fcmToken.isNullOrBlank()) {
            tvFcmTokenPreview.text = "Token: $fcmToken"
        } else {
            tvFcmTokenPreview.text = "Token: Đang lấy từ Google Firebase..."
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val tok = task.result
                    dbHelper.setFcmToken(tok)
                    tvFcmTokenPreview.text = "Token: $tok"
                }
            }
        }

        btnCopyFcmToken.setOnClickListener {
            val currentTok = dbHelper.getFcmToken()
            if (!currentTok.isNullOrBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("EVE FCM Token", currentTok)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Đã sao chép FCM Token vào bộ nhớ tạm!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Chưa lấy được Token, vui lòng đợi 2-3 giây!", Toast.LENGTH_SHORT).show()
            }
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.let { window ->
            val displayMetrics = resources.displayMetrics
            val fullWidth = (displayMetrics.widthPixels * 0.94).toInt()
            window.setLayout(fullWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(android.view.Gravity.CENTER)
        }
    }

    private fun showAdminPinDialog(onSuccess: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_pin_auth, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val edtPin = dialogView.findViewById<EditText>(R.id.edtAdminPin)
        val tvError = dialogView.findViewById<TextView>(R.id.tvPinError)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancelPin)
        val btnSubmit = dialogView.findViewById<View>(R.id.btnSubmitPin)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSubmit.setOnClickListener {
            val pin = edtPin.text.toString().trim()
            if (pin == "1234") {
                dialog.dismiss()
                onSuccess()
            } else {
                tvError.visibility = View.VISIBLE
            }
        }

        dialog.show()
    }

    private fun openUserManagementDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_user_management, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvCount = dialogView.findViewById<TextView>(R.id.tvUserCount)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tvEmptyUsers)
        val rvUsers = dialogView.findViewById<RecyclerView>(R.id.rvUsers)
        val btnClose = dialogView.findViewById<View>(R.id.btnCloseUserManagement)

        var users = dbHelper.getAllPeople()
        lateinit var adapter: UserAdapter

        fun refreshList() {
            users = dbHelper.getAllPeople()
            tvCount.text = "Tổng cộng: ${users.size} người dùng"
            if (users.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                rvUsers.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                rvUsers.visibility = View.VISIBLE
                adapter.updateData(users)
            }
        }

        adapter = UserAdapter(
            userList = users,
            onEditClick = { person ->
                openEditUserDialog(person) {
                    refreshList()
                }
            },
            onDeleteClick = { person ->
                AlertDialog.Builder(this)
                    .setTitle("Xóa người dùng")
                    .setMessage("Bạn có chắc chắn muốn xóa hồ sơ của ${person.preferredPronoun} ${person.name} khỏi bộ nhớ EVE?")
                    .setPositiveButton("Xóa") { _, _ ->
                        dbHelper.deletePerson(person.id)
                        visionTracker.refreshCache()
                        if (visionTracker.activePerson?.id == person.id) {
                            visionTracker.reset()
                            binding.tvActivePerson.text = "🤖 EVE AI"
                        }
                        Toast.makeText(this, "Đã xóa hồ sơ ${person.name}", Toast.LENGTH_SHORT).show()
                        refreshList()
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
            }
        )

        rvUsers.layoutManager = LinearLayoutManager(this)
        rvUsers.adapter = adapter

        refreshList()

        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.let { window ->
            val displayMetrics = resources.displayMetrics
            val fullWidth = (displayMetrics.widthPixels * 0.96).toInt()
            window.setLayout(fullWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(android.view.Gravity.CENTER)
        }
    }

    private fun openEditUserDialog(person: PersonProfile, onUpdated: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_user, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val ivAvatar = dialogView.findViewById<ImageView>(R.id.ivEditAvatar)
        val tvUserId = dialogView.findViewById<TextView>(R.id.tvEditUserId)
        val tvFaceStatus = dialogView.findViewById<TextView>(R.id.tvEditFaceStatus)
        val edtName = dialogView.findViewById<EditText>(R.id.edtName)
        val edtPronoun = dialogView.findViewById<EditText>(R.id.edtPronoun)
        val edtAge = dialogView.findViewById<EditText>(R.id.edtAge)
        val spinnerRole = dialogView.findViewById<Spinner>(R.id.spinnerRole)
        val spinnerGender = dialogView.findViewById<Spinner>(R.id.spinnerGender)
        val cbResetFace = dialogView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.cbResetFace)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancelEdit)
        val btnSave = dialogView.findViewById<View>(R.id.btnSaveEdit)

        // Fill data
        tvUserId.text = "ID: ${person.id}"
        tvFaceStatus.text = if (person.faceEmbeddings.isNotEmpty()) "✓ Đã có dữ liệu khuôn mặt (${person.faceEmbeddings.size}/9 góc)" else "Chưa có dữ liệu khuôn mặt"
        tvFaceStatus.setTextColor(if (person.faceEmbeddings.isNotEmpty()) Color.parseColor("#10B981") else Color.parseColor("#94A3B8"))

        if (!person.avatarBase64.isNullOrEmpty()) {
            try {
                val cleanBase64 = if (person.avatarBase64.contains(",")) person.avatarBase64.substringAfter(",") else person.avatarBase64
                val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bm != null) ivAvatar.setImageBitmap(bm) else ivAvatar.setImageResource(R.mipmap.ic_launcher_round)
            } catch (e: Exception) {
                ivAvatar.setImageResource(R.mipmap.ic_launcher_round)
            }
        } else {
            ivAvatar.setImageResource(R.mipmap.ic_launcher_round)
        }

        edtName.setText(person.name)
        edtPronoun.setText(person.preferredPronoun)
        edtAge.setText(person.age?.toString() ?: "")

        // Spinners
        val roleList = listOf("friend" to "Bạn bè (friend)", "admin" to "Quản trị viên (admin)")
        val roleAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, roleList.map { it.second })
        spinnerRole.adapter = roleAdapter
        val roleIdx = roleList.indexOfFirst { it.first.equals(person.role, ignoreCase = true) }.coerceAtLeast(0)
        spinnerRole.setSelection(roleIdx)

        val genderList = listOf("male" to "Nam", "female" to "Nữ", "unknown" to "Khác")
        val genderAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, genderList.map { it.second })
        spinnerGender.adapter = genderAdapter
        val genderIdx = genderList.indexOfFirst { it.first.equals(person.gender, ignoreCase = true) }.coerceAtLeast(0)
        spinnerGender.setSelection(genderIdx)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val newName = edtName.text.toString().trim()
            if (newName.isEmpty()) {
                edtName.error = "Tên không được để trống"
                return@setOnClickListener
            }
            val newPronoun = edtPronoun.text.toString().trim().ifEmpty { "Bạn" }
            val newAge = edtAge.text.toString().trim().toIntOrNull()
            val newRole = roleList[spinnerRole.selectedItemPosition].first
            val newGender = genderList[spinnerGender.selectedItemPosition].first

            val resetFace = cbResetFace.isChecked

            val updated = person.copy(
                name = newName,
                preferredPronoun = newPronoun,
                age = newAge,
                role = newRole,
                gender = newGender,
                faceEmbeddings = if (resetFace) emptyList() else person.faceEmbeddings,
                avatarBase64 = if (resetFace) null else person.avatarBase64,
                lastSeenAt = System.currentTimeMillis()
            )

            dbHelper.upsertPerson(updated)
            visionTracker.refreshCache()

            if (visionTracker.activePerson?.id == person.id) {
                visionTracker.setActivePersonManually(updated, hasFaceConfirmed = if (resetFace) false else visionTracker.hasVisualFaceConfirmed)
                binding.tvActivePerson.text = "👤 $newPronoun $newName"
            }

            Toast.makeText(this, "Đã cập nhật hồ sơ $newName", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            onUpdated()
        }

        dialog.show()
        dialog.window?.let { window ->
            val displayMetrics = resources.displayMetrics
            val fullWidth = (displayMetrics.widthPixels * 0.96).toInt()
            window.setLayout(fullWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(android.view.Gravity.CENTER)
        }
    }

    private fun togglePipWindow() {
        isPipMinimized = !isPipMinimized
        val density = resources.displayMetrics.density

        if (isPipMinimized) {
            binding.cardCameraPiP.layoutParams.width = (44 * density).toInt()
            binding.cardCameraPiP.layoutParams.height = (44 * density).toInt()
            binding.pipPreviewView.visibility = View.GONE
            binding.pipOverlay.visibility = View.GONE
            binding.tvPipMinimizedIcon.visibility = View.VISIBLE
            binding.btnTogglePiP.text = "📷"
        } else {
            binding.cardCameraPiP.layoutParams.width = (100 * density).toInt()
            binding.cardCameraPiP.layoutParams.height = (130 * density).toInt()
            binding.pipPreviewView.visibility = View.VISIBLE
            binding.pipOverlay.visibility = View.VISIBLE
            binding.tvPipMinimizedIcon.visibility = View.GONE
            binding.btnTogglePiP.text = "━"
        }
        binding.cardCameraPiP.requestLayout()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            startCamera()
        }
    }

    private fun startTimeoutChecker() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                visionTracker.checkTimeout()
                mainHandler.postDelayed(this, 500)
            }
        }, 500)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.pipPreviewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processImageProxy(imageProxy)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                Log.e(TAG, "Binding camera failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(imageProxy: ImageProxy) {
        if (isAnalyzingFrame) {
            imageProxy.close()
            return
        }
        isAnalyzingFrame = true

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            isAnalyzingFrame = false
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                val fullBitmap = ImageUtils.imageProxyToBitmap(imageProxy)
                visionTracker.processFrame(faces, fullBitmap, fullBitmap.width, fullBitmap.height)
                imageProxy.close()
                isAnalyzingFrame = false
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Face detection error: ${e.message}")
                imageProxy.close()
                isAnalyzingFrame = false
            }
    }

    // ==========================================
    // EVE VISION TRACKER CALLBACKS (2-TIER)
    // ==========================================

    override fun onPersonGreeted(person: PersonProfile, similarity: Float) {
        runOnUiThread {
            val pronoun = person.preferredPronoun
            binding.tvActivePerson.text = "👤 $pronoun ${person.name} (${(similarity * 100).toInt()}%)"
            binding.tvTierBadge.text = "Thị giác: Tier 2 (Bám khung tiết kiệm)"

            voiceManager.isListeningPaused = false
            voiceManager.isUserPresent = true
            voiceManager.resetNoSpeechAttempt()

            val now = System.currentTimeMillis()
            val isSameRecent = (lastGreetedPersonId == person.id && (now - lastGreetedTimestamp < GREETED_COOLDOWN_MS))
            if (!isSameRecent && !voiceManager.isTtsSpeaking && !voiceManager.isListening && !isWaitingForAiResponse) {
                if (lastGreetedPersonId != person.id) {
                    LocalAiAgentService.clearSessionMemory()
                }
                lastGreetedPersonId = person.id
                lastGreetedTimestamp = now

                val isAdmin = person.role.equals("admin", ignoreCase = true)
                val pendingNotifs = if (isAdmin) dbHelper.getPendingNotifications() else emptyList()

                if (isAdmin && pendingNotifs.isNotEmpty()) {
                    triggerAdminBriefing(person, pendingNotifs)
                } else {
                    speakScripted(
                        type = ScriptedSpeechType.GREETING_KNOWN,
                        person = person,
                        prefixEmoji = "👋"
                    )
                }
            }
        }
    }

    private fun triggerAdminBriefing(person: PersonProfile, pendingNotifs: List<NotificationItem>) {
        val pronoun = person.preferredPronoun
        val name = person.name

        if (pendingNotifs.size == 1) {
            val notif = pendingNotifs[0]
            val content = notif.body.ifBlank { notif.title }
            speakScripted(
                type = ScriptedSpeechType.ADMIN_BRIEFING_SINGLE,
                person = person,
                params = mapOf("content" to content)
            ) {
                dbHelper.markNotificationsAsRead(listOf(notif.id))
                try {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    nm.cancelAll()
                } catch (_: Exception) {}
            }
            return
        }

        // Multiple notifications: Use LocalAiAgentService (gpt-4o-mini via OpenRouter) to semantically group and summarize
        isWaitingForAiResponse = true
        binding.tvVoiceStatus.text = "EVE đang tổng hợp thông báo cho $pronoun $name..."
        eveWebView.setEmotion("thinking")

        lifecycleScope.launch {
            val summarySpeech = LocalAiAgentService.summarizeNotifications(
                adminName = name,
                pronoun = pronoun,
                notifications = pendingNotifs,
                dbHelper = dbHelper
            )

            runOnUiThread {
                isWaitingForAiResponse = false
                speakAndShowBanner(summarySpeech, "speaking") {
                    dbHelper.markNotificationsAsRead(pendingNotifs.map { it.id })
                    try {
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                        nm.cancelAll()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onPersonDeparted(person: PersonProfile) {
        LocalAiAgentService.clearSessionMemory()
        runOnUiThread {
            binding.tvActivePerson.text = "👀 Đang tìm khuôn mặt..."
            binding.tvTierBadge.text = "Thị giác: Không có người"
            binding.pipOverlay.clear()

            // Reset session greeting tracking
            lastGreetedPersonId = null
            sessionPredictedGender = null

            // Người dùng đã rời đi -> dừng chế độ auto-listen và dừng nghe mic
            voiceManager.isUserPresent = false
            voiceManager.isListeningPaused = false
            voiceManager.resetNoSpeechAttempt()
            voiceManager.stopListening()

            speakScripted(
                type = ScriptedSpeechType.FAREWELL,
                person = person,
                prefixEmoji = "👋"
            )
        }
    }

    override fun onFaceTracked(person: PersonProfile, box: android.graphics.Rect) {
        runOnUiThread {
            val fullBmp = visionTracker.latestFullBitmap ?: return@runOnUiThread
            binding.pipOverlay.updateFace(
                rect = box,
                label = "${person.name} (Đang theo dõi)",
                isRecognized = true,
                imageWidth = fullBmp.width,
                imageHeight = fullBmp.height,
                isFrontCamera = isFrontFacing
            )
        }
    }

    override fun onUnknownFaceDetected(box: android.graphics.Rect) {
        runOnUiThread {
            val fullBmp = visionTracker.latestFullBitmap ?: return@runOnUiThread
            binding.tvActivePerson.text = "👤 Người lạ (Chưa đăng ký)"
            binding.pipOverlay.updateFace(
                rect = box,
                label = "Chưa xác định",
                isRecognized = false,
                imageWidth = fullBmp.width,
                imageHeight = fullBmp.height,
                isFrontCamera = isFrontFacing
            )
        }
    }

    override fun onUnknownPersonGreetable(box: android.graphics.Rect, gender: String, croppedFace: android.graphics.Bitmap) {
        runOnUiThread {
            // Nếu đã có người quen active hoặc EVE đang nói dở hoặc đang lắng nghe / chờ AI phản hồi thì không chen ngang
            if (visionTracker.activePerson != null || voiceManager.isTtsSpeaking || voiceManager.isListening || isWaitingForAiResponse) {
                return@runOnUiThread
            }

            sessionPredictedGender = gender
            voiceManager.isListeningPaused = false
            voiceManager.isUserPresent = true
            voiceManager.resetNoSpeechAttempt()

            val isMale = (gender == "male")
            binding.tvActivePerson.text = if (isMale) "👤 Khách nam (Chưa biết tên)" else "👤 Khách nữ (Chưa biết tên)"
            speakScripted(
                type = ScriptedSpeechType.GREETING_STRANGER,
                params = mapOf("gender" to gender),
                prefixEmoji = "👋"
            )
        }
    }

    override fun onAmbiguousPersonDetected(
        candidate: PersonProfile,
        similarity: Float,
        faceRect: android.graphics.Rect,
        croppedFace: android.graphics.Bitmap,
        embedding: FloatArray
    ) {
        runOnUiThread {
            if (visionTracker.activePerson != null || voiceManager.isTtsSpeaking || voiceManager.isListening || isWaitingForAiResponse || pendingAmbiguityConfirmation != null || pendingDisambiguation != null) {
                return@runOnUiThread
            }

            pendingAmbiguityConfirmation = PendingAmbiguityConfirmation(
                candidate = candidate,
                similarity = similarity,
                faceRect = faceRect,
                croppedFace = croppedFace,
                embedding = embedding
            )

            voiceManager.isListeningPaused = false
            voiceManager.isUserPresent = true
            voiceManager.resetNoSpeechAttempt()

            val simPercent = (similarity * 100).toInt()
            binding.tvActivePerson.text = "❓ Nghi vấn: ${candidate.preferredPronoun} ${candidate.name} ($simPercent%)"
            speakScripted(
                type = ScriptedSpeechType.AMBIGUOUS_QUESTION,
                person = candidate,
                params = mapOf("similarityPercent" to simPercent.toString())
            )
        }
    }

    override fun onNoFace() {
        runOnUiThread {
            binding.pipOverlay.clear()
            if (visionTracker.activePerson == null) {
                binding.tvActivePerson.text = "👀 Đang tìm khuôn mặt..."
                binding.tvTierBadge.text = "Thị giác: Không có người"
            }
        }
    }

    // ==========================================
    // VOICE ASSISTANT & STT CALLBACKS
    // ==========================================

    override fun onListeningStateChanged(isListening: Boolean) {
        runOnUiThread {
            if (isListening) {
                binding.tvVoiceStatus.text = "EVE đang lắng nghe bạn..."
                binding.fabMic.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#EF4444")) // Red
                eveWebView.setEmotion("thinking")
            } else {
                binding.tvVoiceStatus.text = "Chạm để nói chuyện"
                binding.fabMic.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00F0FF")) // Cyan
            }
        }
    }

    override fun onBargeInTriggered() {
        runOnUiThread {
            eveWebView.setEmotion("thinking")
            binding.tvVoiceStatus.text = "EVE đang lắng nghe bạn nói lại..."
            binding.cardSpeechBanner.visibility = View.GONE
        }
    }

    enum class ConfirmationIntent {
        CONFIRMED,
        DENIED,
        AMBIGUOUS
    }

    /**
     * On-Device Semantic Intent Classifier for Vietnamese confirmation/denial variants.
     * Supports phrases like "Ok em nhớ chuẩn đấy", "Sai, nhầm rồi", "Anh chứ ai", etc.
     */
    fun classifyConfirmationIntent(transcript: String): ConfirmationIntent {
        val lower = transcript.lowercase().trim()
            .replace(",", " ")
            .replace(".", " ")
            .replace("!", " ")
            .replace("?", " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

        if (lower.isEmpty()) return ConfirmationIntent.AMBIGUOUS

        // Handle Vietnamese filler negation followed by affirmation: "không, đúng rồi" -> CONFIRMED
        val negationWithAffirmation = Regex("""^(?:không|hông|k|ko)\s+(?:đúng|chuẩn|phải|chính xác|anh đây|mình đây|em đây|chuẩn rồi)""")
        if (negationWithAffirmation.containsMatchIn(lower)) {
            return ConfirmationIntent.CONFIRMED
        }

        val confirmPhrases = listOf(
            "chuẩn", "nhớ chuẩn", "chuẩn rồi", "chuẩn đấy", "chuẩn đét", "chuẩn luôn",
            "đúng", "đúng rồi", "đúng đấy", "đúng anh", "đúng em", "đúng mình", "đúng tôi", "đúng người",
            "phải", "phải rồi", "phải đấy", "chính xác", "chính là", "chính anh", "anh chứ ai", "tôi chứ ai",
            "anh đây", "mình đây", "tôi đây", "em đây", "đây nè", "đây em",
            "nhớ siêu", "giỏi thế", "tinh mắt", "ok em", "dạ phải", "rồi đấy",
            "ok", "oke", "yes", "yeah", "ừ", "uh", "uk", "dạ đúng", "ừ đúng"
        )

        val denyPhrases = listOf(
            "sai", "sai rồi", "sai bét", "sai lè", "nhầm", "nhầm rồi", "nhầm to", "lộn rồi",
            "không phải", "đâu phải", "đâu có", "chưa phải", "không", "no", "nope", "k phải", "ko phải",
            "người khác", "anh khác", "bạn khác", "em khác", "ai đấy", "ai cơ", "nam khác",
            "mới gặp lần đầu", "lần đầu tiên", "chưa từng gặp", "chưa gặp bao giờ", "lần đầu gặp"
        )

        val hasDenyPhrase = denyPhrases.any { lower.contains(it) }
        val hasConfirmPhrase = confirmPhrases.any { lower.contains(it) }

        if (hasDenyPhrase && !hasConfirmPhrase) {
            return ConfirmationIntent.DENIED
        }

        if (hasDenyPhrase && hasConfirmPhrase) {
            val stripped = lower
                .replace("không phải", "")
                .replace("đâu phải", "")
                .replace("chưa phải", "")
                .replace("k phải", "")
                .replace("ko phải", "")
                .trim()
            val remainingConfirm = confirmPhrases.any { stripped.contains(it) }
            return if (remainingConfirm) ConfirmationIntent.CONFIRMED else ConfirmationIntent.DENIED
        }

        if (hasConfirmPhrase) {
            return ConfirmationIntent.CONFIRMED
        }

        return ConfirmationIntent.AMBIGUOUS
    }

    fun formatRelativeTimeVi(lastSeenTimestamp: Long): String {
        if (lastSeenTimestamp <= 0) return "hồi trước"
        val diffMs = System.currentTimeMillis() - lastSeenTimestamp
        if (diffMs < 0) return "gần đây"

        val seconds = diffMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            minutes < 5 -> "vừa nãy"
            minutes < 60 -> "cách đây $minutes phút"
            hours < 24 -> "cách đây $hours tiếng"
            days == 1L -> "hôm qua"
            days < 30 -> "cách đây $days hôm"
            days < 365 -> "cách đây ${days / 30} tháng"
            else -> "cách đây hơn 1 năm"
        }
    }

    data class DisambiguationCandidate(
        val person: PersonProfile,
        val maxSim: Float
    )

    data class PendingDisambiguation(
        val candidates: List<DisambiguationCandidate>,
        var currentIndex: Int = 0,
        val currentEmbedding: FloatArray,
        val currentAvatarBase64: String?,
        val detectedPerson: com.example.facedetector.network.IncomingPerson
    )
    private var pendingDisambiguation: PendingDisambiguation? = null

    data class PendingIdentityConflict(
        val newPerson: com.example.facedetector.network.IncomingPerson,
        val existingVisualPerson: PersonProfile
    )
    private var pendingConflict: PendingIdentityConflict? = null

    data class PendingAmbiguityConfirmation(
        val candidate: PersonProfile,
        val similarity: Float,
        val faceRect: android.graphics.Rect,
        val croppedFace: android.graphics.Bitmap,
        val embedding: FloatArray
    )
    private var pendingAmbiguityConfirmation: PendingAmbiguityConfirmation? = null

    override fun onSpeechResult(transcript: String) {
        voiceManager.isListeningPaused = false
        voiceManager.isUserPresent = true
        voiceManager.resetNoSpeechAttempt()
        runOnUiThread {
            binding.tvVoiceStatus.text = "EVE đang xử lý: \"$transcript\""
            eveWebView.setEmotion("thinking")
        }

        lifecycleScope.launch {
            handleSpeechResultAsync(transcript)
        }
    }

    private suspend fun handleSpeechResultAsync(transcript: String) {
        // 0. Xử lý phản hồi xác nhận nhận diện ngờ ngợ (Ambiguous Confirmation)
        val amb = pendingAmbiguityConfirmation
        if (amb != null) {
            val contextQ = "Em nhìn ${amb.candidate.preferredPronoun} quen lắm, ${amb.candidate.preferredPronoun} có phải là ${amb.candidate.preferredPronoun} ${amb.candidate.name} không ạ?"
            val intent = LocalAiAgentService.classifyConfirmationIntent(transcript, contextQ, dbHelper)
            when (intent) {
                ConfirmationIntent.CONFIRMED -> {
                    val confirmedPerson = amb.candidate
                    pendingAmbiguityConfirmation = null

                    // Áp dụng thuật toán Nearest Replacement & Moving Average (70/30)
                    val updatedEmbeddings = VectorMath.blendEmbeddings(
                        existingEmbeddings = confirmedPerson.faceEmbeddings,
                        newEmbedding = amb.embedding,
                        alpha = 0.30f
                    )
                    val updatedPerson = confirmedPerson.copy(
                        faceEmbeddings = updatedEmbeddings,
                        lastSeenAt = System.currentTimeMillis()
                    )
                    dbHelper.upsertPerson(updatedPerson)
                    visionTracker.refreshCache()
                    visionTracker.setActivePersonManually(updatedPerson, hasFaceConfirmed = true)

                    runOnUiThread {
                        binding.tvTierBadge.text = "Thị giác: Tier 2 (Bám khung tiết kiệm)"
                        binding.tvActivePerson.text = "👤 ${updatedPerson.preferredPronoun} ${updatedPerson.name}"
                        speakScripted(
                            type = ScriptedSpeechType.AMBIGUOUS_CONFIRMED,
                            person = updatedPerson
                        )
                    }
                    return
                }
                ConfirmationIntent.DENIED -> {
                    val deniedPerson = amb.candidate
                    pendingAmbiguityConfirmation = null
                    visionTracker.setAmbiguousCooldown(deniedPerson.id, durationMs = 30_000L)

                    runOnUiThread {
                        binding.tvActivePerson.text = "👤 Khách chưa định danh"
                        binding.tvTierBadge.text = "Thị giác: Tier 1 (Tìm người quen)"
                        speakScripted(
                            type = ScriptedSpeechType.AMBIGUOUS_DENIED,
                            person = deniedPerson
                        )
                    }
                    return
                }
                ConfirmationIntent.AMBIGUOUS -> {
                    pendingAmbiguityConfirmation = null
                }
            }
        }

        // 1. Xử lý phản hồi xác nhận phân biệt người trùng tên (Same-name Disambiguation)
        val disambiguation = pendingDisambiguation
        if (disambiguation != null) {
            val candidate = disambiguation.candidates[disambiguation.currentIndex].person
            val timeStr = formatRelativeTimeVi(candidate.lastSeenAt)
            val contextQ = "Có phải ${candidate.preferredPronoun} ${candidate.name} em gặp $timeStr không ạ?"
            val intent = LocalAiAgentService.classifyConfirmationIntent(transcript, contextQ, dbHelper)
            when (intent) {
                ConfirmationIntent.CONFIRMED -> {
                    val confirmedPerson = disambiguation.candidates[disambiguation.currentIndex].person
                    pendingDisambiguation = null

                    // Tự động nạp thêm góc mặt mới vào bộ 9 vector của người này nếu có độ đa dạng và < 9
                    val updatedEmbeddings = confirmedPerson.faceEmbeddings.toMutableList()
                    val maxSim = updatedEmbeddings.maxOfOrNull { VectorMath.cosineSimilarity(disambiguation.currentEmbedding, it) } ?: 0f
                    if (maxSim < 0.94f && updatedEmbeddings.size < 9) {
                        updatedEmbeddings.add(disambiguation.currentEmbedding)
                    }

                    val updated = confirmedPerson.copy(
                        faceEmbeddings = updatedEmbeddings,
                        avatarBase64 = disambiguation.currentAvatarBase64 ?: confirmedPerson.avatarBase64,
                        lastSeenAt = System.currentTimeMillis()
                    )
                    dbHelper.upsertPerson(updated)
                    visionTracker.refreshCache()
                    visionTracker.setActivePersonManually(updated, hasFaceConfirmed = true)
                    runOnUiThread {
                        binding.tvTierBadge.text = "Thị giác: Tier 2 (Bám khung tiết kiệm)"
                        binding.tvActivePerson.text = "👤 ${updated.preferredPronoun} ${updated.name}"
                        speakScripted(
                            type = ScriptedSpeechType.DISAMBIGUATION_CONFIRMED,
                            person = updated
                        )
                    }
                    return
                }

                ConfirmationIntent.DENIED -> {
                    val nextIndex = disambiguation.currentIndex + 1
                    if (nextIndex < disambiguation.candidates.size && disambiguation.candidates[nextIndex].maxSim >= 0.55f) {
                        disambiguation.currentIndex = nextIndex
                        val candidate2 = disambiguation.candidates[nextIndex].person
                        val tStr = formatRelativeTimeVi(candidate2.lastSeenAt)
                        runOnUiThread {
                            speakScripted(
                                type = ScriptedSpeechType.DISAMBIGUATION_QUESTION,
                                person = candidate2,
                                params = mapOf("timeStr" to tStr)
                            )
                        }
                        return
                    } else {
                        // Không còn ứng viên trùng tên phù hợp -> Tạo hồ sơ mới cùng tên
                        pendingDisambiguation = null
                        val newPerson = PersonProfile(
                            id = "person_${System.currentTimeMillis()}",
                            name = disambiguation.detectedPerson.name,
                            age = disambiguation.detectedPerson.age,
                            gender = disambiguation.detectedPerson.gender,
                            preferredPronoun = disambiguation.detectedPerson.preferredPronoun,
                            role = disambiguation.detectedPerson.role,
                            avatarBase64 = disambiguation.currentAvatarBase64,
                            faceEmbeddings = listOf(disambiguation.currentEmbedding),
                            createdAt = System.currentTimeMillis(),
                            lastSeenAt = System.currentTimeMillis()
                        )
                        dbHelper.upsertPerson(newPerson)
                        visionTracker.refreshCache()
                        visionTracker.setActivePersonManually(newPerson, hasFaceConfirmed = true)
                        runOnUiThread {
                            binding.tvTierBadge.text = "Thị giác: Tier 2 (Bám khung tiết kiệm)"
                            binding.tvActivePerson.text = "👤 ${newPerson.preferredPronoun} ${newPerson.name}"
                            speakScripted(
                                type = ScriptedSpeechType.DISAMBIGUATION_NEW_PERSON,
                                person = newPerson
                            )
                        }
                        return
                    }
                }

                ConfirmationIntent.AMBIGUOUS -> {
                    val currentCandidate = disambiguation.candidates[disambiguation.currentIndex].person
                    val tStr = formatRelativeTimeVi(currentCandidate.lastSeenAt)
                    runOnUiThread {
                        speakScripted(
                            type = ScriptedSpeechType.DISAMBIGUATION_CLARIFY,
                            person = currentCandidate,
                            params = mapOf("timeStr" to tStr)
                        )
                    }
                    return
                }
            }
        }

        // 2. Xử lý phản hồi xác nhận xung đột danh tính (Trường hợp 2)
        val conflict = pendingConflict
        if (conflict != null) {
            val conflictQ = "Ủa, em nhìn khuôn mặt này rất giống ${conflict.existingVisualPerson.name} mà sao lại xưng là ${conflict.newPerson.name} ạ?"
            val intent = LocalAiAgentService.classifyConfirmationIntent(transcript, conflictQ, dbHelper)
            if (intent == ConfirmationIntent.CONFIRMED) {
                pendingConflict = null
                val captureResult = visionTracker.captureCurrentFaceForPerson(
                    name = conflict.newPerson.name,
                    preferredPronoun = conflict.newPerson.preferredPronoun,
                    gender = conflict.newPerson.gender,
                    role = conflict.newPerson.role
                )
                val p = captureResult.person ?: dbHelper.findPersonByName(conflict.newPerson.name)
                runOnUiThread {
                    if (p != null) {
                        visionTracker.setActivePersonManually(p, hasFaceConfirmed = true)
                        binding.tvActivePerson.text = "👤 ${p.preferredPronoun} ${p.name}"
                    }
                    speakScripted(
                        type = ScriptedSpeechType.IDENTITY_CONFLICT_CONFIRMED,
                        person = p,
                        params = mapOf("newName" to conflict.newPerson.name, "newPronoun" to conflict.newPerson.preferredPronoun)
                    )
                }
                return
            } else if (intent == ConfirmationIntent.DENIED) {
                pendingConflict = null
                val oldPronoun = conflict.existingVisualPerson.preferredPronoun
                val oldName = conflict.existingVisualPerson.name
                runOnUiThread {
                    speakScripted(
                        type = ScriptedSpeechType.IDENTITY_CONFLICT_DENIED,
                        person = conflict.existingVisualPerson,
                        params = mapOf("oldPronoun" to oldPronoun, "oldName" to oldName)
                    )
                }
                return
            } else {
                pendingConflict = null
            }
        }

        isWaitingForAiResponse = true
        runOnUiThread {
            eveWebView.setEmotion("thinking")
            binding.tvVoiceStatus.text = "EVE đang suy nghĩ..."
        }

        val activePerson = visionTracker.activePerson
        val visualGender = if (!sessionPredictedGender.isNullOrBlank()) sessionPredictedGender else null

        val visualPrediction = visionTracker.latestVisualPrediction?.let { info ->
            VisualPredictionContext(
                candidateName = info.candidateName,
                candidatePronoun = info.candidatePronoun,
                similarityPercent = info.similarityPercent,
                isAmbiguous = info.isAmbiguous
            )
        }

        val chatResult = LocalAiAgentService.processChatTurn(
            message = transcript,
            currentPerson = activePerson,
            visualGender = visualGender,
            dbHelper = dbHelper,
            visualPredictionContext = visualPrediction
        )

        // 1. Nếu mất mạng hoàn toàn: Thông báo mạng trực tiếp
        if (chatResult.isNetworkError) {
            isWaitingForAiResponse = false
            runOnUiThread {
                speakAndShowBanner(chatResult.replyText, "sad")
            }
            return
        }

        // 2. Action = "perplexity" (Tin tức thời sự nóng - Xử lý trực tiếp tại Local)
        if (chatResult.action == "perplexity" || !chatResult.perplexityQuery.isNullOrBlank()) {
            val query = chatResult.perplexityQuery ?: transcript
            val filler = chatResult.voiceFiller ?: chatResult.replyText.ifBlank { "Dạ để em tra cứu tin tức mới nhất ngay ạ!" }
            
            runOnUiThread {
                speakAndShowBanner(filler, "thinking")
            }

            // Gọi Perplexity API trực tiếp ngay trên máy khách
            val newsResult = LocalAiAgentService.callPerplexityApi(query, dbHelper)
            runOnUiThread {
                isWaitingForAiResponse = false
                if (!newsResult.isNullOrBlank()) {
                    speakAndShowBanner(newsResult, "curious")
                } else {
                    speakAndShowBanner("Dạ em chưa tìm thấy tin tức mới nhất về chủ đề này rồi ạ.", "shrug")
                }
            }
            return
        }

        // 3. Action = "forward_to_server" (Tra cứu kỹ thuật INOVA: domain, hosting, hóa đơn, tài khoản, khách hàng)
        if (chatResult.delegateToServer || chatResult.action == "forward_to_server") {
            val filler = chatResult.voiceFiller ?: chatResult.replyText.ifBlank { "Dạ để em kiểm tra trên hệ thống xíu ạ!" }
            
            // Phát câu đệm giọng nói trước trong lúc gọi n8n
            runOnUiThread {
                speakAndShowBanner(filler, "thinking")
            }

            try {
                val response = N8nService.sendMessage(transcript, activePerson, currentSessionId)
                runOnUiThread {
                    isWaitingForAiResponse = false
                    handleAiResponse(response, transcript)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error contacting n8n for forward_to_server: ${e.message}", e)
                runOnUiThread {
                    isWaitingForAiResponse = false
                    speakAndShowBanner("Dạ hệ thống tra cứu server đang bận, sếp thử lại sau nhé!", "shrug")
                }
            }
            return
        }

        // 4. Xử lý tác vụ hoàn toàn tại Local
        val lowerMsg = transcript.lowercase().trim()
        val currentVisionPerson = visionTracker.activePerson
        val isAdmin = currentVisionPerson?.role.equals("admin", ignoreCase = true)
        val isLogoutCommand = lowerMsg.contains("nghỉ đi") || lowerMsg.contains("tự out") || lowerMsg.contains("tắt app") ||
                lowerMsg.contains("thoát app") || lowerMsg.contains("tắt ứng dụng") || lowerMsg.contains("out đi")
        val isUpdateFaceCommand = lowerMsg.contains("cập nhật lại nhận diện") || lowerMsg.contains("cập nhật nhận diện") ||
                lowerMsg.contains("cập nhật lại khuôn mặt") || lowerMsg.contains("cập nhật khuôn mặt") ||
                lowerMsg.contains("quét lại mặt") || lowerMsg.contains("nhận diện lại mặt") ||
                lowerMsg.contains("cập nhật lại mặt")

        val effectiveAction = when {
            chatResult.action != null -> chatResult.action
            isAdmin && isLogoutCommand -> "logout"
            isUpdateFaceCommand -> "update-face-detect"
            else -> null
        }

        // 4.1. Xử lý phản bác danh tính (identity_denied) nếu có
        if (effectiveAction == "identity_denied") {
            val deniedName = visionTracker.denyCurrentIdentity()
            Log.i(TAG, "Người dùng phản bác danh tính: $deniedName. Đã rollback.")
            if (chatResult.newPerson == null) {
                runOnUiThread {
                    binding.tvActivePerson.text = "👤 Khách chưa định danh"
                    binding.tvTierBadge.text = "Thị giác: Tier 1 (Tìm người quen)"
                    isWaitingForAiResponse = false
                    speakAndShowBanner(chatResult.replyText, chatResult.emotion)
                }
                return
            }
        }

        // 4.2. Đăng ký người mới (new_person)
        chatResult.newPerson?.let { newP ->
            if (newP.name.isNotBlank()) {
                // Thử chụp khuôn mặt và trích xuất vector từ camera
                val captureResult = visionTracker.captureCurrentFaceForPerson(
                    name = newP.name,
                    preferredPronoun = newP.preferredPronoun,
                    gender = newP.gender,
                    role = newP.role
                )

                var savedPerson: PersonProfile? = captureResult.person

                // Nếu tại khoảnh khắc đó camera chưa thấy góc mặt rõ (captureResult.person == null),
                // VẪN PHẢI TẠO VÀ LƯU HỒ SƠ VÀO SQLITE để không bị mất thông tin đăng ký!
                if (savedPerson == null) {
                    val existing = dbHelper.findPersonByName(newP.name)
                    if (existing != null) {
                        savedPerson = existing
                    } else {
                        val newProfile = PersonProfile(
                            id = "person_${System.currentTimeMillis()}",
                            name = newP.name,
                            age = newP.age,
                            gender = newP.gender,
                            preferredPronoun = newP.preferredPronoun,
                            role = newP.role,
                            avatarBase64 = null,
                            faceEmbeddings = emptyList(),
                            createdAt = System.currentTimeMillis(),
                            lastSeenAt = System.currentTimeMillis()
                        )
                        dbHelper.upsertPerson(newProfile)
                        visionTracker.refreshCache()
                        savedPerson = newProfile
                    }
                }

                if (savedPerson != null) {
                    val hasFace = captureResult.success && savedPerson.faceEmbeddings.isNotEmpty()
                    visionTracker.setActivePersonManually(savedPerson, hasFaceConfirmed = hasFace)
                    sessionPredictedGender = savedPerson.gender
                    runOnUiThread {
                        binding.tvActivePerson.text = "👤 ${savedPerson.preferredPronoun} ${savedPerson.name}"
                        binding.tvTierBadge.text = if (hasFace) "Thị giác: Tier 2 (Bám khung tiết kiệm)" else "Thị giác: Chờ thấy mặt"
                        binding.tvVoiceStatus.text = "Đã đăng ký: ${savedPerson.preferredPronoun} ${savedPerson.name}"
                    }
                    Log.i(TAG, "Đã đăng ký thành công hồ sơ người mới: ${savedPerson.preferredPronoun} ${savedPerson.name} (hasFace=$hasFace)")
                }
            } else if (newP.preferredPronoun.isNotBlank()) {
                // Người dùng chỉ xưng đại từ (ví dụ: "gọi tôi là chú nhé")
                val current = visionTracker.activePerson
                if (current != null) {
                    val updated = current.copy(
                        preferredPronoun = newP.preferredPronoun,
                        lastSeenAt = System.currentTimeMillis()
                    )
                    dbHelper.upsertPerson(updated)
                    visionTracker.refreshCache()
                    visionTracker.setActivePersonManually(updated, hasFaceConfirmed = visionTracker.hasVisualFaceConfirmed)
                    runOnUiThread {
                        binding.tvActivePerson.text = "👤 ${newP.preferredPronoun} ${updated.name}"
                    }
                }
            }
        }

        // 4.3. Sửa thông tin người dùng (update_person) - Chặn tuyệt đối nếu là phản bác danh tính hoặc vừa đăng ký người mới
        if (effectiveAction != "identity_denied" && chatResult.newPerson == null) {
            chatResult.updatePerson?.let { up ->
                val cur = visionTracker.activePerson
                if (cur != null) {
                    val updated = cur.copy(
                        name = if (up.name.isNotBlank()) up.name else cur.name,
                        preferredPronoun = if (up.preferredPronoun.isNotBlank()) up.preferredPronoun else cur.preferredPronoun,
                        age = up.age ?: cur.age
                    )
                    dbHelper.upsertPerson(updated)
                    visionTracker.refreshCache()
                    visionTracker.setActivePersonManually(updated, hasFaceConfirmed = true)
                    runOnUiThread {
                        binding.tvActivePerson.text = "👤 ${updated.preferredPronoun} ${updated.name}"
                    }
                }
            }
        }

        // 4.4. Dạy phát âm (pronunciation)
        chatResult.pronunciation?.let { pair: Pair<String, String> ->
            dbHelper.savePronunciation(pair.first, pair.second)
        }

        // 4.5. Lệnh hệ thống (action: logout, update-face-detect)
        when (effectiveAction) {
            "logout" -> {
                Log.d(TAG, "Triggering logout action: Stopping mic and exiting to Home screen.")
                voiceManager.stopListening()
                voiceManager.isListeningPaused = true

                var hasExited = false
                val exitApp = {
                    if (!hasExited) {
                        hasExited = true
                        try {
                            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            startActivity(homeIntent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to launch HOME intent: ${e.message}")
                        }
                        finishAffinity()
                    }
                }

                mainHandler.postDelayed({ exitApp() }, 4500)

                runOnUiThread {
                    isWaitingForAiResponse = false
                    speakAndShowBanner(chatResult.replyText, chatResult.emotion) {
                        exitApp()
                    }
                }
                return
            }
            "update-face-detect" -> {
                val active = visionTracker.activePerson
                if (active != null) {
                    val captureResult = visionTracker.captureCurrentFaceForPerson(
                        name = active.name,
                        preferredPronoun = active.preferredPronoun,
                        gender = active.gender,
                        role = active.role
                    )
                    runOnUiThread {
                        isWaitingForAiResponse = false
                        if (captureResult.success) {
                            val p = captureResult.person ?: active
                            visionTracker.setActivePersonManually(p, hasFaceConfirmed = true)
                            binding.tvVoiceStatus.text = "Đã cập nhật lại khuôn mặt cho ${p.preferredPronoun} ${p.name}"
                            speakAndShowBanner(chatResult.replyText, chatResult.emotion)
                        } else {
                            speakAndShowBanner("Dạ em chưa nhìn rõ mặt của ${active.preferredPronoun} ạ, ${active.preferredPronoun} nhìn thẳng vào camera một chút nhé!", "thinking")
                        }
                    }
                } else {
                    runOnUiThread {
                        isWaitingForAiResponse = false
                        speakAndShowBanner("Dạ em chưa nhận diện được ai trước camera để cập nhật khuôn mặt ạ!", "thinking")
                    }
                }
                return
            }
        }

        // 4.6. Phát câu trả lời thông thường
        runOnUiThread {
            isWaitingForAiResponse = false
            speakAndShowBanner(chatResult.replyText, chatResult.emotion)
        }
    }

    override fun onSpeechError(errorMessage: String) {
        isWaitingForAiResponse = false
        runOnUiThread {
            binding.tvVoiceStatus.text = errorMessage
            eveWebView.setEmotion("idle")
        }
    }

    override fun onNoSpeechDetected(attempt: Int) {
        runOnUiThread {
            if (pendingAmbiguityConfirmation != null) {
                val amb = pendingAmbiguityConfirmation
                pendingAmbiguityConfirmation = null
                if (amb != null) {
                    visionTracker.setAmbiguousCooldown(amb.candidate.id, durationMs = 60_000L)
                }
                voiceManager.isListeningPaused = true
                voiceManager.stopListening()
                binding.tvVoiceStatus.text = "Chạm để nói chuyện"
                eveWebView.setEmotion("idle")
                return@runOnUiThread
            }

            val person = visionTracker.activePerson
            val pronoun = person?.preferredPronoun ?: "Anh/chị"

            if (attempt == 1) {
                // Lần 1: Nhắc nhở người dùng bằng Local AI Agent chau chuốt
                speakScripted(
                    type = ScriptedSpeechType.SILENCE_REMINDER,
                    person = person,
                    params = mapOf("pronoun" to pronoun, "name" to (person?.name ?: ""))
                )
            } else {
                // Lần 2 (người dùng vẫn không nói gì):
                // Theo yêu cầu người dùng: Nếu người dùng không nói gì, tạm dừng lắng nghe VAD nếu vẫn còn khuôn mặt nhận diện,
                // không cần chào tạm biệt trong trường hợp ấy, chỉ cần im lặng thôi.
                voiceManager.isListeningPaused = true
                voiceManager.stopListening()
                voiceManager.resetNoSpeechAttempt()

                // Ẩn banner nhắc nhở
                mainHandler.removeCallbacks(bannerHideRunnable)
                binding.cardSpeechBanner.visibility = View.GONE

                // Hoàn toàn im lặng, không phát TTS tạm biệt, cập nhật trạng thái về idle
                binding.tvVoiceStatus.text = "Chạm để nói chuyện"
                eveWebView.setEmotion("idle")
            }
        }
    }

    // ==========================================
    // AI RESPONSE & AUTO-ENROLLMENT LOGIC
    // ==========================================

    private fun handleAiResponse(response: com.example.facedetector.network.N8nChatResponse, originalMessage: String = "") {
        if (response.sessionId != null) {
            currentSessionId = response.sessionId
        }

        // Tự động lưu từ điển phát âm mới nếu n8n trả về
        if (response.pronunciation != null) {
            dbHelper.savePronunciation(response.pronunciation.first, response.pronunciation.second)
            Log.d(TAG, "Learned pronunciation: ${response.pronunciation.first} -> ${response.pronunciation.second}")
        }

        val rawDetectedPerson = response.detectedPerson

        if (rawDetectedPerson != null) {
            // Kết hợp thông tin giới tính dự đoán từ thị giác (sessionPredictedGender) nếu câu nói chưa xác định được:
            var resolvedGender = rawDetectedPerson.gender
            var resolvedPronoun = rawDetectedPerson.preferredPronoun

            if (resolvedGender == "unknown" && !sessionPredictedGender.isNullOrBlank()) {
                resolvedGender = sessionPredictedGender!!
            }

            // Nếu đại từ chung chung ("Bạn") mà thị giác đã dự đoán Nam/Nữ thì tự động nâng cấp:
            if (resolvedPronoun == "Bạn" && !sessionPredictedGender.isNullOrBlank()) {
                resolvedPronoun = if (sessionPredictedGender == "male") "Anh" else "Chị"
            }

            val detectedPerson = rawDetectedPerson.copy(
                gender = resolvedGender,
                preferredPronoun = resolvedPronoun
            )

            // TRƯỜNG HỢP A: Người dùng chỉ cập nhật đại từ xưng hô (name rỗng, pronoun có giá trị)
            // Ví dụ người dùng nói: "Hãy gọi tôi là chú nhé", "xưng là chú đi", "mình là chú tiểu đây"
            if (detectedPerson.name.isBlank() && detectedPerson.preferredPronoun.isNotBlank()) {
                val newPronoun = detectedPerson.preferredPronoun
                val current = visionTracker.activePerson
                if (current != null) {
                    val updated = current.copy(
                        preferredPronoun = newPronoun,
                        lastSeenAt = System.currentTimeMillis()
                    )
                    dbHelper.upsertPerson(updated)
                    visionTracker.refreshCache()
                    visionTracker.setActivePersonManually(updated, hasFaceConfirmed = visionTracker.hasVisualFaceConfirmed)
                    binding.tvActivePerson.text = "👤 $newPronoun ${updated.name}"
                }
                if (response.replyText.isNotBlank()) {
                    speakAndShowBanner(response.replyText, response.emotion)
                } else {
                    val pName = current?.name ?: ""
                    speakScripted(
                        type = ScriptedSpeechType.PRONOUN_UPDATE,
                        person = current,
                        params = mapOf("newPronoun" to newPronoun, "name" to pName)
                    )
                }
                return
            }

            // TRƯỜNG HỢP B: Có tên người dùng
            val currentVisual = visionTracker.activePerson
            val isFaceCurrentlySeen = visionTracker.hasVisualFaceConfirmed && (visionTracker.latestFaceRect != null)

            // TRƯỜNG HỢP 2: Xung đột danh tính (Camera nhận diện Nam nhưng người nói lại xưng tên là Hoàng)
            if (isFaceCurrentlySeen && currentVisual != null && !detectedPerson.name.equals(currentVisual.name, ignoreCase = true)) {
                pendingConflict = PendingIdentityConflict(detectedPerson, currentVisual)
                val oldPronoun = currentVisual.preferredPronoun
                val oldName = currentVisual.name
                val newName = detectedPerson.name
                speakScripted(
                    type = ScriptedSpeechType.IDENTITY_CONFLICT_QUESTION,
                    person = currentVisual,
                    params = mapOf("oldPronoun" to oldPronoun, "oldName" to oldName, "newName" to newName)
                )
                return
            }

            // Lấy ảnh khuôn mặt và trích xuất vector khuôn mặt hiện tại từ camera
            val fullBmp = visionTracker.latestFullBitmap
            val faceRect = visionTracker.latestFaceRect
            val currentEmbedding: FloatArray? = if (fullBmp != null && faceRect != null) {
                val cropped = ImageUtils.cropFace(fullBmp, faceRect, visionTracker.latestEulerZ)
                if (cropped != null) faceNetModel.getFaceEmbedding(cropped) else null
            } else null

            val currentAvatarBase64: String? = if (fullBmp != null && faceRect != null) {
                val cropped = ImageUtils.cropFace(fullBmp, faceRect, visionTracker.latestEulerZ)
                if (cropped != null) {
                    val scaled = Bitmap.createScaledBitmap(cropped, 160, 160, true)
                    val stream = java.io.ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                    "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                } else null
            } else null

            // Tìm kiếm TẤT CẢ hồ sơ có cùng tên trong SQLite
            val existingList = dbHelper.findAllPeopleByName(detectedPerson.name)

            if (existingList.isEmpty() || currentEmbedding == null) {
                // Không có ai trùng tên trong DB HOẶC không có khuôn mặt trước camera
                var existing = existingList.firstOrNull()
                if (existing != null) {
                    // Cập nhật thông tin diff
                    val hasDiff = (detectedPerson.age != null && detectedPerson.age != existing.age) ||
                                  (detectedPerson.gender != "unknown" && detectedPerson.gender != existing.gender) ||
                                  (detectedPerson.preferredPronoun != existing.preferredPronoun) ||
                                  (detectedPerson.role != existing.role)
                    if (hasDiff) {
                        existing = existing.copy(
                            age = detectedPerson.age ?: existing.age,
                            gender = if (detectedPerson.gender != "unknown") detectedPerson.gender else existing.gender,
                            preferredPronoun = detectedPerson.preferredPronoun,
                            role = detectedPerson.role,
                            lastSeenAt = System.currentTimeMillis()
                        )
                        dbHelper.upsertPerson(existing)
                        visionTracker.refreshCache()
                    }
                    visionTracker.setActivePersonManually(existing, hasFaceConfirmed = false)
                    binding.tvTierBadge.text = "Thị giác: Chờ thấy mặt"
                    binding.tvActivePerson.text = "👤 ${existing.preferredPronoun} ${existing.name}"
                } else {
                    // Thêm mới hoàn toàn
                    val newPerson = PersonProfile(
                        id = "person_${System.currentTimeMillis()}",
                        name = detectedPerson.name,
                        age = detectedPerson.age,
                        gender = detectedPerson.gender,
                        preferredPronoun = detectedPerson.preferredPronoun,
                        role = detectedPerson.role,
                        avatarBase64 = currentAvatarBase64,
                        faceEmbeddings = if (currentEmbedding != null) listOf(currentEmbedding) else emptyList(),
                        createdAt = System.currentTimeMillis(),
                        lastSeenAt = System.currentTimeMillis()
                    )
                    dbHelper.upsertPerson(newPerson)
                    visionTracker.refreshCache()
                    visionTracker.setActivePersonManually(newPerson, hasFaceConfirmed = (currentEmbedding != null))
                    binding.tvTierBadge.text = if (currentEmbedding != null) "Thị giác: Tier 2 (Bám khung tiết kiệm)" else "Thị giác: Chờ thấy mặt"
                    binding.tvActivePerson.text = "👤 ${newPerson.preferredPronoun} ${newPerson.name}"
                    Log.d(TAG, "Enrolled new person profile into SQLite: ${newPerson.name} (gender=${newPerson.gender}, pronoun=${newPerson.preferredPronoun})")
                }
                if (response.replyText.isNotBlank()) {
                    speakAndShowBanner(response.replyText, response.emotion)
                } else {
                    val p = existing ?: dbHelper.findPersonByName(detectedPerson.name)
                    speakScripted(
                        type = ScriptedSpeechType.GREETING_KNOWN,
                        person = p,
                        prefixEmoji = "👋"
                    )
                }
                return
            }

            // ĐÃ CÓ người trùng tên trong SQLite và CÓ khuôn mặt trước camera:
            // Tính toán maxSim đối với từng ứng viên trùng tên
            val rankedCandidates = existingList.map { person ->
                val maxSim = person.faceEmbeddings.maxOfOrNull { emb ->
                    VectorMath.cosineSimilarity(currentEmbedding, emb)
                } ?: 0f
                DisambiguationCandidate(person, maxSim)
            }.sortedByDescending { it.maxSim }

            val bestMatch = rankedCandidates.first()

            if (bestMatch.maxSim >= 0.65f) {
                // maxSim >= 0.65f: Có nét tương đồng rõ rệt với người cũ -> Hỏi xác nhận bằng mốc thời gian lần gặp
                pendingDisambiguation = PendingDisambiguation(
                    candidates = rankedCandidates,
                    currentIndex = 0,
                    currentEmbedding = currentEmbedding,
                    currentAvatarBase64 = currentAvatarBase64,
                    detectedPerson = detectedPerson
                )
                val targetPerson = bestMatch.person
                val timeStr = formatRelativeTimeVi(targetPerson.lastSeenAt)
                speakScripted(
                    type = ScriptedSpeechType.DISAMBIGUATION_QUESTION,
                    person = targetPerson,
                    params = mapOf("timeStr" to timeStr)
                )
                return
            } else {
                // maxSim < 0.65f: Khuôn mặt hoàn toàn khác -> Tạo danh bạ mới cùng tên mà không cần hỏi lại tên
                val newPerson = PersonProfile(
                    id = "person_${System.currentTimeMillis()}",
                    name = detectedPerson.name,
                    age = detectedPerson.age,
                    gender = detectedPerson.gender,
                    preferredPronoun = detectedPerson.preferredPronoun,
                    role = detectedPerson.role,
                    avatarBase64 = currentAvatarBase64,
                    faceEmbeddings = listOf(currentEmbedding),
                    createdAt = System.currentTimeMillis(),
                    lastSeenAt = System.currentTimeMillis()
                )
                dbHelper.upsertPerson(newPerson)
                visionTracker.refreshCache()
                visionTracker.setActivePersonManually(newPerson, hasFaceConfirmed = true)
                binding.tvTierBadge.text = "Thị giác: Tier 2 (Bám khung tiết kiệm)"
                binding.tvActivePerson.text = "👤 ${newPerson.preferredPronoun} ${newPerson.name}"
                if (response.replyText.isNotBlank()) {
                    speakAndShowBanner(response.replyText, response.emotion)
                } else {
                    speakScripted(
                        type = ScriptedSpeechType.DISAMBIGUATION_NEW_PERSON,
                        person = newPerson,
                        prefixEmoji = "👋"
                    )
                }
                return
            }
        }

        // Xử lý Lệnh Hệ Thống: Thoát ứng dụng (logout)
        if (response.action == "logout") {
            Log.d(TAG, "Triggering logout action: Stopping mic and exiting to Home screen.")
            voiceManager.stopListening()
            voiceManager.isListeningPaused = true

            var hasExited = false
            val exitApp = {
                if (!hasExited) {
                    hasExited = true
                    try {
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(homeIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to launch HOME intent: ${e.message}")
                    }
                    finishAffinity()
                }
            }

            // Fallback timer: Đảm bảo app chắc chắn thoát sau 4.5s ngay cả khi TTS gặp sự cố
            mainHandler.postDelayed({ exitApp() }, 4500)

            speakAndShowBanner(response.replyText, response.emotion) {
                exitApp()
            }
            return
        }

        // Xử lý Lệnh Hệ Thống: Cập nhật nhận diện khuôn mặt (update-face-detect)
        if (response.action == "update-face-detect") {
            val active = visionTracker.activePerson
            if (active != null) {
                val captureResult = visionTracker.captureCurrentFaceForPerson(
                    name = active.name,
                    preferredPronoun = active.preferredPronoun,
                    gender = active.gender,
                    role = active.role
                )
                if (captureResult.success) {
                    val p = captureResult.person ?: active
                    visionTracker.setActivePersonManually(p, hasFaceConfirmed = true)
                    speakAndShowBanner(response.replyText, response.emotion)
                } else {
                    speakScripted(
                        type = ScriptedSpeechType.FACE_NOT_CLEAR,
                        person = active
                    )
                }
            } else {
                speakScripted(
                    type = ScriptedSpeechType.FACE_NO_ONE
                )
            }
            return
        }

        // Phát câu trả lời từ Webhook kèm theo emotion tương ứng
        speakAndShowBanner(response.replyText, response.emotion)
    }

    private fun speakScripted(
        type: ScriptedSpeechType,
        person: PersonProfile? = null,
        params: Map<String, String> = emptyMap(),
        prefixEmoji: String? = null,
        onDone: (() -> Unit)? = null
    ) {
        lifecycleScope.launch {
            val polished = LocalAiAgentService.polishSpeech(
                type = type,
                person = person,
                params = params,
                dbHelper = dbHelper
            )
            runOnUiThread {
                val fullText = if (prefixEmoji != null) "$prefixEmoji ${polished.text}" else polished.text
                speakAndShowBanner(fullText, polished.emotion, onDone)
            }
        }
    }

    private fun speakAndShowBanner(text: String, emotion: String, onDone: (() -> Unit)? = null) {
        mainHandler.removeCallbacks(bannerHideRunnable)

        eveWebView.setEmotion(emotion)
        binding.tvSpeechText.text = text
        binding.cardSpeechBanner.visibility = View.VISIBLE

        voiceManager.speak(text) {
            val nonInterruptible = setOf(
                "sleeping", "wave-left", "wave-right", "spin-360", "scan",
                "directive-plant", "plant", "blaster", "curious", "love",
                "shrug", "clap", "jet-boost", "boost"
            )
            if (!nonInterruptible.contains(eveWebView.currentEmotion)) {
                eveWebView.setEmotion("idle")
            }
            onDone?.invoke()
        }

        // Ẩn banner sau 7s
        mainHandler.postDelayed(bannerHideRunnable, 7000)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        faceDetector.close()
        faceNetModel.close()
        genderClassifier.close()
        voiceManager.destroy()
        dbHelper.close()
    }
}