package com.github.avbox.osc.player.engine.iso

import com.github.avbox.osc.util.AppLog
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

data class IsoPlayRun(val offset: Long, val length: Long)

data class IsoPlayTarget(
    val runs: List<IsoPlayRun>,
    val m2tsLength: Long,
    val name: String,
    val startOffset: Long = 0L,
    val durationMs: Long = 0L,
    val playlistRuns: List<IsoPlayRun>? = null,
    val playlistOffset: Long = 0L,
    val playlistLength: Long = 0L,
) {
    constructor(
        m2tsOffset: Long,
        m2tsLength: Long,
        name: String,
        startOffset: Long = 0L,
        durationMs: Long = 0L,
    ) : this(listOf(IsoPlayRun(m2tsOffset, m2tsLength)), m2tsLength, name, startOffset, durationMs)

    val m2tsOffset: Long get() = runs.firstOrNull()?.offset ?: 0L

    val segmentLength: Long get() = (m2tsLength - startOffset).coerceAtLeast(0L)

    val availableLength: Long get() =
        if (playlistRuns != null) (playlistLength - playlistOffset - startOffset).coerceAtLeast(0L)
        else segmentLength
}

internal fun coalesceRuns(extents: List<IsoExtent>): List<IsoPlayRun> {
    if (extents.isEmpty()) return emptyList()
    val runs = ArrayList<IsoPlayRun>(extents.size)
    var current: IsoPlayRun? = null
    for (extent in extents) {
        if (extent.length <= 0L) continue
        val open = current
        current = if (open != null && open.offset + open.length == extent.offset) {
            IsoPlayRun(open.offset, open.length + extent.length)
        } else {
            if (open != null) runs.add(open)
            IsoPlayRun(extent.offset, extent.length)
        }
    }
    current?.let { runs.add(it) }
    return runs
}

data class IsoSelection(
    val segments: List<IsoPlayTarget>,
    val playlistName: String?,
    val durationMs: Long,
    val baseMs: Long = 0L,
) {
    val primary: IsoPlayTarget get() = segments.first()

    val totalLength: Long get() = segments.sumOf { it.segmentLength }

    val multiSegment: Boolean get() = segments.size > 1
}

internal fun IsoSelection.withPlaylistTargets(): List<IsoPlayTarget> {
    val runs = segments.flatMap { it.runs }
    val total = runs.sumOf { it.length }
    var offset = 0L
    return segments.map { segment ->
        val copy = segment.copy(
            playlistRuns = runs,
            playlistOffset = offset,
            playlistLength = total,
        )
        offset += segment.runs.sumOf { it.length }
        copy
    }
}

internal fun matchPlaylistSegments(
    playlist: IsoPlaylist,
    targets: List<IsoPlayTarget>,
): List<IsoPlayTarget>? {
    if (playlist.segments.isEmpty()) return null
    val byName = HashMap<String, IsoPlayTarget>(targets.size)
    for (target in targets) byName[target.name.lowercase(Locale.ROOT)] = target
    val ordered = ArrayList<IsoPlayTarget>(playlist.segments.size)
    for (segment in playlist.segments) {
        val name = "${segment.clipId}.m2ts".lowercase(Locale.ROOT)
        val target = byName[name] ?: return null
        val duration = segment.durationMs
        ordered.add(if (duration > 0L) target.copy(durationMs = duration) else target)
    }
    return ordered
}

internal fun alignSelectionToAccessPoint(
    segments: List<IsoPlayTarget>,
    totalDurationMs: Long,
    playlistName: String?,
    seekMs: Long,
    accessPoints: (String) -> ClpiIndex?,
): IsoSelection? {
    if (segments.isEmpty() || seekMs <= 0L) return null
    var start = 0L
    for (index in segments.indices) {
        val segment = segments[index]
        val duration = segment.durationMs
        if (duration <= 0L) return null
        val end = start + duration
        if (seekMs < end || index == segments.lastIndex) {
            val clipId = segment.name.substringBeforeLast('.').lowercase(Locale.ROOT)
            val clip = accessPoints(clipId) ?: return null
            val first = clip.accessPoints.firstOrNull() ?: return null
            val local = (seekMs - start).coerceIn(0L, (duration - 1L).coerceAtLeast(0L))
            val targetTicks = first.ptsTicks + local * CLPI_TICKS_PER_MILLI
            val point = clip.accessPointAt(targetTicks) ?: first
            val delta = ((point.ptsTicks - first.ptsTicks) / CLPI_TICKS_PER_MILLI).coerceAtLeast(0L)
            val head = segment.copy(
                startOffset = point.packetOffset,
                durationMs = (duration - delta).coerceAtLeast(0L),
            )
            return IsoSelection(
                listOf(head) + segments.drop(index + 1),
                playlistName,
                totalDurationMs,
                start + delta,
            )
        }
        start = end
    }
    return null
}

