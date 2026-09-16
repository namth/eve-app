package com.example.facedetector.ai

import com.example.facedetector.data.UserRecord
import kotlin.math.sqrt

object VectorMath {

    const val SIMILARITY_ENTRY_THRESHOLD = 0.78f // Ngưỡng kích hoạt nhận diện người quen mới (Entry)
    const val SIMILARITY_HOLD_THRESHOLD = 0.65f  // Ngưỡng trễ giữ nhận diện không bị tụt khi đang theo dõi (Hold)
    const val DEFAULT_SIMILARITY_THRESHOLD = SIMILARITY_ENTRY_THRESHOLD

    data class MatchResult(
        val user: UserRecord,
        val similarity: Float
    )

    /**
     * Calculates the Cosine Similarity between two vectors:
     * cos(theta) = (A . B) / (||A|| * ||B||)
     * Returns a value between -1.0 and 1.0 (closer to 1.0 means more similar).
     */
    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size) return 0f
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }

        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator > 0f) dotProduct / denominator else 0f
    }

    /**
     * Calculates Euclidean Distance (L2 distance) between two vectors.
     */
    fun euclideanDistance(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size) return Float.MAX_VALUE
        var sumSquares = 0f
        for (i in v1.indices) {
            val diff = v1[i] - v2[i]
            sumSquares += diff * diff
        }
        return sqrt(sumSquares)
    }

    /**
     * L2 normalizes a vector so its magnitude is 1.0.
     */
    fun l2Normalize(v: FloatArray): FloatArray {
        var sumSquares = 0f
        for (x in v) {
            sumSquares += x * x
        }
        val norm = sqrt(sumSquares)
        if (norm == 0f) return v

        val result = FloatArray(v.size)
        for (i in v.indices) {
            result[i] = v[i] / norm
        }
        return result
    }

    /**
     * Compares a query vector against all enrolled users in the database
     * and returns the best matching user if similarity >= threshold.
     */
    fun findBestMatch(
        queryEmbedding: FloatArray,
        users: List<UserRecord>,
        threshold: Float = DEFAULT_SIMILARITY_THRESHOLD
    ): MatchResult? {
        if (users.isEmpty()) return null

        var bestMatch: UserRecord? = null
        var highestSimilarity = -1.0f

        for (user in users) {
            val sim = cosineSimilarity(queryEmbedding, user.embedding)
            if (sim > highestSimilarity) {
                highestSimilarity = sim
                bestMatch = user
            }
        }

        return if (bestMatch != null && highestSimilarity >= threshold) {
            MatchResult(bestMatch, highestSimilarity)
        } else {
            null
        }
    }

    data class ProfileMatchResult(
        val person: com.example.facedetector.data.PersonProfile,
        val similarity: Float
    )

    /**
     * Finds best matching person profile by comparing query vector against all embeddings
     * in each person's gallery and picking the highest similarity match.
     */
    fun findBestPersonMatch(
        queryEmbedding: FloatArray,
        people: List<com.example.facedetector.data.PersonProfile>,
        threshold: Float = DEFAULT_SIMILARITY_THRESHOLD
    ): ProfileMatchResult? {
        if (people.isEmpty()) return null

        var bestMatch: com.example.facedetector.data.PersonProfile? = null
        var highestSimilarity = -1.0f

        for (person in people) {
            val embeddings = person.faceEmbeddings
            if (embeddings.isEmpty()) continue

            var maxPersonSim = -1.0f
            for (emb in embeddings) {
                val sim = cosineSimilarity(queryEmbedding, emb)
                if (sim > maxPersonSim) {
                    maxPersonSim = sim
                }
            }

            if (maxPersonSim > highestSimilarity) {
                highestSimilarity = maxPersonSim
                bestMatch = person
            }
        }

        return if (bestMatch != null && highestSimilarity >= threshold) {
            ProfileMatchResult(bestMatch, highestSimilarity)
        } else {
            null
        }
    }
}
