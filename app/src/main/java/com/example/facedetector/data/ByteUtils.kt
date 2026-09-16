package com.example.facedetector.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

object ByteUtils {
    /**
     * Converts a FloatArray to ByteArray (BLOB) for SQLite storage.
     */
    fun floatArrayToByteArray(floatArray: FloatArray): ByteArray {
        val byteBuffer = ByteBuffer.allocate(floatArray.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (value in floatArray) {
            byteBuffer.putFloat(value)
        }
        return byteBuffer.array()
    }

    /**
     * Converts a ByteArray (BLOB) from SQLite back to FloatArray.
     */
    fun byteArrayToFloatArray(byteArray: ByteArray): FloatArray {
        val byteBuffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN)
        val floatArray = FloatArray(byteArray.size / 4)
        for (i in floatArray.indices) {
            floatArray[i] = byteBuffer.float
        }
        return floatArray
    }

    const val EMBEDDING_DIM = 192

    /**
     * Converts a list of FloatArrays (gallery of embeddings) to a single contiguous ByteArray.
     * Each embedding is 192 floats (768 bytes).
     */
    fun floatArraysToByteArray(vectors: List<FloatArray>): ByteArray {
        if (vectors.isEmpty()) return ByteArray(0)
        val totalFloats = vectors.sumOf { it.size }
        val byteBuffer = ByteBuffer.allocate(totalFloats * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (vector in vectors) {
            for (value in vector) {
                byteBuffer.putFloat(value)
            }
        }
        return byteBuffer.array()
    }

    /**
     * Parses a ByteArray (BLOB) into a List of FloatArray (gallery of embeddings).
     * If legacy single-vector (768 bytes), returns a list with 1 vector.
     * If multiple (e.g. N * 768 bytes), returns list of N vectors.
     */
    fun byteArrayToFloatArrays(byteArray: ByteArray): List<FloatArray> {
        if (byteArray.isEmpty()) return emptyList()
        val byteBuffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN)
        val totalFloats = byteArray.size / 4
        if (totalFloats == 0) return emptyList()

        val vectorDim = if (totalFloats % EMBEDDING_DIM == 0) EMBEDDING_DIM else totalFloats
        val count = totalFloats / vectorDim
        val result = ArrayList<FloatArray>(count)
        for (i in 0 until count) {
            val arr = FloatArray(vectorDim)
            for (j in 0 until vectorDim) {
                arr[j] = byteBuffer.float
            }
            result.add(arr)
        }
        return result
    }
}
