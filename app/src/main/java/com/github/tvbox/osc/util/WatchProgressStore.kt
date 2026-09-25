package com.github.tvbox.osc.util

import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.cache.CacheManager
import com.github.tvbox.osc.player.PlaybackService
import java.util.concurrent.ConcurrentHashMap

/**
 * 续播点(app 侧进度)的唯一写入/清除出口:判据与护栏只写在一处,分散在多处等于没加。
 * 进度键是"源+片+线路+集+集名"无分隔符拼接后取 MD5,**反推不出归属**,owner(源|片id)只能由调用方给。
 */
object WatchProgressStore {

    /** 落盘在主线程、级联清理在历史页 IO 协程,两边可能并发读改写同一条索引;锁序只允许 indexLock → PlaybackProgress/EpisodeTotals */
    private val indexLock = Any()

    /** 每部片最近一拍的真实时长:释放内核那一刻读不到时长,靠它判"看完即清";每秒 tick 会读它,故不加 indexLock */
    private val lastDurations = ConcurrentHashMap<String, Long>()

    /** 容量兜底的节流:上限不是实时约束,不值得每次落盘都枚举整张键表 + 解析全部载荷 */
    private const val TRIM_INTERVAL_MS = 60_000L

    private var lastTrimAt = 0L

    /** 被删掉历史的缓存键(MD5,与落盘同形):播放器里那份还停在旧位置,退出/释放时的回写必须丢弃 */
    private val discardedKeys: MutableSet<String> = HashSet()

    /** 索引归属键,与 PlaybackProgress.key / EpisodeTotals.key / HistoryPage.key 同构 */
    @JvmStatic
    fun ownerOf(vod: VodInfo?): String? {
        if (vod == null) return null
        val source = vod.sourceKey
        val id = vod.id
        if (source.isNullOrEmpty() || id.isNullOrEmpty()) return null
        return source + "|" + id
    }

    /**
     * 落盘当前进度。[positionMs] <= 0 是"清除"而非"位置为 0"(见 VideoView.onCompletion);
     * 无痕只拦"写新的",清除一律执行,否则旧续播点会永远留着。owner 为空时只落进度、不维护索引。
     */
    @JvmStatic
    fun save(owner: String?, progressKey: String?, positionMs: Long, durationMs: Long) {
        if (progressKey.isNullOrEmpty()) return
        if (positionMs <= 0) {
            clear(owner, progressKey)
            return
        }
        if (isDiscarded(progressKey)) return
        when (WatchProgressRules.decide(positionMs, resolveDuration(owner, durationMs))) {
            WatchDecision.SAVE -> {
                if (HistoryHelper.isIncognito()) return
                CacheManager.save(md5(progressKey), positionMs)
                remember(owner, progressKey)
            }
            WatchDecision.CLEAR -> {
                CacheManager.delete(md5(progressKey), 0L)
                forget(owner, progressKey)
            }
            WatchDecision.SKIP -> Unit
        }
    }

    /** 记下本拍的真实时长(每秒 tick 调用,owner = 源|片id) */
    @JvmStatic
    fun noteDuration(owner: String?, durationMs: Long) {
        if (owner.isNullOrEmpty() || durationMs <= 0) return
        synchronized(indexLock) { lastDurations[owner] = durationMs }
    }

    /**
     * 时长兜底:释放内核的落盘(见 VideoView.release)发生在 mMediaPlayer 置空之后,那一刻 getDuration() 读作 0,
     * 于是"看完即清"会退化成只剩绝对 30 秒的 SAVE,把刚清掉的续播点以片尾位置写回。用同片的上一拍时长补上。
     */
    private fun resolveDuration(owner: String?, durationMs: Long): Long {
        if (durationMs > 0) return durationMs
        if (owner.isNullOrEmpty()) return 0L
        synchronized(indexLock) { return lastDurations[owner] ?: 0L }
    }

    /** 清除某条进度(重播/播出完成/离开该集);任何模式下都执行 */
    @JvmStatic
    fun clear(owner: String?, progressKey: String?) {
        if (progressKey.isNullOrEmpty()) return
        CacheManager.delete(md5(progressKey), 0L)
        forget(owner, progressKey)
    }

    /**
     * 换源/换线"接着看":把 [fromKey] 的位置继承给 [toKey],目标键已有记录则不覆盖
     * (回滚原源时不该被别的源的位置顶掉);继承位置同样过判据,时长未知按绝对阈值。
     */
    @JvmStatic
    fun inherit(owner: String?, fromKey: String?, toKey: String?, positionMs: Long) {
        if (fromKey.isNullOrEmpty() || toKey.isNullOrEmpty()) return
        if (fromKey == toKey) return
        if (positionMs <= 0) return
        if (HistoryHelper.isIncognito()) return
        if (isDiscarded(toKey)) return
        if (WatchProgressRules.decide(positionMs, 0L) == WatchDecision.SKIP) return
        if (CacheManager.getCache(md5(toKey)) != null) return
        CacheManager.save(md5(toKey), positionMs)
        remember(owner, toKey)
    }

    /** 用户删单条历史:该片进度键 + 索引 + 百分比 + 集数一起清(主动操作,不受无痕拦截) */
    @JvmStatic
    fun clearOwner(owner: String?) {
        if (owner.isNullOrEmpty()) return
        val cleared = synchronized(indexLock) { clearTitleLocked(owner) }
        discard(cleared)
        // eps=0 = 该片本就没有索引(存量进度未建索引),不是漏清
        LOG.i("echo-progress clear-owner owner=" + owner + " eps=" + cleared.size)
        PlaybackService.peek()?.discardStartedContentOf(listOf(owner))
        PlaybackProgress.forget(owner)
        EpisodeTotals.remove(owner)
    }