class IsoLocator(private val volume: IsoFileSource) {

    private val clipIndexes = ConcurrentHashMap<String, ClpiIndex>()

    private val clipLocks = ConcurrentHashMap<String, Any>()

    internal var selectedStreams: List<IsoStream> = emptyList()
        private set

    internal var selectedVideoPid: Int? = null
        private set

    private val bdmvEntry: IsoEntry by lazy { locateBdmv() }

    fun locate(): IsoPlayTarget = locateSelection().primary

    fun locateSelection(): IsoSelection {
        val bdmv = bdmvEntry
        if (!bdmv.directory) throw IsoFormatException("BDMV is not a directory")
        val stream = volume.find(bdmv, STREAM)
            ?: throw IsoFormatException("BDMV/$STREAM directory missing")
        if (!stream.directory) throw IsoFormatException("$STREAM is not a directory")
        val candidates = volume.list(stream).filter { !it.directory && it.name.lowercase(Locale.ROOT).endsWith(M2TS) }
        if (candidates.isEmpty()) throw IsoFormatException("no m2ts file found")
        val largest = candidates.maxByOrNull { it.length }
            ?: throw IsoFormatException("no m2ts file found")
        AppLog.i(
            "echo-iso-m2ts:count=${candidates.size}:chosen=${largest.name}:${largest.length}:" +
                "top=${candidates.sortedByDescending { it.length }.take(TOP_N).joinToString(",") { "${it.name}:${it.length}" }}",
        )
        val targets = ArrayList<IsoPlayTarget>(candidates.size)
        for (candidate in candidates) {
            val target = toTarget(candidate) ?: continue
            targets.add(target)
        }
        val fallback = targets.firstOrNull { it.name == largest.name }
            ?: toTarget(largest)
            ?: throw IsoFormatException("m2ts has no extents: ${largest.name}")
        val playlists = probePlaylists(bdmv)
        val chosen = pickLongestPlaylist(playlists)
        if (chosen != null) {
            val segments = matchPlaylistSegments(chosen, targets)
            if (segments == null) {
                AppLog.i("echo-iso-playlist-skip:${chosen.name}:reason=segment-missing")
            } else {
                val selection = IsoSelection(segments, chosen.name, chosen.durationMs)
                AppLog.i(
                    "echo-iso-playlist:${chosen.name}:segments=${segments.size}:" +
                        "duration=${chosen.durationMs}:unique=${uniqueDurationMs(chosen)}:" +
                        "total=${selection.totalLength}:first=${segments.first().name}",
                )
                selectedStreams = chosen.streams
                selectedVideoPid = chosen.videoPid
                if (chosen.streams.isNotEmpty()) {
                    AppLog.i(
                        "echo-iso-streams:" + chosen.streams.joinToString(",") { "${it.pid}:${it.language}" },
                    )
                }
                return selection
            }
        }
        selectedStreams = emptyList()
        selectedVideoPid = null
        return IsoSelection(listOf(fallback), null, 0L)
    }

    fun locateSeek(selection: IsoSelection, seekMs: Long): IsoSelection? =
        alignSelectionToAccessPoint(
            selection.segments,
            selection.durationMs,
            selection.playlistName,
            seekMs,
        ) { clipId -> accessPoints(clipId) }

    internal fun accessPoints(clipId: String): ClpiIndex? {
        val key = clipId.lowercase(Locale.ROOT)
        clipIndexes[key]?.let { return it }
        val fresh = Any()
        val lock = clipLocks.putIfAbsent(key, fresh) ?: fresh
        return synchronized(lock) {
            clipIndexes[key]?.let { return@synchronized it }
            val index = loadClipIndex(key) ?: return@synchronized null
            clipIndexes[key] = index
            index
        }
    }

