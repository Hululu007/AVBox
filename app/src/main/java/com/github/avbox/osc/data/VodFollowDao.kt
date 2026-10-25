package com.github.avbox.osc.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VodFollowDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: VodFollow): Long

    @Query("select * from vodFollow order by addedTime desc")
    fun observeAll(): Flow<List<VodFollow>>

    @Query("select * from vodFollow where `cid`=:cid and `sourceKey`=:sourceKey and `vodId`=:vodId")
    suspend fun getVodFollow(cid: String?, sourceKey: String?, vodId: String?): VodFollow?

    @Query("delete from vodFollow where `id` in (:ids)")
    suspend fun deleteSelected(ids: List<Int>)
}
