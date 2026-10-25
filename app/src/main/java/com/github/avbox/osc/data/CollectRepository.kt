package com.github.avbox.osc.data

import com.github.avbox.osc.bean.VodInfo
import kotlinx.coroutines.flow.Flow

interface CollectRepository {

    fun currentCid(): String

    suspend fun isVodCollect(sourceKey: String?, vodId: String?): Boolean

    suspend fun allVodCollect(): List<VodCollect>

    fun observeAll(): Flow<List<VodCollect>>

    suspend fun insertVodCollect(sourceKey: String?, vodInfo: VodInfo)

    suspend fun deleteVodCollect(sourceKey: String?, vodInfo: VodInfo)

    suspend fun deleteVodCollect(id: Int)

    suspend fun deleteVodCollectAll()
}

internal class RoomCollectRepository(
    private val collects: () -> VodCollectDao,
) : CollectRepository {

    override fun currentCid(): String = CurrentSubscription.cid()

    override suspend fun isVodCollect(sourceKey: String?, vodId: String?): Boolean {
        val record = collects().getVodCollect(CurrentSubscription.cid(), sourceKey, vodId)
        return record != null
    }

    override suspend fun allVodCollect(): List<VodCollect> = collects().getAll()

    override fun observeAll(): Flow<List<VodCollect>> = collects().observeAll()

    override suspend fun insertVodCollect(sourceKey: String?, vodInfo: VodInfo) {
        val cid = CurrentSubscription.cid()
        val dao = collects()
        if (dao.getVodCollect(cid, sourceKey, vodInfo.id) != null) {
            return
        }
        val record = VodCollect()
        record.cid = cid
        record.sourceKey = sourceKey
        record.vodId = vodInfo.id
        record.updateTime = System.currentTimeMillis()
        record.name = vodInfo.name
        record.pic = vodInfo.pic
        dao.insert(record)
    }

    override suspend fun deleteVodCollect(id: Int) {
        collects().delete(id)
    }

    override suspend fun deleteVodCollect(sourceKey: String?, vodInfo: VodInfo) {
        val dao = collects()
        val record = dao.getVodCollect(CurrentSubscription.cid(), sourceKey, vodInfo.id)
        if (record != null) {
            dao.delete(record)
        }
    }

    override suspend fun deleteVodCollectAll() {
        collects().deleteAll()
    }
}