    private fun loadClipIndex(key: String): ClpiIndex? {
        val bdmv = try {
            bdmvEntry
        } catch (e: IsoFormatException) {
            return null
        }
        val dir = volume.find(bdmv, CLIPINF) ?: return null
        if (!dir.directory) return null
        val want = "$key$CLPI"
        val entry = volume.list(dir).firstOrNull { it.name.lowercase(Locale.ROOT) == want } ?: return null
        val data = try {
            volume.readEntry(entry)
        } catch (e: IsoFormatException) {
            AppLog.i("echo-iso-clpi-read:${entry.name}:${e.message}")
            return null
        }
        val index = parseClpi(entry.name, data)
        if (index == null) {
            AppLog.i("echo-iso-clpi-parse:${entry.name}:${data.size}")
            return null
        }
        val first = index.accessPoints.first()
        AppLog.i("echo-iso-clpi:${entry.name}:rap=${index.accessPoints.size}:first=${first.ptsTicks}:spn=${index.sourcePackets}")
        return index
    }

    private fun locateBdmv(): IsoEntry {
        val root = volume.root()
        val direct = try {
            volume.find(root, BDMV)
        } catch (e: IsoFormatException) {
            dumpRoot(root)
            throw e
        }
        if (direct != null) return direct
        val children = try {
            volume.list(root)
        } catch (e: IsoFormatException) {
            dumpRoot(root)
            throw e
        }
        for (child in children) {
            if (!child.directory) continue
            val nested = try {
                volume.find(child, BDMV)
            } catch (e: IsoFormatException) {
                continue
            }
            if (nested != null) {
                AppLog.i("echo-iso-bdmv:nested=${child.name}")
                return nested
            }
        }
        dumpRoot(root)
        throw IsoFormatException("BDMV directory missing")
    }

    private fun dumpRoot(root: IsoEntry) {
        val children = try {
            volume.list(root)
        } catch (e: IsoFormatException) {
            AppLog.i("echo-iso-root:error=${e.message}")
            return
        }
        AppLog.i(
            "echo-iso-root:count=${children.size}:" +
                children.take(ROOT_LOG_LIMIT).joinToString(",") {
                    "${it.name}:${it.length}:${if (it.directory) 1 else 0}"
                },
        )
    }

    private fun toTarget(entry: IsoEntry): IsoPlayTarget? {
        if (entry.length <= 0) {
            AppLog.i("echo-iso-target-skip:${entry.name}:reason=empty")
            return null
        }
        val runs = coalesceRuns(entry.extents)
        if (runs.isEmpty()) {
            AppLog.i("echo-iso-target-skip:${entry.name}:reason=no-extents")
            return null
        }
        var total = 0L
        for (run in runs) total += run.length
        if (total < entry.length) {
            AppLog.i("echo-iso-target-skip:${entry.name}:reason=short:$total/${entry.length}")
            return null
        }
        if (runs.size > MAX_TARGET_RUNS) {
            AppLog.i("echo-iso-target-skip:${entry.name}:reason=runs:${runs.size}")
            return null
        }
        if (runs.size > 1) {
            AppLog.i(
                "echo-iso-target-runs:${entry.name}:${runs.size}:" +
                    runs.joinToString(",") { "${it.offset}+${it.length}" },
            )
        }
        return IsoPlayTarget(runs, entry.length, entry.name)
    }

