package com.github.tvbox.osc.data

import android.text.TextUtils
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.HistoryMerge
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.google.gson.ExclusionStrategy
import com.google.gson.FieldAttributes
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface HistoryRepository {

    suspend fun getVodInfo(sourceKey: String, vodId: String): VodInfo?

    fun observeVodRecords(cid: String?): Flow<List<VodInfo>>

    suspend fun sweepDuplicates()

    suspend fun insertVodRecord(sourceKey: String, vodInfo: VodInfo)

    suspend fun touchVodRecord(sourceKey: String, vodId: String): Boolean

    suspend fun deleteVodRecord(sourceKey: String?, vodInfo: VodInfo)

    suspend fun deleteVodRecordAll()
}

internal class RoomHistoryRepository(
    private val records: () -> VodRecordDao,
) : HistoryRepository {

    private val vodInfoStrategy: ExclusionStrategy = object : ExclusionStrategy {
        override fun shouldSkipField(field: FieldAttributes): Boolean {
            if (field.declaringClass == VodInfo::class.java && field.name == "seriesFlags") {
                return true
            }
            if (field.declaringClass == VodInfo::class.java && field.name == "seriesMap") {
                return true
            }
            if (field.declaringClass == VodInfo::class.java && field.name == "updateTime") {
                return true
            }
            return false
        }

        override fun shouldSkipClass(clazz: Class<*>): Boolean = false
    }

    private fun vodInfoGson(): Gson =
        GsonBuilder().addSerializationExclusionStrategy(vodInfoStrategy).create()

    private fun vodInfoType(): Type = object : TypeToken<VodInfo>() {}.type

    override suspend fun getVodInfo(sourceKey: String, vodId: String): VodInfo? {
        val record = records().getVodRecord(CurrentSubscription.cid(), sourceKey, vodId)
        try {
            if (record != null && record.dataJson != null && !TextUtils.isEmpty(record.dataJson)) {
                val vodInfo = vodInfoGson().fromJson<VodInfo>(record.dataJson, vodInfoType())
                if (vodInfo.name == null) return null
                return vodInfo
            }
        } catch (e: Exception) {
            LOG.e("RoomHistoryRepository", e)
        }
        return null
    }

    override fun observeVodRecords(cid: String?): Flow<List<VodInfo>> =
        records().observeAll(cid).map { recordList ->
            val hisNum = HistoryHelper.getHisNum(KV.get(HawkConfig.HISTORY_NUM, 0))
            recordList.take(hisNum).mapNotNull { parseRecord(it) }
        }

    private fun parseRecord(record: VodRecord): VodInfo? {
        try {
            if (record.dataJson != null && !TextUtils.isEmpty(record.dataJson)) {
                val parsed = vodInfoGson().fromJson<VodInfo>(record.dataJson, vodInfoType())
                if (parsed.name != null) {
                    return parsed.copy(sourceKey = record.sourceKey, updateTime = record.updateTime)
                }
            }
        } catch (e: Exception) {
            LOG.e("RoomHistoryRepository", e)
        }
        return null
    }

    override suspend fun sweepDuplicates() {
        if (!HistoryMerge.isEnabled()) return
        val dao = records()
        val cid = CurrentSubscription.cid()
        val all = dao.getAll(cid, Int.MAX_VALUE).mapNotNull { parseRecord(it) }
        if (all.isEmpty()) return
        val (_, dropped) = HistoryMerge.dedupe(all) { it.name }
        if (dropped.isEmpty()) return
        dropped.forEach { info ->
            dao.getVodRecord(cid, info.sourceKey, info.id)?.let { dao.delete(it) }
        }
    }

    override suspend fun insertVodRecord(sourceKey: String, vodInfo: VodInfo) {
        if (HistoryHelper.isIncognito()) return
        val cid = CurrentSubscription.cid()
        val dao = records()
        val record = dao.getVodRecord(cid, sourceKey, vodInfo.id) ?: VodRecord()
        record.cid = cid
        record.sourceKey = sourceKey
        record.vodId = vodInfo.id
        record.updateTime = System.currentTimeMillis()
        record.dataJson = vodInfoGson().toJson(vodInfo)
        dao.insert(record)
        val hisNum = HistoryHelper.getHisNum(KV.get(HawkConfig.HISTORY_NUM, 0))
        if (dao.getCount(cid) > hisNum) {
            dao.reserver(cid, hisNum)
        }
    }

    override suspend fun touchVodRecord(sourceKey: String, vodId: String): Boolean {
        if (HistoryHelper.isIncognito()) return false
        return records().touch(CurrentSubscription.cid(), sourceKey, vodId, System.currentTimeMillis()) > 0
    }

    override suspend fun deleteVodRecord(sourceKey: String?, vodInfo: VodInfo) {
        val dao = records()
        val record = dao.getVodRecord(CurrentSubscription.cid(), sourceKey, vodInfo.id)
        if (record != null) {
            dao.delete(record)
        }
    }

    override suspend fun deleteVodRecordAll() {
        records().deleteAll(CurrentSubscription.cid())
    }
}
