package com.github.avbox.osc.player.engine.iso

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

internal data class IsoProbe(val totalLength: Long, val firstBytes: ByteArray)

internal data class IsoVolumeInfo(
    val totalLength: Long,
    val selection: IsoSelection,
    val locator: IsoLocator? = null,
    val streams: List<IsoStream> = emptyList(),
    val videoPid: Int? = null,
)

internal class IsoResolver(
    internal val logger: (String) -> Unit = {},
) {
    private val probes = ConcurrentHashMap<String, IsoProbe>()

    fun probe(uri: Uri, factory: DataSource.Factory): IsoProbe {
        val key = uri.toString()
        probes[key]?.let { return it }
        val value = readProbe(uri, factory)
        if (probes.size >= MAX_PROBES) probes.clear()
        val existing = probes.putIfAbsent(key, value)
        return existing ?: value
    }

    fun resolve(probe: IsoProbe, reader: RangeBlockReader, seekMs: Long = 0L): IsoVolumeInfo {
        val key = isoFingerprint(probe.totalLength, probe.firstBytes)
        logger("echo-iso-volume-key:${key.takeLast(12)}:${probe.firstBytes.size}")
        val info = try {
            IsoVolumeCache.load(key) { loadVolume(probe.totalLength, reader) }
        } catch (e: IsoFormatException) {
            logger("echo-iso-error:${e.message}")
            throw e
        }
        IsoPlaylistState.publishStreams(info.streams)
        IsoPlaylistState.publishVideoPid(info.videoPid)
        if (seekMs <= 0L) return info
        val aligned = info.locator?.locateSeek(info.selection, seekMs)
        if (aligned == null) {
            logger("echo-iso-seek-rap-miss:$seekMs:${info.selection.segments.size}")
            return info
        }
        logger(
            "echo-iso-seek-rap:${seekMs}:${aligned.baseMs}:" +
                "${aligned.primary.name}:${aligned.primary.startOffset}:" +
                "segments=${aligned.segments.size}",
        )
        return info.copy(selection = aligned)
    }

    private fun loadVolume(totalLength: Long, reader: RangeBlockReader): IsoVolumeInfo =
        parse(totalLength, reader)

    private fun parse(totalLength: Long, reader: RangeBlockReader): IsoVolumeInfo {
        val volume = UdfVolume.open(reader, totalLength) { logger("echo-iso-udf-$it") }
        val locator = IsoLocator(volume)
        val selection = locator.locateSelection()
        logger("echo-iso-read-count:${volume.readRequests()}")
        val target = selection.primary
        logger("echo-iso-udf-ok:${target.m2tsOffset}:${target.m2tsLength}")
        logger("echo-iso-target:${target.name}:${target.m2tsOffset}:${target.m2tsLength}")
        return IsoVolumeInfo(
            totalLength,
            selection,
            locator,
            locator.selectedStreams,
            locator.selectedVideoPid,
        )
    }

    private fun readProbe(uri: Uri, factory: DataSource.Factory): IsoProbe {
        val source = factory.createDataSource()
        try {
            var opened = source.open(probeSpec(uri, PROBE_SIZE.toLong()))
            var headers = source.responseHeaders
            var total = probeTotalLength(headers, C.LENGTH_UNSET.toLong())
            if (total == C.LENGTH_UNSET.toLong()) {
                source.close()
                opened = source.open(probeSpec(uri, C.LENGTH_UNSET.toLong()))
                headers = source.responseHeaders
                total = probeTotalLength(headers, opened)
            }
            logger("echo-iso-probe:open=$opened:total=$total:mode=${sourceMode(headers)}:${describeHeaders(headers)}")
            if (total == C.LENGTH_UNSET.toLong() || total <= 0) {
                throw IsoFormatException("unknown length")
            }
            if (isRangeUnsupported(headers)) {
                IsoSessionSignal.markStub(total)
                throw IsoFormatException("range unsupported")
            }
            val size = minOf(total, PROBE_SIZE.toLong()).toInt()
            val bytes = ByteArray(size)
            val direct = readUpTo(source, bytes)
            logger("echo-iso-probe-read:$direct/$size")
            if (direct < size) {
                val rest = DataSourceRangeReader(factory, uri, logger).read(direct.toLong(), size - direct)
                System.arraycopy(rest, 0, bytes, direct, rest.size)
            }
            logger("echo-iso-probe-head:${hexPrefix(bytes)}")
            return IsoProbe(total, bytes)
        } catch (e: IsoFormatException) {
            logger("echo-iso-error:${e.message}")
            throw e
        } catch (e: IOException) {
            logAuthFailure(e, logger)
            throw e
        } finally {
            source.close()
        }
    }

    private fun probeSpec(uri: Uri, length: Long): DataSpec =
        DataSpec.Builder()
            .setUri(uri)
            .setPosition(0)
            .setLength(length)
            .build()

    private fun readUpTo(source: DataSource, target: ByteArray): Int {
        var read = 0
        while (read < target.size) {
            val got = source.read(target, read, target.size - read)
            if (got <= 0) break
            read += got
        }
        return read
    }

    private fun describeHeaders(headers: Map<String, List<String>>): String {
        val builder = StringBuilder()
        for (key in PROBE_HEADER_KEYS) {
            if (builder.isNotEmpty()) builder.append('|')
            builder.append(key).append('=').append(headerValue(headers, key)?.take(80) ?: "-")
        }
        return builder.toString()
    }

    private fun hexPrefix(bytes: ByteArray): String {
        val size = minOf(bytes.size, PROBE_HEX_BYTES)
        val builder = StringBuilder(size * 2)
        for (index in 0 until size) {
            val value = bytes[index].toInt() and 0xFF
            if (value < 0x10) builder.append('0')
            builder.append(value.toString(16))
        }
        return builder.toString()
    }

    private companion object {
        const val PROBE_SIZE = 4096
        const val MAX_PROBES = 8
        const val PROBE_HEX_BYTES = 16
        val PROBE_HEADER_KEYS = listOf("Content-Type", CONTENT_LENGTH_HEADER, CONTENT_RANGE_HEADER, ACCEPT_RANGES_HEADER)
    }
}