    private fun probePlaylists(bdmv: IsoEntry): List<IsoPlaylist> {
        val playlistDir = volume.find(bdmv, PLAYLIST)
        if (playlistDir == null || !playlistDir.directory) {
            AppLog.i("echo-iso-mpls:none dirMissing=${playlistDir == null}")
            return emptyList()
        }
        val dirStarted = System.currentTimeMillis()
        val all = volume.list(playlistDir)
            .filter { !it.directory && it.name.lowercase(Locale.ROOT).endsWith(MPLS) }
            .sortedByDescending { it.length }
        val entries = if (all.size <= FULL_SCAN_LIMIT) all else {
            val preferred = all.filter { it.name.lowercase(Locale.ROOT) in MAIN_PLAYLISTS }
            (preferred + all.take(MAX_PLAYLISTS)).distinctBy { it.name }
        }
        val dirMs = System.currentTimeMillis() - dirStarted
        if (entries.isEmpty()) {
            AppLog.i("echo-iso-mpls:none emptyDir")
            return emptyList()
        }
        val fastTargets = entries.filter { it.name.lowercase(Locale.ROOT) in MAIN_PLAYLISTS }
        val covered = HashSet<String>()
        if (fastTargets.isNotEmpty() && fastTargets.size < entries.size) {
            if (prefetchPlaylists(fastTargets)) covered += fastTargets.map { it.name }
            val fastStarted = System.currentTimeMillis()
            val fastPayloads = IsoMplsFetch.load(fastTargets) { entry ->
                val data = volume.readEntry(entry)
                MplFetchResult(data, parseMplPlaylist(entry.name, data))
            }
            val fastParsed = fastTargets.indices.mapNotNull { fastPayloads[it]?.playlist }
            val fastBest = pickLongestPlaylist(fastParsed)
            if (fastBest != null && uniqueDurationMs(fastBest) >= MIN_MAIN_DURATION_MS) {
                AppLog.i(
                    "echo-iso-mpls-fast:${fastBest.name}:${uniqueDurationMs(fastBest)}:" +
                        "segments=${fastBest.segments.size}:dir=$dirMs:" +
                        "fetch=${System.currentTimeMillis() - fastStarted}",
                )
                return fastParsed
            }
        }
        val parsed = ArrayList<IsoPlaylist>(entries.size)
        val skipped = ArrayList<String>()
        var rawLogged = 0
        var skipLogged = 0
        var unreadable = 0
        val fetchStarted = System.currentTimeMillis()
        prefetchPlaylists(mplPrefetchCandidates(entries, covered))
        val payloads = IsoMplsFetch.load(entries) { entry ->
            val data = volume.readEntry(entry)
            MplFetchResult(data, parseMplPlaylist(entry.name, data))
        }
        val fetchMs = System.currentTimeMillis() - fetchStarted
        for (index in entries.indices) {
            val entry = entries[index]
            val result = payloads[index]
            if (result == null) {
                unreadable++
                if (skipLogged < MAX_SKIP_LOG) {
                    AppLog.i("echo-iso-mpls-skip:${entry.name}:reason=read")
                    skipLogged++
                }
                skipped.add(entry.name)
                continue
            }
            if (result.playlist == null) {
                if (rawLogged < MAX_RAW_LOG) {
                    AppLog.i("echo-iso-mpls-raw:${entry.name}:len=${result.bytes.size}")
                    dumpHex(entry.name, result.bytes)
                    rawLogged++
                }
                skipped.add(entry.name)
            } else {
                parsed.add(result.playlist)
            }
        }
        if (unreadable > skipLogged) {
            AppLog.i("echo-iso-mpls-skip-more:${unreadable - skipLogged}")
        }
        parsed.sortByDescending { uniqueDurationMs(it) }
        val summary = parsed.take(TOP_N).joinToString(";") {
            "${it.name}=${it.segments.size}:${it.durationMs}:${uniqueDurationMs(it)}"
        }
        val chosen = pickLongestPlaylist(parsed)
        AppLog.i(
            "echo-iso-mpls:count=${all.size}:scanned=${entries.size}:parsed=${parsed.size}:skipped=${skipped.size}:" +
                "chosen=${chosen?.name}:items=${chosen?.segments?.size}:" +
                "duration=${chosen?.durationMs}:unique=${chosen?.let(::uniqueDurationMs)}:" +
                "first=${chosen?.segments?.firstOrNull()?.clipId}:" +
                "in=${chosen?.segments?.firstOrNull()?.inTimeUs}:out=${chosen?.segments?.firstOrNull()?.outTimeUs}" +
                ":top=$summary:fetch=$fetchMs:dir=$dirMs",
        )
        return parsed
    }

    private fun prefetchPlaylists(entries: List<IsoEntry>): Boolean {
        if (entries.isEmpty()) return false
        val extents = entries.flatMap { it.extents }
        val payload = extents.sumOf { it.length }
        val plan = mplPrefetchPlan(extents)
        if (plan.windows.isEmpty()) {
            val span = if (extents.isEmpty()) {
                0L
            } else {
                extents.maxOf { it.offset + it.length } - extents.minOf { it.offset }
            }
            AppLog.i("echo-iso-mpls-prefetch:0:$span:$payload:0:${plan.reason}")
            return false
        }
        var loaded = 0
        var expired = 0
        for (window in plan.windows) {
            val result = IsoMplsFetch.runWithBudget(PREFETCH_BUDGET_MS) {
                try {
                    volume.prefetch(window.offset, window.length)
                } catch (e: Exception) {
                    AppLog.i("echo-iso-mpls-prefetch-fail:${window.offset}:${e.message}")
                    false
                }
            }
            when (result) {
                null -> expired++
                true -> loaded++
                false -> Unit
            }
            if (expired > 0) break
        }
        AppLog.i(
            "echo-iso-mpls-prefetch:${plan.windows.size}:" +
                "${plan.windows.sumOf { it.length.toLong() }}:$payload:$loaded:${plan.reason}",
        )
        if (expired > 0) AppLog.i("echo-iso-mpls-prefetch-timeout:$expired")
        return loaded == plan.windows.size
    }

