package com.example.facedetector.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class UserDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "face_detector.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_USERS = "users"
        const val COLUMN_ID = "id"
        const val COLUMN_NAME = "name"
        const val COLUMN_EMBEDDING = "embedding"
        const val COLUMN_CREATED_AT = "created_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableQuery = """
            CREATE TABLE $TABLE_USERS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_NAME TEXT NOT NULL,
                $COLUMN_EMBEDDING BLOB NOT NULL,
                $COLUMN_CREATED_AT INTEGER NOT NULL
            )
        """.trimIndent()
        db.execSQL(createTableQuery)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_USERS")
        onCreate(db)
    }

    /**
     * Inserts a new enrolled face user into database.
     */
    fun insertUser(name: String, embedding: FloatArray): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_NAME, name)
            put(COLUMN_EMBEDDING, ByteUtils.floatArrayToByteArray(embedding))
            put(COLUMN_CREATED_AT, System.currentTimeMillis())
        }
        return db.insert(TABLE_USERS, null, values)
    }

    /**
     * Retrieves all enrolled users from database.
     */
    fun getAllUsers(): List<UserRecord> {
        val userList = mutableListOf<UserRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_USERS,
            arrayOf(COLUMN_ID, COLUMN_NAME, COLUMN_EMBEDDING, COLUMN_CREATED_AT),
            null,
            null,
            null,
            null,
            "$COLUMN_ID ASC"
        )

        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(COLUMN_ID)
            val nameIndex = it.getColumnIndexOrThrow(COLUMN_NAME)
            val embeddingIndex = it.getColumnIndexOrThrow(COLUMN_EMBEDDING)
            val createdAtIndex = it.getColumnIndexOrThrow(COLUMN_CREATED_AT)

            while (it.moveToNext()) {
                val id = it.getLong(idIndex)
                val name = it.getString(nameIndex)
                val embeddingBlob = it.getBlob(embeddingIndex)
                val createdAt = it.getLong(createdAtIndex)
                val embedding = ByteUtils.byteArrayToFloatArray(embeddingBlob)

                userList.add(UserRecord(id, name, embedding, createdAt))
            }
        }
        return userList
    }

    /**
     * Gets count of enrolled users.
     */
    fun getUserCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_USERS", null)
        var count = 0
        cursor.use {
            if (it.moveToFirst()) {
                count = it.getInt(0)
            }
        }
        return count
    }

    /**
     * Deletes user by ID.
     */
    fun deleteUser(id: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_USERS, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }
}
