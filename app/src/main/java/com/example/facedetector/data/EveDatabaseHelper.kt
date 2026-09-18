package com.example.facedetector.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class EveDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "eve_database.db"
        const val DATABASE_VERSION = 1

        // Table: people
        const val TABLE_PEOPLE = "people"
        const val COL_ID = "id"
        const val COL_NAME = "name"
        const val COL_AGE = "age"
        const val COL_GENDER = "gender"
        const val COL_PRONOUN = "preferred_pronoun"
        const val COL_ROLE = "role"
        const val COL_AVATAR = "avatar_base64"
        const val COL_EMBEDDING = "face_embedding"
        const val COL_CREATED_AT = "created_at"
        const val COL_LAST_SEEN_AT = "last_seen_at"

        // Table: app_settings
        const val TABLE_SETTINGS = "app_settings"
        const val COL_SETTING_KEY = "key"
        const val COL_SETTING_VAL = "value"

        // Table: notifications
        const val TABLE_NOTIFICATIONS = "notifications"
        const val COL_NOTIF_ID = "id"
        const val COL_NOTIF_TITLE = "title"
        const val COL_NOTIF_BODY = "body"
        const val COL_NOTIF_DATA = "data"
        const val COL_NOTIF_IS_READ = "is_read"
        const val COL_NOTIF_RECEIVED_AT = "received_at"

        // Table: pronunciations
        const val TABLE_PRONUNCIATIONS = "pronunciations"
        const val COL_PRONUN_WORD = "word"
        const val COL_PRONUN_SPEAK = "speak"

        const val KEY_AI_PROVIDER = "ai_provider"
        const val KEY_AI_API_KEY = "ai_api_key"
        const val KEY_AI_MODEL = "ai_model"
        const val KEY_AI_BASE_URL = "ai_base_url"
        const val KEY_FCM_TOKEN = "fcm_token"

        const val KEY_PERPLEXITY_API_KEY = "perplexity_api_key"
        const val KEY_PERPLEXITY_MODEL = "perplexity_model"
        const val KEY_PERPLEXITY_BASE_URL = "perplexity_base_url"

        private const val B64_OPENROUTER_KEY = "c2stb3ItdjEtZWQ1NzlhOTQ0YWVkN2FlN2RkMWE0MDZlZjBlYWI3OGQ3OTk3NzAyMjQ5YWY1NDFjN2Q1ZWE0Zjg5ODU4N2VjMA=="
        val DEFAULT_OPENROUTER_KEY: String by lazy {
            try { String(android.util.Base64.decode(B64_OPENROUTER_KEY, android.util.Base64.DEFAULT)) } catch (_: Exception) { "" }
        }
        const val DEFAULT_OPENROUTER_MODEL = "openai/gpt-4o-mini"
        const val DEFAULT_OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"

        private const val B64_PERPLEXITY_KEY = "cHBseC1PNjZLdEVaSXNJeVNBOHpQaVRzRVRjM05NV09iaFhMdkpUdzNHYUR4MWs1cGs1WE8="
        val DEFAULT_PERPLEXITY_KEY: String by lazy {
            try { String(android.util.Base64.decode(B64_PERPLEXITY_KEY, android.util.Base64.DEFAULT)) } catch (_: Exception) { "" }
        }
        const val DEFAULT_PERPLEXITY_MODEL = "sonar"
        const val DEFAULT_PERPLEXITY_URL = "https://api.perplexity.ai/chat/completions"
    }

    override fun onCreate(db: SQLiteDatabase) {
        // 1. People table
        db.execSQL("""
            CREATE TABLE $TABLE_PEOPLE (
                $COL_ID TEXT PRIMARY KEY,
                $COL_NAME TEXT NOT NULL,
                $COL_AGE INTEGER,
                $COL_GENDER TEXT,
                $COL_PRONOUN TEXT,
                $COL_ROLE TEXT,
                $COL_AVATAR TEXT,
                $COL_EMBEDDING BLOB,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_LAST_SEEN_AT INTEGER NOT NULL
            )
        """.trimIndent())

        // 2. Settings table
        db.execSQL("""
            CREATE TABLE $TABLE_SETTINGS (
                $COL_SETTING_KEY TEXT PRIMARY KEY,
                $COL_SETTING_VAL TEXT NOT NULL
            )
        """.trimIndent())

        // 3. Notifications queue table
        db.execSQL("""
            CREATE TABLE $TABLE_NOTIFICATIONS (
                $COL_NOTIF_ID TEXT PRIMARY KEY,
                $COL_NOTIF_TITLE TEXT,
                $COL_NOTIF_BODY TEXT,
                $COL_NOTIF_DATA TEXT,
                $COL_NOTIF_IS_READ INTEGER DEFAULT 0,
                $COL_NOTIF_RECEIVED_AT INTEGER NOT NULL
            )
        """.trimIndent())

        // 4. Pronunciations table
        db.execSQL("""
            CREATE TABLE $TABLE_PRONUNCIATIONS (
                $COL_PRONUN_WORD TEXT PRIMARY KEY,
                $COL_PRONUN_SPEAK TEXT NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PEOPLE")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SETTINGS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NOTIFICATIONS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PRONUNCIATIONS")
        onCreate(db)
    }

    // ==========================================
    // PEOPLE / PROFILES OPERATIONS
    // ==========================================

    fun upsertPerson(person: PersonProfile): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_ID, person.id)
            put(COL_NAME, person.name)
            if (person.age != null) put(COL_AGE, person.age) else putNull(COL_AGE)
            put(COL_GENDER, person.gender)
            put(COL_PRONOUN, person.preferredPronoun)
            put(COL_ROLE, person.role)
            if (person.avatarBase64 != null) put(COL_AVATAR, person.avatarBase64) else putNull(COL_AVATAR)
            if (person.faceEmbeddings.isNotEmpty()) {
                put(COL_EMBEDDING, ByteUtils.floatArraysToByteArray(person.faceEmbeddings))
            } else if (person.faceEmbedding != null) {
                put(COL_EMBEDDING, ByteUtils.floatArrayToByteArray(person.faceEmbedding!!))
            } else {
                putNull(COL_EMBEDDING)
            }
            put(COL_CREATED_AT, person.createdAt)
            put(COL_LAST_SEEN_AT, person.lastSeenAt)
        }
        return db.insertWithOnConflict(TABLE_PEOPLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateFaceEmbedding(personId: String, embedding: FloatArray, avatarBase64: String? = null): Boolean {
        return updateFaceEmbeddings(personId, listOf(embedding), avatarBase64)
    }

    fun updateFaceEmbeddings(personId: String, embeddings: List<FloatArray>, avatarBase64: String? = null): Boolean {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_EMBEDDING, ByteUtils.floatArraysToByteArray(embeddings))
            if (avatarBase64 != null) {
                put(COL_AVATAR, avatarBase64)
            }
            put(COL_LAST_SEEN_AT, System.currentTimeMillis())
        }
        val rows = db.update(TABLE_PEOPLE, values, "$COL_ID = ?", arrayOf(personId))
        return rows > 0
    }

    fun findPersonById(id: String): PersonProfile? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_PEOPLE,
            null,
            "$COL_ID = ?",
            arrayOf(id.trim()),
            null,
            null,
            null,
            "1"
        )
        cursor.use {
            if (it.moveToFirst()) {
                return cursorToPerson(it)
            }
        }
        return null
    }

    fun findPersonByName(name: String): PersonProfile? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_PEOPLE,
            null,
            "LOWER($COL_NAME) = LOWER(?)",
            arrayOf(name.trim()),
            null,
            null,
            null,
            "1"
        )
        cursor.use {
            if (it.moveToFirst()) {
                return cursorToPerson(it)
            }
        }
        return null
    }

    fun findAllPeopleByName(name: String): List<PersonProfile> {
        val list = mutableListOf<PersonProfile>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_PEOPLE,
            null,
            "LOWER($COL_NAME) = LOWER(?)",
            arrayOf(name.trim()),
            null,
            null,
            "$COL_LAST_SEEN_AT DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToPerson(it))
            }
        }
        return list
    }

    fun getPersonById(id: String): PersonProfile? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_PEOPLE,
            null,
            "$COL_ID = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1"
        )
        cursor.use {
            if (it.moveToFirst()) {
                return cursorToPerson(it)
            }
        }
        return null
    }

    fun getAllPeople(): List<PersonProfile> {
        val list = mutableListOf<PersonProfile>()
        val db = readableDatabase
        val cursor = db.query(TABLE_PEOPLE, null, null, null, null, null, "$COL_LAST_SEEN_AT DESC")
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToPerson(it))
            }
        }
        return list
    }

    fun updateLastSeen(personId: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_LAST_SEEN_AT, System.currentTimeMillis())
        }
        db.update(TABLE_PEOPLE, values, "$COL_ID = ?", arrayOf(personId))
    }

    fun deletePerson(id: String): Boolean {
        val db = writableDatabase
        return db.delete(TABLE_PEOPLE, "$COL_ID = ?", arrayOf(id)) > 0
    }

    private fun cursorToPerson(cursor: android.database.Cursor): PersonProfile {
        val id = cursor.getString(cursor.getColumnIndexOrThrow(COL_ID))
        val name = cursor.getString(cursor.getColumnIndexOrThrow(COL_NAME))
        val ageIndex = cursor.getColumnIndexOrThrow(COL_AGE)
        val age = if (cursor.isNull(ageIndex)) null else cursor.getInt(ageIndex)
        val gender = cursor.getString(cursor.getColumnIndexOrThrow(COL_GENDER)) ?: "unknown"
        val pronoun = cursor.getString(cursor.getColumnIndexOrThrow(COL_PRONOUN)) ?: "Bạn"
        val role = cursor.getString(cursor.getColumnIndexOrThrow(COL_ROLE)) ?: "friend"
        val avatarIndex = cursor.getColumnIndexOrThrow(COL_AVATAR)
        val avatarBase64 = if (cursor.isNull(avatarIndex)) null else cursor.getString(avatarIndex)
        val embIndex = cursor.getColumnIndexOrThrow(COL_EMBEDDING)
        val faceEmbeddings = if (cursor.isNull(embIndex)) {
            emptyList()
        } else {
            ByteUtils.byteArrayToFloatArrays(cursor.getBlob(embIndex))
        }
        val createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT))
        val lastSeenAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_LAST_SEEN_AT))

        return PersonProfile(
            id = id,
            name = name,
            age = age,
            gender = gender,
            preferredPronoun = pronoun,
            role = role,
            avatarBase64 = avatarBase64,
            faceEmbeddings = faceEmbeddings,
            createdAt = createdAt,
            lastSeenAt = lastSeenAt
        )
    }

    // ==========================================
    // APP SETTINGS OPERATIONS (Key-Value)
    // ==========================================

    fun setSetting(key: String, value: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SETTING_KEY, key)
            put(COL_SETTING_VAL, value)
        }
        db.insertWithOnConflict(TABLE_SETTINGS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getSetting(key: String, defaultValue: String? = null): String? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SETTINGS,
            arrayOf(COL_SETTING_VAL),
            "$COL_SETTING_KEY = ?",
            arrayOf(key),
            null,
            null,
            null
        )
        cursor.use {
            if (it.moveToFirst()) {
                return it.getString(0)
            }
        }
        return defaultValue
    }

    // ==========================================
    // PRONUNCIATIONS OPERATIONS
    // ==========================================

    fun savePronunciation(word: String, speak: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_PRONUN_WORD, word.trim())
            put(COL_PRONUN_SPEAK, speak.trim())
        }
        db.insertWithOnConflict(TABLE_PRONUNCIATIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAllPronunciations(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val db = readableDatabase
        val cursor = db.query(TABLE_PRONUNCIATIONS, null, null, null, null, null, null)
        cursor.use {
            while (it.moveToNext()) {
                val word = it.getString(it.getColumnIndexOrThrow(COL_PRONUN_WORD))
                val speak = it.getString(it.getColumnIndexOrThrow(COL_PRONUN_SPEAK))
                map[word] = speak
            }
        }
        return map
    }

    // ==========================================
    // NOTIFICATIONS OPERATIONS
    // ==========================================

    fun insertNotification(id: String, title: String, body: String, dataJson: String? = null): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_NOTIF_ID, id)
            put(COL_NOTIF_TITLE, title)
            put(COL_NOTIF_BODY, body)
            put(COL_NOTIF_DATA, dataJson)
            put(COL_NOTIF_IS_READ, 0)
            put(COL_NOTIF_RECEIVED_AT, System.currentTimeMillis())
        }
        return db.insertWithOnConflict(TABLE_NOTIFICATIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getPendingNotifications(): List<NotificationItem> {
        val list = mutableListOf<NotificationItem>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NOTIFICATIONS,
            null,
            "$COL_NOTIF_IS_READ = 0",
            null,
            null,
            null,
            "$COL_NOTIF_RECEIVED_AT ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(it.getColumnIndexOrThrow(COL_NOTIF_ID))
                val title = it.getString(it.getColumnIndexOrThrow(COL_NOTIF_TITLE)) ?: ""
                val body = it.getString(it.getColumnIndexOrThrow(COL_NOTIF_BODY)) ?: ""
                val data = it.getString(it.getColumnIndexOrThrow(COL_NOTIF_DATA))
                val isRead = it.getInt(it.getColumnIndexOrThrow(COL_NOTIF_IS_READ)) == 1
                val receivedAt = it.getLong(it.getColumnIndexOrThrow(COL_NOTIF_RECEIVED_AT))
                list.add(NotificationItem(id, title, body, data, isRead, receivedAt))
            }
        }
        return list
    }

    fun markNotificationsAsRead(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_NOTIF_IS_READ, 1)
        }
        val placeholders = ids.joinToString(",") { "?" }
        return db.update(TABLE_NOTIFICATIONS, values, "$COL_NOTIF_ID IN ($placeholders)", ids.toTypedArray())
    }

    fun markAllNotificationsAsRead(): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_NOTIF_IS_READ, 1)
        }
        return db.update(TABLE_NOTIFICATIONS, values, "$COL_NOTIF_IS_READ = 0", null)
    }

    fun clearAllNotifications(): Int {
        val db = writableDatabase
        return db.delete(TABLE_NOTIFICATIONS, null, null)
    }

    // ==========================================
    // AI AGENT & FCM SETTINGS HELPERS
    // ==========================================

    fun getAiApiKey(): String {
        return getSetting(KEY_AI_API_KEY, DEFAULT_OPENROUTER_KEY) ?: DEFAULT_OPENROUTER_KEY
    }

    fun setAiApiKey(key: String) {
        setSetting(KEY_AI_API_KEY, key)
    }

    fun getAiModel(): String {
        return getSetting(KEY_AI_MODEL, DEFAULT_OPENROUTER_MODEL) ?: DEFAULT_OPENROUTER_MODEL
    }

    fun setAiModel(model: String) {
        setSetting(KEY_AI_MODEL, model)
    }

    fun getAiBaseUrl(): String {
        return getSetting(KEY_AI_BASE_URL, DEFAULT_OPENROUTER_URL) ?: DEFAULT_OPENROUTER_URL
    }

    fun setAiBaseUrl(url: String) {
        setSetting(KEY_AI_BASE_URL, url)
    }

    fun getFcmToken(): String? {
        return getSetting(KEY_FCM_TOKEN)
    }

    fun setFcmToken(token: String) {
        setSetting(KEY_FCM_TOKEN, token)
    }

    fun getPerplexityApiKey(): String {
        return getSetting(KEY_PERPLEXITY_API_KEY, DEFAULT_PERPLEXITY_KEY) ?: DEFAULT_PERPLEXITY_KEY
    }

    fun setPerplexityApiKey(key: String) {
        setSetting(KEY_PERPLEXITY_API_KEY, key)
    }

    fun getPerplexityModel(): String {
        return getSetting(KEY_PERPLEXITY_MODEL, DEFAULT_PERPLEXITY_MODEL) ?: DEFAULT_PERPLEXITY_MODEL
    }

    fun setPerplexityModel(model: String) {
        setSetting(KEY_PERPLEXITY_MODEL, model)
    }

    fun getPerplexityBaseUrl(): String {
        return getSetting(KEY_PERPLEXITY_BASE_URL, DEFAULT_PERPLEXITY_URL) ?: DEFAULT_PERPLEXITY_URL
    }

    fun setPerplexityBaseUrl(url: String) {
        setSetting(KEY_PERPLEXITY_BASE_URL, url)
    }
}

data class NotificationItem(
    val id: String,
    val title: String,
    val body: String,
    val data: String? = null,
    val isRead: Boolean = false,
    val receivedAt: Long = System.currentTimeMillis()
)

