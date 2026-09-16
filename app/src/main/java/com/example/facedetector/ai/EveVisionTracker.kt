package com.example.facedetector.ai

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.example.facedetector.data.EveDatabaseHelper
import com.example.facedetector.data.PersonProfile
import com.google.mlkit.vision.face.Face
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.abs

enum class VisionTier {
    TIER1_RECOGNITION, // Full TFLite embedding inference to find identity
    TIER2_TRACKING     // Lightweight bounding box tracking (No TFLite inference)
}

class EveVisionTracker(
    private val faceNetModel: FaceNetModel,
    private val genderClassifier: GenderClassifier,
    private val dbHelper: EveDatabaseHelper,
    private val listener: Listener
) {

    companion object {
        private const val TAG = "EveVisionTracker"
        private const val REQUIRED_CONFIRMATION_FRAMES = 2
        private const val UNKNOWN_GREET_COOLDOWN_MS = 30000L // 30s cooldown giữa các lần chào người lạ
        private const val BACKGROUND_LEARN_INTERVAL_MS = 2500L // Tối thiểu 2.5s giữa các lần học ngầm góc mặt
    }

    interface Listener {
        fun onPersonGreeted(person: PersonProfile, similarity: Float)
        fun onPersonDeparted(person: PersonProfile)
        fun onFaceTracked(person: PersonProfile, box: Rect)
        fun onUnknownFaceDetected(box: Rect)
        fun onUnknownPersonGreetable(box: Rect, gender: String, croppedFace: Bitmap)
        fun onNoFace()
    }

    var activePerson: PersonProfile? = null
        private set

    var currentTier: VisionTier = VisionTier.TIER1_RECOGNITION
        private set

    var hasVisualFaceConfirmed: Boolean = false
        private set

    private var lastSeenTimestamp: Long = 0L
    private val debounceGracePeriodMs: Long = 2500L // 2.5s grace period để phản hồi khi rời khung hình

    private var lastDepartedPersonId: String? = null
    private var departureCooldownUntil: Long = 0L
    private var activeTrackingId: Int? = null

    // Hysteresis & Anti-flicker fields
    private var pendingPersonMatch: PersonProfile? = null
    private var pendingMatchSim: Float = 0f
    private var consecutiveKnownFrames: Int = 0
    private var lastTrackedBox: Rect? = null
    private var trackingMismatchCount: Int = 0

    // Unknown person tracking & proactive greeting
    private var unknownFaceFirstSeenTime: Long = 0L
    private var consecutiveUnknownFrames: Int = 0
    private var hasGreetedUnknownCurrentSession: Boolean = false
    private var lastUnknownGreetedTimestamp: Long = 0L

    // Tier 2 Background Angle-Aware Auto-Learning
    private var lastBackgroundLearnTimestamp: Long = 0L
    private var lastLearnedEulerY: Float = 0f
    private var lastLearnedEulerZ: Float = 0f
    @Volatile
    private var isBackgroundLearningBusy: Boolean = false

    // Cache latest face bitmap, rect & roll angle for instant auto-enrollment
    @Volatile
    var latestFullBitmap: Bitmap? = null
        private set

    @Volatile
    var latestFaceRect: Rect? = null
        private set

    @Volatile
    var latestEulerZ: Float = 0f
        private set

    private var cachedPeople: List<PersonProfile> = emptyList()

    init {
        refreshCache()
    }

    fun refreshCache() {
        cachedPeople = dbHelper.getAllPeople()
    }

    /**
     * Called on each camera frame after ML Kit detects faces.
     */
    @Synchronized
    fun processFrame(faces: List<Face>, fullBitmap: Bitmap, imageWidth: Int, imageHeight: Int) {
        latestFullBitmap = fullBitmap

        if (faces.isEmpty()) {
            latestFaceRect = null
            latestEulerZ = 0f
            pendingPersonMatch = null
            consecutiveKnownFrames = 0
            unknownFaceFirstSeenTime = 0L
            consecutiveUnknownFrames = 0
            listener.onNoFace()
            return
        }

        // Pick largest face in frame as primary subject
        val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return
        latestFaceRect = primaryFace.boundingBox
        latestEulerZ = primaryFace.headEulerAngleZ

        val now = System.currentTimeMillis()

        when (currentTier) {
            VisionTier.TIER2_TRACKING -> {
                // Tier 2: Simple tracking mode with Spatial Continuity (IoU)
                val currentTrackingId = primaryFace.trackingId
                val currentBox = primaryFace.boundingBox
                val lastBox = lastTrackedBox

                val isSameTrackingId = (currentTrackingId != null && activeTrackingId != null && currentTrackingId == activeTrackingId)
                val iou = if (lastBox != null) calculateIoU(lastBox, currentBox) else 1.0f

                // Spatial IoU continuity: Nếu trackingId bị ML Kit đổi nhưng hộp mặt ở cùng vị trí (IoU >= 0.45)
                // thì vẫn tiếp tục giữ activePerson, không bị văng ra!
                if (!isSameTrackingId && iou < 0.45f) {
                    trackingMismatchCount++
                    if (trackingMismatchCount >= 3) {
                        // Người khác xuất hiện hoặc đã di chuyển xa
                        trackingMismatchCount = 0
                        currentTier = VisionTier.TIER1_RECOGNITION
                        activePerson = null
                        activeTrackingId = null
                        hasVisualFaceConfirmed = false
                        lastTrackedBox = null
                        return
                    }
                } else {
                    trackingMismatchCount = 0
                    if (currentTrackingId != null) {
                        activeTrackingId = currentTrackingId
                    }
                }

                lastSeenTimestamp = now
                hasVisualFaceConfirmed = true
                lastTrackedBox = currentBox

                val person = activePerson
                if (person != null) {
                    listener.onFaceTracked(person, currentBox)

                    // TIER 2 BACKGROUND ANGLE-AWARE AUTO-LEARNING (Học ngầm các góc mặt khi đang trò chuyện)
                    if (person.faceEmbeddings.size < 9 && !isBackgroundLearningBusy && (now - lastBackgroundLearnTimestamp >= BACKGROUND_LEARN_INTERVAL_MS)) {
                        val yaw = primaryFace.headEulerAngleY
                        val roll = primaryFace.headEulerAngleZ
                        val deltaYaw = abs(yaw - lastLearnedEulerY)
                        val deltaRoll = abs(roll - lastLearnedEulerZ)

                        // Kích hoạt khi góc mặt quay/nghiêng > 12 độ hoặc định kỳ sau 5 giây
                        if (deltaYaw >= 12f || deltaRoll >= 12f || (now - lastBackgroundLearnTimestamp >= 5000L)) {
                            triggerBackgroundAngleLearning(person, fullBitmap, currentBox, roll, yaw)
                        }
                    }
                }
            }

            VisionTier.TIER1_RECOGNITION -> {
                // Tier 1: Advanced recognition mode with Hysteresis & Temporal Smoothing
                val croppedFace = ImageUtils.cropFace(fullBitmap, primaryFace.boundingBox, primaryFace.headEulerAngleZ)
                if (croppedFace != null) {
                    val embedding = faceNetModel.getFaceEmbedding(croppedFace)
                    val match = VectorMath.findBestPersonMatch(embedding, cachedPeople, threshold = VectorMath.SIMILARITY_ENTRY_THRESHOLD)

                    if (match != null) {
                        // Reset bộ đếm người lạ
                        unknownFaceFirstSeenTime = 0L
                        consecutiveUnknownFrames = 0

                        // Cooldown check: Tránh nhận diện và chào lại ngay lập tức khi người đó vừa rời đi
                        if (match.person.id == lastDepartedPersonId && now < departureCooldownUntil) {
                            return
                        }

                        // TEMPORAL SMOOTHING: Cần tối thiểu 2 frame liên tiếp xác nhận cùng 1 người
                        if (pendingPersonMatch?.id == match.person.id) {
                            consecutiveKnownFrames++
                        } else {
                            pendingPersonMatch = match.person
                            pendingMatchSim = match.similarity
                            consecutiveKnownFrames = 1
                        }

                        if (consecutiveKnownFrames >= REQUIRED_CONFIRMATION_FRAMES) {
                            consecutiveKnownFrames = 0
                            var recognizedPerson = match.person

                            // Auto-adaptive learning: Nếu nhận diện chắc chắn và chưa đủ 9 vector
                            if (match.similarity >= 0.82f && recognizedPerson.faceEmbeddings.size < 9) {
                                val maxGallerySim = recognizedPerson.faceEmbeddings.maxOfOrNull {
                                    VectorMath.cosineSimilarity(embedding, it)
                                } ?: 0f
                                if (maxGallerySim < 0.94f) {
                                    val updatedEmbeddings = recognizedPerson.faceEmbeddings.toMutableList().apply {
                                        add(embedding)
                                    }
                                    val updatedPerson = recognizedPerson.copy(
                                        faceEmbeddings = updatedEmbeddings,
                                        lastSeenAt = now
                                    )
                                    dbHelper.upsertPerson(updatedPerson)
                                    recognizedPerson = updatedPerson
                                    refreshCache()
                                    Log.d(TAG, "Tier 1 Auto-learned initial face angle for ${updatedPerson.name}: ${updatedEmbeddings.size}/9")
                                }
                            }

                            // Found registered person
                            activePerson = recognizedPerson
                            activeTrackingId = primaryFace.trackingId
                            lastTrackedBox = primaryFace.boundingBox
                            currentTier = VisionTier.TIER2_TRACKING
                            lastSeenTimestamp = now
                            hasVisualFaceConfirmed = true
                            lastDepartedPersonId = null
                            dbHelper.updateLastSeen(recognizedPerson.id)
                            listener.onPersonGreeted(recognizedPerson, match.similarity)
                        }
                    } else {
                        // Không khớp với người quen ở ngưỡng Entry (0.78)
                        pendingPersonMatch = null
                        consecutiveKnownFrames = 0

                        // Tính maxSim với toàn bộ kho vector
                        val maxAnySim = cachedPeople.flatMap { it.faceEmbeddings }.maxOfOrNull {
                            VectorMath.cosineSimilarity(embedding, it)
                        } ?: 0f

                        listener.onUnknownFaceDetected(primaryFace.boundingBox)

                        // Nếu maxSim < HOLD_THRESHOLD (0.65) -> Thực sự là NGƯỜI LẠ
                        if (maxAnySim < VectorMath.SIMILARITY_HOLD_THRESHOLD) {
                            if (unknownFaceFirstSeenTime == 0L) {
                                unknownFaceFirstSeenTime = now
                            }
                            consecutiveUnknownFrames++

                            val stableDuration = now - unknownFaceFirstSeenTime
                            val isCoolDownOver = (now - lastUnknownGreetedTimestamp > UNKNOWN_GREET_COOLDOWN_MS)

                            // Người lạ đứng ổn định >= 1.2s (hoặc >= 4 frames) và qua cooldown
                            if (stableDuration >= 1200L && consecutiveUnknownFrames >= 4 && !hasGreetedUnknownCurrentSession && isCoolDownOver) {
                                hasGreetedUnknownCurrentSession = true
                                lastUnknownGreetedTimestamp = now

                                val genderResult = genderClassifier.predictGender(croppedFace)
                                listener.onUnknownPersonGreetable(primaryFace.boundingBox, genderResult.gender, croppedFace)
                            }
                        } else {
                            // VÙNG XÁM (0.65 <= maxAnySim < 0.78): Giữ nguyên, không vội kích hoạt chào người lạ
                            unknownFaceFirstSeenTime = 0L
                            consecutiveUnknownFrames = 0
                        }
                    }
                }
            }
        }
    }

    /**
     * Chạy ngầm việc crop và phân tích góc mặt mới trong khi trò chuyện ở Tier 2.
     * Hoàn toàn không làm chậm camera preview hoặc UI thread.
     */
    private fun triggerBackgroundAngleLearning(
        person: PersonProfile,
        fullBitmap: Bitmap,
        box: Rect,
        roll: Float,
        yaw: Float
    ) {
        if (isBackgroundLearningBusy) return
        isBackgroundLearningBusy = true
        lastBackgroundLearnTimestamp = System.currentTimeMillis()

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val cropped = ImageUtils.cropFace(fullBitmap, box, roll)
                if (cropped != null) {
                    val embedding = faceNetModel.getFaceEmbedding(cropped)
                    val embeddings = person.faceEmbeddings

                    // 1. Kiểm tra độ tương đồng với người hiện tại (phải >= 0.75 để đảm bảo đúng người)
                    val maxPersonSim = embeddings.maxOfOrNull { VectorMath.cosineSimilarity(embedding, it) } ?: 0f

                    // 2. Kiểm tra độ đa dạng: phải < 0.94 để chắc chắn đây là góc mặt mới có giá trị
                    if (maxPersonSim in 0.75f..0.94f && embeddings.size < 9) {
                        val updatedList = embeddings.toMutableList().apply { add(embedding) }
                        val updatedPerson = person.copy(
                            faceEmbeddings = updatedList,
                            lastSeenAt = System.currentTimeMillis()
                        )
                        dbHelper.upsertPerson(updatedPerson)
                        synchronized(this@EveVisionTracker) {
                            if (activePerson?.id == person.id) {
                                activePerson = updatedPerson
                            }
                        }
                        refreshCache()
                        lastLearnedEulerY = yaw
                        lastLearnedEulerZ = roll
                        Log.d(TAG, "Tier 2 Auto-learned face angle while chatting for ${updatedPerson.name}: ${updatedList.size}/9 (yaw=${yaw.toInt()}, roll=${roll.toInt()})")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Background angle learning error: ${e.message}")
            } finally {
                isBackgroundLearningBusy = false
            }
        }
    }

    private fun calculateIoU(box1: Rect, box2: Rect): Float {
        val intersectionLeft = maxOf(box1.left, box2.left)
        val intersectionTop = maxOf(box1.top, box2.top)
        val intersectionRight = minOf(box1.right, box2.right)
        val intersectionBottom = minOf(box1.bottom, box2.bottom)

        if (intersectionRight <= intersectionLeft || intersectionBottom <= intersectionTop) {
            return 0f
        }

        val intersectionArea = (intersectionRight - intersectionLeft).toLong() * (intersectionBottom - intersectionTop).toLong()
        val box1Area = box1.width().toLong() * box1.height().toLong()
        val box2Area = box2.width().toLong() * box2.height().toLong()
        val unionArea = box1Area + box2Area - intersectionArea

        return if (unionArea > 0) intersectionArea.toFloat() / unionArea.toFloat() else 0f
    }

    /**
     * Checks if the active person has left the frame longer than 2.5 seconds.
     */
    @Synchronized
    fun checkTimeout() {
        val person = activePerson ?: return
        if (!hasVisualFaceConfirmed) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastSeenTimestamp > debounceGracePeriodMs) {
            // Person was visually present and has now stepped out of camera view
            lastDepartedPersonId = person.id
            departureCooldownUntil = now + 5000L // 5s cooldown
            activePerson = null
            activeTrackingId = null
            lastTrackedBox = null
            hasVisualFaceConfirmed = false
            hasGreetedUnknownCurrentSession = false
            currentTier = VisionTier.TIER1_RECOGNITION
            listener.onPersonDeparted(person)
        }
    }

    data class AutoCaptureResult(
        val success: Boolean,
        val person: PersonProfile? = null,
        val reason: String? = null
    )

    /**
     * Captures current face from camera and saves/updates profile with face embedding and avatar.
     * Used when the user introduces their name.
     */
    @Synchronized
    fun captureCurrentFaceForPerson(
        name: String,
        preferredPronoun: String = "Bạn",
        gender: String = "unknown",
        role: String = "friend"
    ): AutoCaptureResult {
        val fullBmp = latestFullBitmap
        val faceRect = latestFaceRect

        if (fullBmp == null || faceRect == null) {
            return AutoCaptureResult(
                success = false,
                reason = "NO_FACE_IN_VIEW"
            )
        }

        val cropped = ImageUtils.cropFace(fullBmp, faceRect, latestEulerZ)
            ?: return AutoCaptureResult(success = false, reason = "CROP_FAILED")

        // 1. Extract 192D vector
        val embedding = faceNetModel.getFaceEmbedding(cropped)

        // 2. Compress cropped face into Base64 avatar
        val avatarBase64 = bitmapToBase64(cropped)

        // 3. Check if person already exists in SQLite
        val existing = dbHelper.findPersonByName(name)
        val finalPerson = if (existing != null) {
            val updatedEmbeddings = existing.faceEmbeddings.toMutableList()
            val maxSim = updatedEmbeddings.maxOfOrNull { VectorMath.cosineSimilarity(embedding, it) } ?: 0f
            if (maxSim < 0.95f && updatedEmbeddings.size < 9) {
                updatedEmbeddings.add(embedding)
            } else if (updatedEmbeddings.isEmpty()) {
                updatedEmbeddings.add(embedding)
            }
            val updated = existing.copy(
                faceEmbeddings = updatedEmbeddings,
                avatarBase64 = avatarBase64,
                lastSeenAt = System.currentTimeMillis()
            )
            dbHelper.upsertPerson(updated)
            updated
        } else {
            val newPerson = PersonProfile(
                id = "person_${System.currentTimeMillis()}",
                name = name,
                gender = gender,
                preferredPronoun = preferredPronoun,
                role = role,
                avatarBase64 = avatarBase64,
                faceEmbeddings = listOf(embedding),
                createdAt = System.currentTimeMillis(),
                lastSeenAt = System.currentTimeMillis()
            )
            dbHelper.upsertPerson(newPerson)
            newPerson
        }

        refreshCache()
        activePerson = finalPerson
        currentTier = VisionTier.TIER2_TRACKING
        lastSeenTimestamp = System.currentTimeMillis()
        hasVisualFaceConfirmed = true
        hasGreetedUnknownCurrentSession = true

        return AutoCaptureResult(success = true, person = finalPerson)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(bitmap, 160, 160, true)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, stream)
        val byteArray = stream.toByteArray()
        return "data:image/jpeg;base64," + Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    fun setActivePersonManually(person: PersonProfile, hasFaceConfirmed: Boolean = false) {
        activePerson = person
        currentTier = VisionTier.TIER2_TRACKING
        lastSeenTimestamp = System.currentTimeMillis()
        hasVisualFaceConfirmed = hasFaceConfirmed
        hasGreetedUnknownCurrentSession = true
    }

    fun reset() {
        activePerson = null
        activeTrackingId = null
        lastTrackedBox = null
        hasVisualFaceConfirmed = false
        hasGreetedUnknownCurrentSession = false
        currentTier = VisionTier.TIER1_RECOGNITION
        lastSeenTimestamp = 0L
    }
}
