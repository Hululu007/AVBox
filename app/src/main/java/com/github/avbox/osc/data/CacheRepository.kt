package com.github.avbox.osc.data

import com.github.avbox.osc.util.AppLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface CacheRepository {

    suspend fun get(key: String?): Any?

    suspend fun save(key: String, body: Any?)

    suspend fun delete(key: String?, body: Any?)

    suspend fun clearAllProgress(): List<String>
}

internal class RoomCacheRepository(
    private val caches: () -> CacheDao,
) : CacheRepository {

    override suspend fun get(key: String?): Any? {
        val cache = caches().getCache(key) ?: return null
        val data = cache.data ?: return null
        return withContext(Dispatchers.IO) { toObject(data) }
    }

    override suspend fun save(key: String, body: Any?) {
        val cache = CacheEntry()
        cache.key = key
        cache.data = withContext(Dispatchers.IO) { toByteArray(body) }
        caches().save(cache)
    }

    override suspend fun delete(key: String?, body: Any?) {
        if (key == null) return
        val cache = CacheEntry()
        cache.key = key
        cache.data = withContext(Dispatchers.IO) { toByteArray(body) }
        caches().delete(cache)
    }

    override suspend fun clearAllProgress(): List<String> {
        val dao = caches()
        val rows = dao.getAll()
        val progressRows = withContext(Dispatchers.IO) {
            rows.filter { row -> row.data?.let { toObject(it) is Long } == true }
        }
        val removed = ArrayList<String>(progressRows.size)
        for (row in progressRows) {
            dao.delete(row)
            removed.add(row.key)
        }
        return removed
    }

    private fun toObject(data: ByteArray): Any? {
        var bais: ByteArrayInputStream? = null
        var ois: ObjectInputStream? = null
        try {
            bais = ByteArrayInputStream(data)
            ois = ObjectInputStream(bais)
            return ois.readObject()
        } catch (e: Exception) {
            AppLog.e("RoomCacheRepository", e)
        } finally {
            try {
                bais?.close()
                ois?.close()
            } catch (ignore: Exception) {
                AppLog.e("RoomCacheRepository", ignore)
            }
        }
        return null
    }

    private fun toByteArray(body: Any?): ByteArray {
        var baos: ByteArrayOutputStream? = null
        var oos: ObjectOutputStream? = null
        try {
            baos = ByteArrayOutputStream()
            oos = ObjectOutputStream(baos)
            oos.writeObject(body)
            oos.flush()
            return baos.toByteArray()
        } catch (e: Exception) {
            AppLog.e("RoomCacheRepository", e)
        } finally {
            try {
                baos?.close()
                oos?.close()
            } catch (e: Exception) {
                AppLog.e("RoomCacheRepository", e)
            }
        }
        return ByteArray(0)
    }
}
