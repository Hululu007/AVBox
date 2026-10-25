package com.github.avbox.osc.data

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VodCollectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: VodCollect): Long

    @Query("select * from vodCollect  order by updateTime desc")
    suspend fun getAll(): List<VodCollect>

    @Query("select * from vodCollect  order by updateTime desc")
    fun observeAll(): Flow<List<VodCollect>>

    @Query("select * from vodCollect where `id`=:id")
    suspend fun getVodCollect(id: Int): VodCollect?

    @Query("delete from vodCollect where `id`=:id")
    suspend fun delete(id: Int)

    @Query("select * from vodCollect where `cid`=:cid and `sourceKey`=:sourceKey and `vodId`=:vodId")
    suspend fun getVodCollect(cid: String?, sourceKey: String?, vodId: String?): VodCollect?

    @Delete
    suspend fun delete(record: VodCollect): Int

    @Query("DELETE FROM vodCollect")
    suspend fun deleteAll()
}
