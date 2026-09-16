package com.example.facedetector.ai

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ImageUtils {

    /**
     * Converts CameraX ImageProxy to an oriented Bitmap.
     */
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val bitmap = imageProxy.toBitmap()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        return if (rotationDegrees != 0) {
            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }

    /**
     * Safely crops the face area from full-frame Bitmap using ML Kit's bounding box.
     * Applies safe padding so chin and forehead are fully included.
     * Optionally aligns face upright by rotating counter to rollAngle (Euler Z).
     */
    fun cropFace(bitmap: Bitmap, boundingBox: Rect, rollAngle: Float = 0f): Bitmap? {
        val width = bitmap.width
        val height = bitmap.height

        // Add 12% margin around the face bounding box so rotated corners don't clip forehead or chin
        val marginX = (boundingBox.width() * 0.12f).toInt()
        val marginY = (boundingBox.height() * 0.12f).toInt()

        val left = max(0, boundingBox.left - marginX)
        val top = max(0, boundingBox.top - marginY)
        val right = min(width, boundingBox.right + marginX)
        val bottom = min(height, boundingBox.bottom + marginY)

        val cropWidth = right - left
        val cropHeight = bottom - top

        if (cropWidth <= 0 || cropHeight <= 0) return null

        return try {
            if (abs(rollAngle) > 2.5f) {
                val matrix = Matrix().apply {
                    postRotate(-rollAngle)
                }
                Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight, matrix, true)
            } else {
                Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
            }
        } catch (e: Exception) {
            null
        }
    }
}
