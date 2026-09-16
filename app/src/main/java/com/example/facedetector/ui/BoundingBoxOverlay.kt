package com.example.facedetector.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class BoundingBoxOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class FaceBox(
        val rect: Rect,
        val label: String,
        val isRecognized: Boolean
    )

    private var currentFaceBox: FaceBox? = null
    private var sourceImageWidth: Int = 0
    private var sourceImageHeight: Int = 0
    private var isFrontFacing: Boolean = false

    private val recognizedBoxPaint = Paint().apply {
        color = Color.parseColor("#4CAF50") // Green for known
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val unknownBoxPaint = Paint().apply {
        color = Color.parseColor("#FF5722") // Deep orange for unknown
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val textBackgroundPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 44f
        isFakeBoldText = true
        isAntiAlias = true
    }

    /**
     * Updates face detection result to draw.
     */
    fun updateFace(
        rect: Rect?,
        label: String?,
        isRecognized: Boolean,
        imageWidth: Int,
        imageHeight: Int,
        isFrontCamera: Boolean = false
    ) {
        if (rect == null || label == null) {
            currentFaceBox = null
        } else {
            currentFaceBox = FaceBox(rect, label, isRecognized)
            sourceImageWidth = imageWidth
            sourceImageHeight = imageHeight
            isFrontFacing = isFrontCamera
        }
        postInvalidate()
    }

    fun clear() {
        currentFaceBox = null
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val box = currentFaceBox ?: return
        if (sourceImageWidth == 0 || sourceImageHeight == 0) return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // Calculate scale to fit/fill preview
        val scaleX = viewWidth / sourceImageWidth.toFloat()
        val scaleY = viewHeight / sourceImageHeight.toFloat()

        val rawRect = box.rect
        val left: Float
        val right: Float

        if (isFrontFacing) {
            // Mirror horizontally for front camera
            left = viewWidth - (rawRect.right.toFloat() * scaleX)
            right = viewWidth - (rawRect.left.toFloat() * scaleX)
        } else {
            left = rawRect.left.toFloat() * scaleX
            right = rawRect.right.toFloat() * scaleX
        }

        val top = rawRect.top.toFloat() * scaleY
        val bottom = rawRect.bottom.toFloat() * scaleY

        val mappedRectF = RectF(left, top, right, bottom)
        val paint = if (box.isRecognized) recognizedBoxPaint else unknownBoxPaint

        // Draw bounding box
        canvas.drawRoundRect(mappedRectF, 16f, 16f, paint)

        // Draw label background pill
        val label = box.label
        val textWidth = textPaint.measureText(label)
        val textHeight = textPaint.descent() - textPaint.ascent()
        val padding = 16f

        val bgTop = max(0f, top - textHeight - padding * 2)
        val bgBottom = top
        val bgRight = left + textWidth + padding * 2

        textBackgroundPaint.color = if (box.isRecognized) Color.parseColor("#E62E7D32") else Color.parseColor("#E6D84315")
        canvas.drawRoundRect(RectF(left, bgTop, bgRight, bgBottom), 12f, 12f, textBackgroundPaint)

        // Draw text
        canvas.drawText(label, left + padding, bgBottom - padding, textPaint)
    }
}
