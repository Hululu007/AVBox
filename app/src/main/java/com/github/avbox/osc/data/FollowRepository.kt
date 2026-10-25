package com.github.avbox.osc.data

import com.github.avbox.osc.bean.VodInfo
import kotlinx.coroutines.flow.Flow

interface FollowRepository {

    fun currentCid(): String

    suspend fun find(sourceKey: String?, vodId: String?): VodFollow?

    fun observeAll(): Flow<List<VodFollow>>

    suspend fun upsert(sourceKey: String?, vodInfo: VodInfo, days: Set<Int>, hour: Int)

    suspend fun deleteSelected(ids: List<Int>)
}

internal class RoomFollowRepository(
    private val follows: () -> VodFollowDao,
) : FollowRepository {

    override fun currentCid(): String = CurrentSubscription.cid()

    override suspend fun find(sourceKey: String?, vodId: String?): VodFollow? =
        follows().getVodFollow(CurrentSubscription.cid(), sourceKey, vodId)

    override fun observeAll(): Flow<List<VodFollow>> = follows().observeAll()

    override suspend fun upsert(sourceKey: String?, vodInfo: VodInfo, days: Set<Int>, hour: Int) {
        val cid = CurrentSubscription.cid()
        val dao = follows()
        val record = dao.getVodFollow(cid, sourceKey, vodInfo.id) ?: VodFollow()
        if (record.id == 0) {
            record.cid = cid
            record.sourceKey = sourceKey
            record.vodId = vodInfo.id
            record.addedTime = System.currentTimeMillis()
        }
        record.name = vodInfo.name
        record.pic = vodInfo.pic
        record.updateDays = FollowDays.encode(days)
        record.updateHour = hour.coerceIn(0, 23)
        dao.insert(record)
    }

    override suspend fun deleteSelected(ids: List<Int>) {
        if (ids.isEmpty()) return
        follows().deleteSelected(ids)
    }
}
