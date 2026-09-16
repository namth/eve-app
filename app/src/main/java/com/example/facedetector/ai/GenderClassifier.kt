package com.example.facedetector.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp

data class GenderResult(
    val gender: String, // "male" or "female"
    val confidence: Float,
    val maleScore: Float,
    val femaleScore: Float
)

class GenderClassifier(context: Context) {

    companion object {
        private const val TAG = "GenderClassifier"
        private const val MODEL_NAME = "model_gender_q.tflite"
        const val INPUT_IMAGE_SIZE = 128
    }

    private var interpreter: Interpreter? = null

    init {
        try {
            val modelBuffer = loadModelFile(context, MODEL_NAME)
            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }
            interpreter = Interpreter(modelBuffer, options)
            Log.d(TAG, "GenderClassifier initialized successfully with $MODEL_NAME")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load gender classification model: ${e.message}", e)
        }
    }

    /**
     * Classifies the gender of a cropped face bitmap.
     * Returns GenderResult("male" | "female", confidence).
     */
    @Synchronized
    fun predictGender(faceBitmap: Bitmap): GenderResult {
        val interp = interpreter ?: return GenderResult("unknown", 0.5f, 0.5f, 0.5f)

        val resizedBitmap = Bitmap.createScaledBitmap(faceBitmap, INPUT_IMAGE_SIZE, INPUT_IMAGE_SIZE, true)
        val byteBuffer = convertBitmapToByteBuffer(resizedBitmap)

        // Model output shape is [1, 2]: index 0 = Male, index 1 = Female
        val outputScores = Array(1) { FloatArray(2) }
        interp.run(byteBuffer, outputScores)

        val rawMale = outputScores[0][0]
        val rawFemale = outputScores[0][1]

        // Softmax calculation for normalized probabilities
        val maxVal = maxOf(rawMale, rawFemale)
        val expMale = exp((rawMale - maxVal).toDouble()).toFloat()
        val expFemale = exp((rawFemale - maxVal).toDouble()).toFloat()
        val sumExp = expMale + expFemale

        val probMale = if (sumExp > 0f) expMale / sumExp else 0.5f
        val probFemale = if (sumExp > 0f) expFemale / sumExp else 0.5f

        val isMale = probMale >= probFemale
        val detectedGender = if (isMale) "male" else "female"
        val confidence = if (isMale) probMale else probFemale

        Log.d(TAG, "Gender predicted: $detectedGender (confidence=${(confidence * 100).toInt()}%, male=${probMale}, female=${probFemale})")

        return GenderResult(
            gender = detectedGender,
            confidence = confidence,
            maleScore = probMale,
            femaleScore = probFemale
        )
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val imgData = ByteBuffer.allocateDirect(1 * INPUT_IMAGE_SIZE * INPUT_IMAGE_SIZE * 3 * 4)
        imgData.order(ByteOrder.nativeOrder())
        imgData.rewind()

        val intValues = IntArray(INPUT_IMAGE_SIZE * INPUT_IMAGE_SIZE)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until INPUT_IMAGE_SIZE) {
            for (j in 0 until INPUT_IMAGE_SIZE) {
                val value = intValues[pixel++]
                // Model expects RGB normalized between [0.0, 1.0] (div by 255.0f)
                val r = ((value shr 16) and 0xFF) / 255.0f
                val g = ((value shr 8) and 0xFF) / 255.0f
                val b = (value and 0xFF) / 255.0f

                imgData.putFloat(r)
                imgData.putFloat(g)
                imgData.putFloat(b)
            }
        }
        return imgData
    }

    private fun loadModelFile(context: Context, modelPath: String): ByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
