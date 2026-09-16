package com.example.facedetector.data

data class PersonProfile(
    val id: String,
    val name: String,
    val age: Int? = null,
    val gender: String = "unknown",
    val preferredPronoun: String = "Bạn",
    val role: String = "friend",
    val avatarBase64: String? = null,
    val faceEmbeddings: List<FloatArray> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    var lastSeenAt: Long = System.currentTimeMillis()
) {
    // Compatibility getter: returns primary embedding (first in gallery)
    val faceEmbedding: FloatArray?
        get() = faceEmbeddings.firstOrNull()

    // Secondary constructor for single embedding (backwards compatibility)
    constructor(
        id: String,
        name: String,
        age: Int? = null,
        gender: String = "unknown",
        preferredPronoun: String = "Bạn",
        role: String = "friend",
        avatarBase64: String? = null,
        faceEmbedding: FloatArray?,
        createdAt: Long = System.currentTimeMillis(),
        lastSeenAt: Long = System.currentTimeMillis()
    ) : this(
        id = id,
        name = name,
        age = age,
        gender = gender,
        preferredPronoun = preferredPronoun,
        role = role,
        avatarBase64 = avatarBase64,
        faceEmbeddings = if (faceEmbedding != null) listOf(faceEmbedding) else emptyList(),
        createdAt = createdAt,
        lastSeenAt = lastSeenAt
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PersonProfile
        if (id != other.id) return false
        if (name != other.name) return false
        if (age != other.age) return false
        if (gender != other.gender) return false
        if (preferredPronoun != other.preferredPronoun) return false
        if (role != other.role) return false
        if (avatarBase64 != other.avatarBase64) return false
        if (faceEmbeddings.size != other.faceEmbeddings.size) return false
        for (i in faceEmbeddings.indices) {
            if (!faceEmbeddings[i].contentEquals(other.faceEmbeddings[i])) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (age ?: 0)
        result = 31 * result + gender.hashCode()
        result = 31 * result + preferredPronoun.hashCode()
        result = 31 * result + role.hashCode()
        result = 31 * result + (avatarBase64?.hashCode() ?: 0)
        result = 31 * result + faceEmbeddings.hashCode()
        return result
    }
}
