package com.example.facedetector.ai

import com.example.facedetector.data.UserRecord
import kotlin.math.sqrt

object VectorMath {

    const val SIMILARITY_ENTRY_THRESHOLD = 0.80f // Ngưỡng kích hoạt nhận diện người quen mới (Entry) - Siết chặt để chống nhận nhầm
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

        val candidates = mutableListOf<ProfileMatchResult>()
        for (person in people) {
            val embeddings = person.faceEmbeddings
            if (embeddings.isEmpty()) continue

            val sortedSims = embeddings.map { cosineSimilarity(queryEmbedding, it) }.sortedDescending()
            val maxSim = sortedSims.first()

            // Consistency check: nếu hồ sơ có >= 3 vector và maxSim nằm ở vùng cận biên [threshold .. 0.83f],
            // đòi hỏi vector tốt thứ 2 không được quá xa lạ (>= 0.55f) để loại bỏ trường hợp ăn may 1 góc dị biệt.
            if (sortedSims.size >= 3 && maxSim in threshold..0.83f) {
                if (sortedSims[1] < 0.55f) {
                    continue
                }
            }

            if (maxSim >= threshold) {
                candidates.add(ProfileMatchResult(person, maxSim))
            }
        }

        if (candidates.isEmpty()) return null

        candidates.sortByDescending { it.similarity }
        val topMatch = candidates[0]

        // Margin check: nếu có >= 2 hồ sơ khác nhau cùng vượt ngưỡng,
        // khoảng cách giữa người cao nhất và người thứ nhì phải >= 0.035f để tránh nhận nhầm mơ hồ
        if (candidates.size >= 2) {
            val secondMatch = candidates[1]
            if (topMatch.person.id != secondMatch.person.id && (topMatch.similarity - secondMatch.similarity) < 0.035f) {
                return null
            }
        }

        return topMatch
    }

    data class AmbiguityMatchResult(
        val person: com.example.facedetector.data.PersonProfile,
        val maxSimilarity: Float,
        val consensusCount: Int
    )

    /**
     * Kiểm tra xem khuôn mặt hiện tại có rơi vào vùng "ngờ ngợ" [minThreshold .. maxThreshold)
     * với một người quen trong danh bạ hay không, kết hợp kiểm tra đa góc mặt đồng thuận.
     */
    fun checkAmbiguousMatch(
        queryEmbedding: FloatArray,
        people: List<com.example.facedetector.data.PersonProfile>,
        minThreshold: Float = 0.65f,
        maxThreshold: Float = SIMILARITY_ENTRY_THRESHOLD
    ): AmbiguityMatchResult? {
        if (people.isEmpty()) return null

        val candidates = mutableListOf<AmbiguityMatchResult>()

        for (person in people) {
            val embeddings = person.faceEmbeddings
            if (embeddings.isEmpty()) continue

            val sims = embeddings.map { cosineSimilarity(queryEmbedding, it) }.sortedDescending()
            val maxSim = sims.first()

            if (maxSim >= minThreshold && maxSim < maxThreshold) {
                // Đếm số góc mặt đồng thuận đạt >= 0.60
                val consensusCount = sims.count { it >= 0.60f }

                // Nếu người đó đã có >= 2 góc trong DB, đòi hỏi ít nhất 2 góc phải đồng thuận
                // Nếu chỉ có 1 góc ban đầu trong DB, đòi hỏi maxSim phải >= 0.70
                val isQualified = if (embeddings.size >= 2) {
                    consensusCount >= 2
                } else {
                    maxSim >= 0.70f
                }

                if (isQualified) {
                    candidates.add(AmbiguityMatchResult(person, maxSim, consensusCount))
                }
            }
        }

        if (candidates.isEmpty()) return null

        candidates.sortByDescending { it.maxSimilarity }
        val top = candidates[0]

        // Nếu có >= 2 người cùng rơi vào dải ngờ ngợ, khoảng cách phải đủ rõ (> 0.035)
        if (candidates.size >= 2) {
            val second = candidates[1]
            if (top.person.id != second.person.id && (top.maxSimilarity - second.maxSimilarity) < 0.035f) {
                return null
            }
        }

        return top
    }

    /**
     * Thuật toán Nearest Replacement & Moving Average (70/30):
     * - Nếu chưa đủ 9 slots: thêm vào slot mới.
     * - Nếu đã đủ 9 slots: Khóa Slot 0 (ảnh gốc), tìm slot gần nhất trong 1..8
     *   và hòa trộn 70% vector cũ + 30% vector mới, sau đó chuẩn hóa L2.
     */
    fun blendEmbeddings(
        existingEmbeddings: List<FloatArray>,
        newEmbedding: FloatArray,
        alpha: Float = 0.30f
    ): List<FloatArray> {
        val normalizedNew = l2Normalize(newEmbedding)
        if (existingEmbeddings.isEmpty()) {
            return listOf(normalizedNew)
        }

        // Trường hợp 1: Chưa đủ 9 vector
        if (existingEmbeddings.size < 9) {
            val maxSim = existingEmbeddings.maxOfOrNull { cosineSimilarity(normalizedNew, it) } ?: 0f
            // Nếu góc mới có độ đa dạng tốt (< 0.94) -> thêm vào cuối
            return if (maxSim < 0.94f) {
                existingEmbeddings + listOf(normalizedNew)
            } else {
                // Nếu quá giống một góc đã có, hòa trộn vào góc đó
                val bestIdx = existingEmbeddings.indices.maxByOrNull {
                    cosineSimilarity(normalizedNew, existingEmbeddings[it])
                } ?: 0
                val result = existingEmbeddings.toMutableList()
                val oldVec = result[bestIdx]
                val blended = FloatArray(oldVec.size) { i: Int ->
                    (1f - alpha) * oldVec[i] + alpha * normalizedNew[i]
                }
                result[bestIdx] = l2Normalize(blended)
                result
            }
        }

        // Trường hợp 2: Đã đầy đủ 9 vector (Slots 0..8)
        // Khóa cố định Slot 0 (Ảnh gốc). Tìm slot gần nhất trong các slot 1..8
        val searchRange = 1 until existingEmbeddings.size
        var bestIdx = 1
        var bestSim = -1.0f

        for (i in searchRange) {
            val sim = cosineSimilarity(normalizedNew, existingEmbeddings[i])
            if (sim > bestSim) {
                bestSim = sim
                bestIdx = i
            }
        }

        val result = existingEmbeddings.toMutableList()
        val oldVec = result[bestIdx]
        val blended = FloatArray(oldVec.size) { i: Int ->
            (1f - alpha) * oldVec[i] + alpha * normalizedNew[i]
        }
        result[bestIdx] = l2Normalize(blended)
        return result
    }
}
