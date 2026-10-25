package com.github.avbox.osc.data

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

import com.github.avbox.osc.io.FileUtils
import com.github.avbox.osc.util.AppContextHolder

import java.io.File
import java.io.IOException

object AppDataManager {
    private const val DB_FILE_VERSION = 4
    private const val DB_NAME = "tvbox"
    private var manager: AppDataManager? = null
    private var dbInstance: AppDataBase? = null

    @JvmStatic
    fun init() {
        if (manager == null) {
            synchronized(AppDataManager::class.java) {
                if (manager == null) {
                    manager = this
                }
            }
        }
    }

    private fun dbPath(): String {
        return DB_NAME + ".v" + DB_FILE_VERSION + ".db"
    }

    @JvmStatic
    fun get(): AppDataBase {
        synchronized(AppDataManager::class.java) {
            if (manager == null) {
                throw RuntimeException("AppDataManager is no init")
            }
            if (dbInstance == null) {
                dbInstance = Room.databaseBuilder(requireNotNull(AppContextHolder.context()) { "app context not initialized" }, AppDataBase::class.java, dbPath())
                    .setDriver(BundledSQLiteDriver())
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                    .addMigrations(MIGRATION_1_2)
                    .build()
            }
            return requireNotNull(dbInstance) { "database instance not created" }
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun backup(path: File): Boolean {
        dbInstance?.close()
        dbInstance = null
        val db = requireNotNull(AppContextHolder.context()) { "app context not initialized" }.getDatabasePath(dbPath())
        return if (db.exists()) {
            FileUtils.copyFile(db, path)
            true
        } else {
            false
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun restore(path: File): Boolean {
        dbInstance?.close()
        dbInstance = null
        val db = requireNotNull(AppContextHolder.context()) { "app context not initialized" }.getDatabasePath(dbPath())
        if (db.exists()) {
            db.delete()
        }
        if (!db.parentFile.exists()) {
            db.parentFile.mkdirs()
        }
        FileUtils.copyFile(path, db)
        return true
    }
}
