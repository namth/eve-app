package com.example.facedetector.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.facedetector.MainActivity
import com.example.facedetector.R
import com.example.facedetector.data.EveDatabaseHelper
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

class EveFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "EveFcmService"
        const val CHANNEL_ID = "eve_notifications_channel"
        const val CHANNEL_NAME = "EVE AI Assistant"
        const val EXTRA_FROM_NOTIFICATION = "extra_from_notification"

        var onNotificationReceivedListener: ((com.example.facedetector.data.NotificationItem) -> Unit)? = null
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM Token received: $token")
        try {
            val dbHelper = EveDatabaseHelper(this)
            dbHelper.setFcmToken(token)
            dbHelper.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving FCM token: ${e.message}", e)
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM Message received from: ${remoteMessage.from}")

        // 1. Trích xuất thông tin tiêu đề và nội dung
        var title = remoteMessage.notification?.title
        var body = remoteMessage.notification?.body

        val dataMap = remoteMessage.data
        val dataJson = if (dataMap.isNotEmpty()) JSONObject(dataMap as Map<*, *>).toString() else null

        if (title.isNullOrBlank() && dataMap.containsKey("title")) {
            title = dataMap["title"]
        }
        val detailedDataBody = dataMap["content"] ?: dataMap["detail"] ?: dataMap["details"]
            ?: dataMap["text"] ?: dataMap["message"] ?: dataMap["body"]

        if (body.isNullOrBlank()) {
            body = detailedDataBody
        } else if (!detailedDataBody.isNullOrBlank() && body.contains("thông báo mới từ hệ thống", ignoreCase = true)) {
            // Nếu body chỉ là câu chung chung mặc định của FCM, ưu tiên lấy nội dung chi tiết từ data
            body = detailedDataBody
        }

        val finalTitle = title ?: "Thông báo từ EVE AI"
        val finalBody = body ?: "Bạn có một thông báo mới từ hệ thống."
        val messageId = remoteMessage.messageId ?: "fcm_${System.currentTimeMillis()}"

        val notifItem = com.example.facedetector.data.NotificationItem(
            id = messageId,
            title = finalTitle,
            body = finalBody,
            data = dataJson,
            isRead = false,
            receivedAt = System.currentTimeMillis()
        )

        // 2. Lưu thông báo vào SQLite (status: is_read = 0)
        try {
            val dbHelper = EveDatabaseHelper(this)
            dbHelper.insertNotification(
                id = messageId,
                title = finalTitle,
                body = finalBody,
                dataJson = dataJson
            )
            dbHelper.close()
            Log.d(TAG, "Saved notification to SQLite: ID=$messageId, title=$finalTitle")

            // Bắn callback báo cho MainActivity ngay lập tức nếu app đang mở
            onNotificationReceivedListener?.invoke(notifItem)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving notification to DB: ${e.message}", e)
        }

        // 3. Hiển thị thông báo trên khay hệ thống (Status Bar)
        showSystemNotification(finalTitle, finalBody, messageId.hashCode())
    }

    private fun showSystemNotification(title: String, body: String, notificationId: Int) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_FROM_NOTIFICATION, true)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        createNotificationChannel()

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notificationBuilder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Kênh nhận thông báo từ n8n cho EVE Voice Assistant"
                    enableLights(true)
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }
}
