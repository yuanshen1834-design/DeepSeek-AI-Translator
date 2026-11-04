package com.deeplab.translator

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 一条翻译记录 */
data class TranslationRecord(
    val id: Long,
    val timestamp: Long,
    val srcLang: String,
    val tgtLang: String,
    val srcText: String,
    val tgtText: String
)

/** 翻译记录 SQLite 存储 */
class TranslationDbHelper(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "translations.db"
        private const val DB_VERSION = 1
        private const val TABLE = "history"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "ts INTEGER NOT NULL," +
                "src_lang TEXT NOT NULL," +
                "tgt_lang TEXT NOT NULL," +
                "src_text TEXT NOT NULL," +
                "tgt_text TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun insert(ts: Long, srcLang: String, tgtLang: String, srcText: String, tgtText: String) {
        val values = ContentValues().apply {
            put("ts", ts)
            put("src_lang", srcLang)
            put("tgt_lang", tgtLang)
            put("src_text", srcText)
            put("tgt_text", tgtText)
        }
        writableDatabase.insert(TABLE, null, values)
    }

    fun getAll(): List<TranslationRecord> {
        val list = mutableListOf<TranslationRecord>()
        val cursor = readableDatabase.query(TABLE, null, null, null, null, null, "ts DESC")
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    TranslationRecord(
                        id = it.getLong(it.getColumnIndexOrThrow("_id")),
                        timestamp = it.getLong(it.getColumnIndexOrThrow("ts")),
                        srcLang = it.getString(it.getColumnIndexOrThrow("src_lang")),
                        tgtLang = it.getString(it.getColumnIndexOrThrow("tgt_lang")),
                        srcText = it.getString(it.getColumnIndexOrThrow("src_text")),
                        tgtText = it.getString(it.getColumnIndexOrThrow("tgt_text"))
                    )
                )
            }
        }
        return list
    }

    fun delete(id: Long) {
        writableDatabase.delete(TABLE, "_id=?", arrayOf(id.toString()))
    }

    fun deleteAll() {
        writableDatabase.delete(TABLE, null, null)
    }
}