    private fun dumpRange(name: String, data: ByteArray, from: Int, limit: Int) {
        if (from < 0 || from >= data.size || limit <= 0) return
        val end = minOf(data.size, from + limit)
        var offset = from
        while (offset < end) {
            val chunkEnd = minOf(offset + HEX_BYTES_PER_LINE, end)
            AppLog.i("echo-iso-hex:$name:$offset:${hexRange(data, offset, chunkEnd)}")
            offset = chunkEnd
        }
    }

    private fun be32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun dumpHex(name: String, data: ByteArray) {
        dumpRange(name, data, 0, HEAD_HEX_BYTES)
    }

    private fun hexRange(data: ByteArray, from: Int, to: Int): String {
        val sb = StringBuilder((to - from) * 2)
        for (index in from until to) {
            sb.append(HEX_DIGITS[(data[index].toInt() shr 4) and 0x0F])
            sb.append(HEX_DIGITS[data[index].toInt() and 0x0F])
        }
        return sb.toString()
    }

    private companion object {
        const val BDMV = "BDMV"
        const val STREAM = "STREAM"
        const val PLAYLIST = "PLAYLIST"
        const val M2TS = ".m2ts"
        const val MPLS = ".mpls"
        const val CLIPINF = "CLIPINF"
        const val CLPI = ".clpi"
        const val TOP_N = 5
        const val MAX_TARGET_RUNS = 256
        const val MAX_RAW_LOG = 2
        const val MAX_SKIP_LOG = 8
        const val ROOT_LOG_LIMIT = 12
        const val HEAD_HEX_BYTES = 4096
        const val HEX_BYTES_PER_LINE = 64
        const val HEX_DIGITS = "0123456789abcdef"
        const val PREFETCH_BUDGET_MS = 3_000L
    }
}

internal class MplFetchResult(val bytes: ByteArray, val playlist: IsoPlaylist?)

internal object IsoMplsFetch {

    private const val THREADS = 1
    private const val TIMEOUT_MS = 12_000L

    private val ordinal = AtomicInteger(0)

    private val executor = Executors.newFixedThreadPool(THREADS) { runnable ->
        Thread(runnable, "iso-mpls-${ordinal.incrementAndGet()}").apply { isDaemon = true }
    }

    private val prefetchExecutor = Executors.newFixedThreadPool(1) { runnable ->
        Thread(runnable, "iso-mpls-prefetch-${ordinal.incrementAndGet()}").apply { isDaemon = true }
    }

    fun <T> runWithBudget(budgetMs: Long, block: () -> T): T? {
        val task = FutureTask(Callable { block() })
        prefetchExecutor.execute(task)
        return try {
            task.get(budgetMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            task.cancel(false)
            null
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            task.cancel(false)
            null
        }
    }

    fun load(entries: List<IsoEntry>, loader: (IsoEntry) -> MplFetchResult): Array<MplFetchResult?> {
        val results = arrayOfNulls<MplFetchResult>(entries.size)
        if (entries.size <= 1 || entries.none { it.data is EntryData.Extents }) {
            for (index in entries.indices) {
                results[index] = loadOne(entries[index], loader)
            }
            return results
        }
        val tasks = entries.map { entry -> Callable { loader(entry) } }
        val futures = try {
            executor.invokeAll(tasks, TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return serialLoad(entries, loader, results, 0)
        }
        var head = 0
        for (index in futures.indices) {
            val future = futures[index]
            if (future.isCancelled) break
            val value = try {
                future.get()
            } catch (e: Exception) {
                null
            }
            if (value == null) break
            results[index] = value
            head = index + 1
        }
        return if (head == entries.size) results else serialLoad(entries, loader, results, head)
    }

    private fun serialLoad(
        entries: List<IsoEntry>,
        loader: (IsoEntry) -> MplFetchResult,
        results: Array<MplFetchResult?>,
        from: Int,
    ): Array<MplFetchResult?> {
        for (index in from until entries.size) {
            results[index] = loadOne(entries[index], loader)
        }
        return results
    }

    private fun loadOne(entry: IsoEntry, loader: (IsoEntry) -> MplFetchResult): MplFetchResult? =
        try {
            loader(entry)
        } catch (e: Exception) {
            null
        }
}

private const val MAX_PLAYLISTS = 8
private const val FULL_SCAN_LIMIT = 16
private const val MIN_MAIN_DURATION_MS = 600_000L
private val MAIN_PLAYLISTS = setOf("00800.mpls", "00801.mpls")
