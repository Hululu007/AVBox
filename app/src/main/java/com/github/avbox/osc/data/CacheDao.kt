package com.github.avbox.osc.data

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update

@Dao
interface CacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(cache: CacheEntry): Long

    @Query("select *from cache where `key`=:key")
    suspend fun getCache(key: String?): CacheEntry?

    @Query("select *from cache")
    suspend fun getAll(): List<CacheEntry>

    @Delete
    suspend fun delete(cache: CacheEntry): Int

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(cache: CacheEntry): Int
}