    /** 该集重新起播:删除时下的作废到此为止,之后的进度照常记(否则重看一遍也不会再记) */
    @JvmStatic
    fun onPlayStart(progressKey: String?) {
        if (progressKey.isNullOrEmpty()) return
        synchronized(indexLock) { discardedKeys.remove(md5(progressKey)) }
    }

    /** 作废内存里那一份的写入权:同片接管只看归属、不看记录,会直接接着旧位置播,必须连它一起作废 */
    private fun discard(progressKeys: List<String>) {
        if (progressKeys.isEmpty()) return
        synchronized(indexLock) { progressKeys.forEach { discardedKeys.add(md5(it)) } }
    }

    /** 入参已是缓存键(MD5):兜底扫描拿不到原始进度键,只能这样登记 */
    private fun discardHashed(cacheKeys: List<String>) {
        if (cacheKeys.isEmpty()) return
        synchronized(indexLock) { discardedKeys.addAll(cacheKeys) }
    }

    private fun isDiscarded(progressKey: String): Boolean =
        synchronized(indexLock) { discardedKeys.contains(md5(progressKey)) }

    /** 清空历史:全部片级联 + 两张快照整体清空(快照直接整张清,不逐片读改写) */
    @JvmStatic
    fun clearAll() {
        var titles = 0
        val owners = ArrayList<String>()
        val keys = ArrayList<String>()
        synchronized(indexLock) {
            for (indexKey in KV.keys(WatchProgressIndex.KEY_PREFIX)) {
                titles++
                val owner = WatchProgressIndex.ownerOf(indexKey)
                owners.add(owner)
                keys.addAll(clearTitleLocked(owner))
            }
        }
        discard(keys)
        PlaybackService.peek()?.discardStartedContentOf(owners)
        // 索引清完再兜底扫一遍:没有索引条目的存量进度(键是 MD5,反推不出归属)只能这样清
        val stale = CacheManager.clearAllProgress()
        discardHashed(stale)
        LOG.i("echo-progress clear-all titles=" + titles + " eps=" + keys.size + " stale=" + stale.size)
        PlaybackProgress.forgetAll()
        EpisodeTotals.removeAll()
    }

    /** 只清该片的进度键与索引;百分比/集数由调用方按 owner 单独移除。返回清掉的集键(调用方据此作废) */
    private fun clearTitleLocked(owner: String): List<String> {
        val indexKey = WatchProgressIndex.keyOf(owner)
        val eps = WatchProgressIndex.decode(KV.get(indexKey, ""))?.eps.orEmpty()
        eps.forEach { CacheManager.delete(md5(it), 0L) }
        KV.delete(indexKey)
        return eps
    }

    /** 记住"这部片写过这一集":追加集键 + 刷新活跃时间,顺带做一次容量兜底 */
    private fun remember(owner: String?, ep: String) {
        if (owner.isNullOrEmpty()) return
        synchronized(indexLock) {
            val key = WatchProgressIndex.keyOf(owner)
            KV.put(key, WatchProgressIndex.withEp(KV.get(key, ""), ep, System.currentTimeMillis()))
            trimCapacityLocked(owner)
        }
    }

    private fun forget(owner: String?, ep: String) {
        if (owner.isNullOrEmpty()) return
        synchronized(indexLock) {
            val key = WatchProgressIndex.keyOf(owner)
            val payload = WatchProgressIndex.withoutEp(KV.get(key, ""), ep)
            if (payload == null) KV.delete(key) else KV.put(key, payload)
        }
    }

    /**
     * 回收:片数超 [WatchProgressIndex.MAX_TITLES] 时按最后活跃时间淘汰最旧的一批(刚写入的那部绝不动),
     * 并把两张快照对齐到"索引里还有的片 + 正在看的那部"。按 [TRIM_INTERVAL_MS] 节流(稳态不枚举键表)。
     */
    private fun trimCapacityLocked(justSavedOwner: String) {
        val now = System.currentTimeMillis()
        if (now - lastTrimAt < TRIM_INTERVAL_MS) return
        lastTrimAt = now
        val indexKeys = KV.keys(WatchProgressIndex.KEY_PREFIX)
        val entries = ArrayList<Pair<String, Long>>(indexKeys.size)
        for (key in indexKeys) {
            val owner = WatchProgressIndex.ownerOf(key)
            entries.add(owner to (WatchProgressIndex.decode(KV.get(key, ""))?.at ?: 0L))
        }
        // 快照与索引同口径:各留 300 条会积下"进度已被回收、卡片还挂着"的孤儿,淘汰也挑不到最旧的
        val keep = entries.mapTo(HashSet(entries.size + 1)) { it.first }
        val currentOwner = App.getInstance().vodInfo?.let { ownerOf(it) }
        if (!currentOwner.isNullOrEmpty()) keep.add(currentOwner)
        PlaybackProgress.retain(keep)
        EpisodeTotals.retain(keep)
        val evicted = WatchProgressIndex.pickEvictions(entries, WatchProgressIndex.MAX_TITLES)
            .filter { it != justSavedOwner && it != currentOwner }
        if (evicted.isEmpty()) return
        // 与删历史同构:作废内存里那份并解除接管归属。漏了解除,被淘汰的片重进会被接管(它跳过起播),
        // 作废登记就永远没有解除机会 —— 那部片之后写的进度会被一直丢弃
        PlaybackService.peek()?.discardStartedContentOf(evicted)
        for (owner in evicted) {
            val keys = clearTitleLocked(owner)
            discard(keys)
            LOG.i("echo-progress evict owner=" + owner + " eps=" + keys.size)
            PlaybackProgress.forget(owner)
            EpisodeTotals.remove(owner)
        }
    }

    private fun md5(key: String): String = MD5.string2MD5(key)
}
