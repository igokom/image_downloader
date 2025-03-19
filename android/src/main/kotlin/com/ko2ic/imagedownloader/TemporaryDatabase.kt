package com.ko2ic.imagedownloader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.MediaStore

class TemporaryDatabase(context: Context) :
    SQLiteOpenHelper(context, TABLE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val TABLE_NAME = "image_downloader_temporary"

        private const val DATABASE_VERSION = 1
        private const val DICTIONARY_TABLE_CREATE = "CREATE TABLE $TABLE_NAME (" +
                "${MediaStore.MediaColumns._ID} TEXT, " +
                "${MediaStore.MediaColumns.MIME_TYPE} TEXT, " +
                "${MediaStore.MediaColumns.DATA} TEXT, " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} TEXT, " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} TEXT, " +
                "${MediaStore.MediaColumns.SIZE} INTEGER " +
                ");"

        val COLUMNS =
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.SIZE
            )
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(DICTIONARY_TABLE_CREATE)
    }
}