package com.github.avbox.osc.data

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(entities = [CacheEntry::class, VodRecord::class, VodCollect::class, VodFollow::class], version = 2)
abstract class AppDataBase : RoomDatabase() {
    abstract fun getCacheDao(): CacheDao

    abstract fun getVodRecordDao(): VodRecordDao

    abstract fun getVodCollectDao(): VodCollectDao

    abstract fun getVodFollowDao(): VodFollowDao
}
