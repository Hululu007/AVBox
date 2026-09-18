package com.github.tvbox.osc.ui.music

import android.net.Uri
import android.text.TextUtils
import com.github.tvbox.osc.util.LOG
import com.lzy.okgo.OkGo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.universalchardet.UniversalDetector
import java.io.File
import java.nio.charset.Charset
import kotlin.math.roundToLong

data class LyricLine(val timeMs: Long, val text: String)

/** 歌词文本格式:判定与分派共用一处,避免日志里报的格式和实际走的分支不一致 */
private enum class LrcFormat { LRC, SRT, ASS }

object MusicLrc {

    private val timeTag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val wordTag = Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")
    private val srtTime = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->")
    private val breakTag = Regex("(?i)<br\\s*/?>")
    private val htmlTag = Regex("<[^>]{1,20}>")
    private val braceTag = Regex("\\{[^}]{0,40}\\}")
    private val assTag = Regex("\\{[^}]*\\}")
    private val assBreak = Regex("\\\\[Nn]")
    private val assDialogue = Regex("(?im)^[ \\t]*dialogue[ \\t]*:")

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.54 Safari/537.36"

    suspend fun load(source: String?): List<LyricLine> {
        val path = source?.trim().orEmpty()
        if (path.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val raw = read(path)
            if (raw.isNullOrBlank()) {
                LOG.i("echo-music lyric raw empty: kind=" + kindOf(path) + " srcLen=" + path.length)
                emptyList()
            } else {
                val lines = parse(raw)
                // 诊断:源给的歌词格式五花八门(标准 LRC / 增强 LRC / SRT / ASS),
                // 解析出 0 行时只有靠它才能看出是"格式不认"还是"内容为空"。只打结构,不打歌词内容
                LOG.i(
                    "echo-music lyric raw len=" + raw.length +
                        " format=" + formatOf(raw) + " lines=" + lines.size
                )
                lines
            }
        }
    }

    private fun kindOf(path: String): String = when {
        path.startsWith("data:") -> "data"
        path.startsWith("http://") || path.startsWith("https://") -> "http"
        else -> "file"
    }

    private fun formatOf(raw: String): LrcFormat = when {
        raw.contains("-->") -> LrcFormat.SRT
        isAss(raw) -> LrcFormat.ASS
        else -> LrcFormat.LRC
    }

    /**
     * ASS 判定必须"行首",不能用 `raw.contains("Dialogue:")`:歌词正文里出现这个词的概率不为零,
     * 一旦误判就会整首 0 行(正是这次要修的症状)。
     */
    private fun isAss(raw: String): Boolean =
        raw.contains("[Script Info]", ignoreCase = true) || assDialogue.containsMatchIn(raw)

    fun parse(raw: String): List<LyricLine> = when (formatOf(raw)) {
        LrcFormat.SRT -> parseSrt(raw)
        LrcFormat.ASS -> parseAss(raw)
        LrcFormat.LRC -> parseLrc(raw)
    }

