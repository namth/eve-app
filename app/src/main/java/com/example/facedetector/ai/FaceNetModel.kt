package com.example.facedetector.ai

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class FaceNetModel(context: Context) {

    companion object {
        private const val MODEL_NAME = "mobilefacenet.tflite"
        const val INPUT_IMAGE_SIZE = 112
        const val EMBEDDING_SIZE = 192

        // Normalization parameters for MobileFaceNet
        private const val IMAGE_MEAN = 127.5f
        private const val IMAGE_STD = 128.0f
    }

    private val interpreter: Interpreter

    init {
        val modelBuffer = loadModelFile(context, MODEL_NAME)
        val options = Interpreter.Options().apply {
            setNumThreads(4) // Use multi-threading for fast mobile CPU inference
        }
        interpreter = Interpreter(modelBuffer, options)
    }

    /**
     * Extracts a 192-dimensional L2-normalized embedding vector from a cropped face Bitmap.
     */
    fun getFaceEmbedding(faceBitmap: Bitmap): FloatArray {
        val resizedBitmap = Bitmap.createScaledBitmap(faceBitmap, INPUT_IMAGE_SIZE, INPUT_IMAGE_SIZE, true)
        val byteBuffer = convertBitmapToByteBuffer(resizedBitmap)

        // Output buffer for [1, 192]
        val outputEmbeddings = Array(1) { FloatArray(EMBEDDING_SIZE) }
        interpreter.run(byteBuffer, outputEmbeddings)

        // L2 normalize the embedding vector for accurate cosine similarity
        return VectorMath.l2Normalize(outputEmbeddings[0])
    }

    /**
     * Converts a 112x112 Bitmap to a normalized ByteBuffer formatted as [1, 112, 112, 3] Float32.
     */
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
                // MobileFaceNet expects RGB normalized between [-1.0, 1.0]
                val r = (((value shr 16) and 0xFF) - IMAGE_MEAN) / IMAGE_STD
                val g = (((value shr 8) and 0xFF) - IMAGE_MEAN) / IMAGE_STD
                val b = ((value and 0xFF) - IMAGE_MEAN) / IMAGE_STD

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
        interpreter.close()
    }
}