internal class DataSourceRangeReader(
    private val factory: DataSource.Factory,
    private val uri: Uri,
    private val logger: (String) -> Unit,
) : RangeBlockReader {

    override fun read(start: Long, length: Int): ByteArray {
        return try {
            readOnce(start, length)
        } catch (e: IOException) {
            logAuthFailure(e, logger)
            if (e is HttpDataSource.InvalidResponseCodeException) throw e
            readOnce(start, length)
        }
    }

    private fun readOnce(start: Long, length: Int): ByteArray {
        val source = factory.createDataSource()
        try {
            source.open(
                DataSpec.Builder()
                    .setUri(uri)
                    .setPosition(start)
                    .setLength(length.toLong())
                    .build(),
            )
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = source.read(bytes, read, length - read)
                if (count == C.RESULT_END_OF_INPUT) break
                read += count
            }
            if (read != length) throw IsoFormatException("short read: $read/$length")
            return bytes
        } catch (e: IOException) {
            logAuthFailure(e, logger)
            throw e
        } finally {
            source.close()
        }
    }
}

internal object IsoVolumeCache {

    private const val MAX_ENTRIES = 4
    private const val TTL_MS = 600_000L

    private class Cached(val info: IsoVolumeInfo, val at: Long)

    private val lock = Any()
    private val entries = object : LinkedHashMap<String, Cached>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>): Boolean =
            size > MAX_ENTRIES
    }
    private val inFlight = ConcurrentHashMap<String, FutureTask<IsoVolumeInfo>>()

    fun get(key: String): IsoVolumeInfo? {
        val cached = synchronized(lock) { entries[key] } ?: return null
        if (System.currentTimeMillis() - cached.at > TTL_MS) {
            synchronized(lock) { entries.remove(key) }
            return null
        }
        return cached.info
    }

    fun put(key: String, info: IsoVolumeInfo) {
        synchronized(lock) { entries[key] = Cached(info, System.currentTimeMillis()) }
    }

    fun load(key: String, parse: () -> IsoVolumeInfo): IsoVolumeInfo {
        get(key)?.let { return it }
        val task = FutureTask<IsoVolumeInfo>(Callable { parse() })
        val running = inFlight.putIfAbsent(key, task) ?: task
        if (running === task) task.run()
        val info = try {
            running.get()
        } catch (e: ExecutionException) {
            throw (e.cause as? IsoFormatException)
                ?: IsoFormatException("volume parse failed: ${e.cause?.message}")
        } finally {
            inFlight.remove(key, running)
        }
        put(key, info)
        return info
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
        inFlight.clear()
    }
}

internal fun headerValue(headers: Map<String, List<String>>, key: String): String? =
    headers.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.firstOrNull()

internal fun probeTotalLength(headers: Map<String, List<String>>, openedLength: Long): Long {
    val contentRange = headerValue(headers, CONTENT_RANGE_HEADER)
    if (contentRange != null) return parseContentRangeTotal(contentRange) ?: C.LENGTH_UNSET.toLong()
    val contentLength = headerValue(headers, CONTENT_LENGTH_HEADER)?.trim()?.toLongOrNull()
    return contentLength?.takeIf { it > 0 } ?: openedLength
}

internal fun parseContentRangeTotal(value: String?): Long? {
    val trimmed = value?.trim() ?: return null
    if (!trimmed.startsWith("bytes", ignoreCase = true)) return null
    val slash = trimmed.lastIndexOf('/')
    if (slash < 0 || slash == trimmed.length - 1) return null
    val total = trimmed.substring(slash + 1).trim()
    if (total == "*") return null
    return total.toLongOrNull()?.takeIf { it > 0 }
}

internal const val CONTENT_RANGE_HEADER = "Content-Range"
internal const val CONTENT_LENGTH_HEADER = "Content-Length"
internal const val ACCEPT_RANGES_HEADER = "Accept-Ranges"

internal fun isRangeUnsupported(headers: Map<String, List<String>>): Boolean =
    headerValue(headers, ACCEPT_RANGES_HEADER)?.trim()?.equals("none", ignoreCase = true) == true

internal fun sourceMode(headers: Map<String, List<String>>): String =
    when {
        isRangeUnsupported(headers) -> "range-none"
        headerValue(headers, CONTENT_RANGE_HEADER) != null -> "206"
        else -> "200"
    }

internal fun isoFingerprint(totalLength: Long, firstBytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(firstBytes)
    val hex = StringBuilder(digest.size * 2)
    for (byte in digest) {
        val value = byte.toInt() and 0xFF
        if (value < 0x10) hex.append('0')
        hex.append(value.toString(16))
    }
    return "$totalLength:$hex"
}

internal fun logAuthFailure(e: IOException, logger: (String) -> Unit) {
    val code = (e as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: return
    if (code == 403 || code == 410) logger("echo-iso-auth-expired:$code")
}