    /**
     * ASS/SSA 字幕。源站常把歌词做成 ASS 内联在取流结果里(`[Script Info]` + `[Events]` 段的
     * `Dialogue: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text`),它既没有 `-->`
     * 也没有 `[mm:ss]`,不单独认格式就会一行都解析不出来。
     */
    private fun parseAss(raw: String): List<LyricLine> {
        val lines = ArrayList<LyricLine>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("Dialogue:", ignoreCase = true)) continue
            // Text 字段本身可能含逗号,必须限制切分次数
            val fields = trimmed.substringAfter(':').split(",", limit = 10)
            if (fields.size < 10) continue
            val start = assTimeMs(fields[1]) ?: continue
            val text = fields[9]
                .replace(assTag, "")
                .replace(assBreak, "\n")
                .replace("\\h", " ")
                .trim()
            if (text.isEmpty()) continue
            lines.add(LyricLine(start, text))
        }
        lines.sortBy { it.timeMs }
        return lines
    }

    /** `H:MM:SS.cc`(ASS 用百分秒)。用 roundToLong 而非 toLong:`12.34 * 1000` 在 double 下可能是
     *  12339.999…,截断会少 1ms */
    private fun assTimeMs(value: String): Long? {
        val parts = value.trim().split(':')
        if (parts.size < 3) return null
        val hour = parts[0].toLongOrNull() ?: return null
        val minute = parts[1].toLongOrNull() ?: return null
        val second = parts[2].toDoubleOrNull() ?: return null
        return hour * 3_600_000 + minute * 60_000 + (second * 1000).roundToLong()
    }

    private fun parseLrc(raw: String): List<LyricLine> {
        val lines = ArrayList<LyricLine>()
        for (line in raw.lineSequence()) {
            val tags = timeTag.findAll(line).toList()
            if (tags.isEmpty()) continue
            val text = wordTag.replace(line.substring(tags.last().range.last + 1), "").trim()
            if (text.isEmpty()) continue
            for (tag in tags) {
                val minute = tag.groupValues[1].toLongOrNull() ?: continue
                val second = tag.groupValues[2].toLongOrNull() ?: continue
                lines.add(LyricLine(minute * 60_000 + second * 1000 + fractionMs(tag.groupValues[3]), text))
            }
        }
        lines.sortBy { it.timeMs }
        return lines
    }

    private fun parseSrt(raw: String): List<LyricLine> {
        val lines = ArrayList<LyricLine>()
        for (block in raw.split(Regex("\\r?\\n\\s*\\r?\\n"))) {
            val rows = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val timeRow = rows.indexOfFirst { srtTime.containsMatchIn(it) }
            if (timeRow < 0) continue
            val match = srtTime.find(rows[timeRow]) ?: continue
            val text = rows.drop(timeRow + 1).joinToString("\n")
                .let { breakTag.replace(it, "\n") }
                .let { htmlTag.replace(braceTag.replace(it, ""), "") }
                .trim()
            if (text.isEmpty()) continue
            val hour = match.groupValues[1].toLong()
            val minute = match.groupValues[2].toLong()
            val second = match.groupValues[3].toLong()
            val ms = match.groupValues[4].padEnd(3, '0').take(3).toLong()
            lines.add(LyricLine(hour * 3_600_000 + minute * 60_000 + second * 1000 + ms, text))
        }
        lines.sortBy { it.timeMs }
        return lines
    }

    private fun fractionMs(value: String): Long = when (value.length) {
        0 -> 0L
        1 -> value.toLongOrNull().orZero() * 100
        2 -> value.toLongOrNull().orZero() * 10
        else -> value.take(3).toLongOrNull().orZero()
    }

    private fun Long?.orZero(): Long = this ?: 0L

    private fun read(source: String): String? = runCatching {
        when {
            source.startsWith("data:") -> decodeDataUri(source)
            source.startsWith("http://") || source.startsWith("https://") -> fetch(source.substringBefore('#'))
            else -> {
                val file = File(source)
                if (file.exists()) decode(file.readBytes()) else null
            }
        }
    }.getOrElse {
        LOG.i("echo-music lyric load failed: " + it.message)
        null
    }

    private fun decodeDataUri(source: String): String? {
        val comma = source.indexOf(',')
        if (comma < 0) return null
        val fragment = source.indexOf('#', comma + 1)
        val meta = source.substring(5, comma).lowercase()
        val payload = source.substring(comma + 1, if (fragment < 0) source.length else fragment)
        val bytes = if (meta.contains(";base64")) {
            android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
        } else {
            Uri.decode(payload).toByteArray(Charsets.UTF_8)
        }
        return decode(bytes)
    }

    private fun fetch(url: String): String? {
        val response = OkGo.get<String>(url)
            .headers("User-Agent", UA)
            .execute()
        val bytes = response.body.bytes()
        return decode(bytes)
    }

    private fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val detector = UniversalDetector(null)
        detector.handleData(bytes, 0, bytes.size)
        detector.dataEnd()
        val name = detector.detectedCharset
        detector.reset()
        if (TextUtils.isEmpty(name)) return String(bytes, Charsets.UTF_8)
        return runCatching { String(bytes, Charset.forName(name)) }.getOrElse { String(bytes, Charsets.UTF_8) }
    }
}
