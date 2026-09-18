package com.example.facedetector

import com.example.facedetector.ai.VectorMath
import com.example.facedetector.data.PersonProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class VectorMathAmbiguityTest {

    private fun createUnitVector(size: Int = 192, dominantIndex: Int = 0): FloatArray {
        val arr = FloatArray(size) { 0f }
        arr[dominantIndex] = 1.0f
        return arr
    }

    private fun vectorNorm(v: FloatArray): Float {
        var sum = 0f
        for (x in v) sum += x * x
        return sqrt(sum)
    }

    @Test
    fun testL2Normalize() {
        val v = FloatArray(192) { 2.5f }
        val normalized = VectorMath.l2Normalize(v)
        val norm = vectorNorm(normalized)
        assertTrue("Norm should be approximately 1.0, but was $norm", abs(norm - 1.0f) < 0.0001f)
    }

    @Test
    fun testBlendEmbeddings_LessThan9_AppendsNew() {
        val v0 = createUnitVector(192, 0)
        val v1 = createUnitVector(192, 1)
        val existing = listOf(v0, v1)

        val vNew = createUnitVector(192, 2)
        val updated = VectorMath.blendEmbeddings(existing, vNew, alpha = 0.30f)

        assertEquals(3, updated.size)
        assertTrue(v0.contentEquals(updated[0]))
        assertTrue(v1.contentEquals(updated[1]))
    }

    @Test
    fun testBlendEmbeddings_Full9_PreservesSlot0_AndBlendsNearest() {
        // Tạo 9 vector cơ sở
        val existing = (0 until 9).map { createUnitVector(192, it) }
        val originalSlot0 = existing[0].clone()
        val originalSlot3 = existing[3].clone()

        // vNew rất gần với Slot 3 (dominantIndex = 3 với chút nhiễu)
        val vNew = createUnitVector(192, 3)

        val updated = VectorMath.blendEmbeddings(existing, vNew, alpha = 0.30f)

        assertEquals(9, updated.size)
        // 1. Slot 0 phải tuyệt đối không đổi (Khóa mỏ neo gốc)
        assertTrue("Slot 0 must be preserved unchanged!", originalSlot0.contentEquals(updated[0]))

        // 2. Slot 3 là gần nhất, phải được hòa trộn và có độ dài L2 = 1.0
        val normSlot3 = vectorNorm(updated[3])
        assertTrue("Blended vector norm should be 1.0", abs(normSlot3 - 1.0f) < 0.0001f)

        // 3. Các slot khác (ví dụ Slot 1, 2, 4, 5, 6, 7, 8) không bị thay đổi
        assertTrue(existing[1].contentEquals(updated[1]))
        assertTrue(existing[2].contentEquals(updated[2]))
        assertTrue(existing[4].contentEquals(updated[4]))
    }

    @Test
    fun testCheckAmbiguousMatch_DetectsCandidateWithinRange() {
        // Tạo hồ sơ có 3 vector
        val v0 = createUnitVector(192, 0)
        val v1 = createUnitVector(192, 1)
        val v2 = createUnitVector(192, 2)

        val person = PersonProfile(
            id = "test_1",
            name = "Nam",
            gender = "male",
            preferredPronoun = "Anh",
            role = "admin",
            faceEmbeddings = listOf(v0, v1, v2)
        )

        // Query vector có Cosine Similarity ~ 0.707 với v0 và v1
        val query = FloatArray(192) { 0f }
        query[0] = 0.7071f
        query[1] = 0.7071f

        val result = VectorMath.checkAmbiguousMatch(query, listOf(person), minThreshold = 0.65f, maxThreshold = 0.80f)

        assertNotNull("Should detect ambiguous match", result)
        assertEquals("test_1", result?.person?.id)
        assertTrue("Consensus count should be >= 2", (result?.consensusCount ?: 0) >= 2)
    }

    @Test
    fun testCheckAmbiguousMatch_IgnoresWhenAboveMaxThreshold() {
        val v0 = createUnitVector(192, 0)
        val person = PersonProfile(
            id = "test_1",
            name = "Nam",
            gender = "male",
            preferredPronoun = "Anh",
            role = "admin",
            faceEmbeddings = listOf(v0)
        )

        // Query vector trùng 100% (Sim = 1.0 >= 0.80) -> Không phải ngờ ngợ mà là chắc chắn người quen
        val query = createUnitVector(192, 0)
        val result = VectorMath.checkAmbiguousMatch(query, listOf(person), minThreshold = 0.65f, maxThreshold = 0.80f)

        assertNull("Should not flag as ambiguous when similarity >= 0.80", result)
    }
}
