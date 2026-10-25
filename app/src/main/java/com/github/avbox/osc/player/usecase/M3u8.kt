package com.github.avbox.osc.player.usecase

import androidx.media3.common.util.UriUtil

import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.RegexUtils
import com.github.avbox.osc.util.VideoParseRuler
import java.math.BigDecimal
import java.util.regex.Pattern

object M3u8 {
    private const val TAG_DISCONTINUITY = "#EXT-X-DISCONTINUITY"
    private const val TAG_MEDIA_DURATION = "#EXTINF"
    private const val TAG_ENDLIST = "#EXT-X-ENDLIST"
    private const val TAG_KEY = "#EXT-X-KEY"
    private const val TAG_MAP = "#EXT-X-MAP"
    private const val TAG_CUE_OUT = "#EXT-X-CUE-OUT"
    private const val TAG_CUE_IN = "#EXT-X-CUE-IN"
    private const val TAG_DATERANGE = "#EXT-X-DATERANGE"

    private val REGEX_X_DISCONTINUITY = Pattern.compile("#EXT-X-DISCONTINUITY[\\s\\S]*?(?=#EXT-X-DISCONTINUITY|$)")
    private val REGEX_MEDIA_DURATION = Pattern.compile(TAG_MEDIA_DURATION + ":([\\d\\.]+)\\b")
    private val REGEX_URI = Pattern.compile("URI=\"(.+?)\"")

    private val REGEX_AD_SEGMENT_URI = Pattern.compile("(?i)(^|[/?&=_.-])(ads?|adv|advert(ise(ment)?)?|commercial|preroll|pre-roll|midroll|mid-roll|postroll|post-roll|sponsor|scte|vast|vmap|interstitial|bumper)([/?&=_.-]|$)")

    private val AD_DOMAIN_KEYWORDS = arrayOf(
        "adservice", "adserver", "adsystem", "doubleclick", "googlesyndication",
        "advertising", "2mdn.net", "moatads", "scorecardresearch", "quantserve"
    )

    private const val MAX_FRAME_RATE_AD_BLOCK_SIZE = 12
    private val FRAME_RATE_FEATURES: Map<Int, Set<BigDecimal>> = prepareFrameRateFeatures()

    @JvmField
    var currentAdCount: Int = 0

    @JvmStatic
    fun isAd(regex: String): Boolean {
        return regex.contains(TAG_DISCONTINUITY) || regex.contains(TAG_MEDIA_DURATION) || regex.contains(TAG_ENDLIST) || regex.contains(TAG_KEY) || regex.contains(TAG_CUE_OUT) || regex.contains(TAG_CUE_IN) || regex.contains(TAG_DATERANGE) || isDouble(regex)
    }

    @JvmStatic
    fun purify(tsUrlPre: String, m3u8content: String?): String? {
        val start = System.currentTimeMillis()
        currentAdCount = 0
        var m3u8content = m3u8content
        if (null == m3u8content || m3u8content.length == 0) return null
        if (m3u8content.startsWith("\ufeff")) m3u8content = m3u8content.substring(1)
        if (!m3u8content.startsWith("#EXTM3U")) return null

        var totalSegments = 0
        val lines = RegexUtils.getPattern(if (m3u8content.contains("\r\n")) "\r\n" else "\n").split(m3u8content)
        for (line in lines) {
            if (line.length > 0 && line[0] != '#') {
                totalSegments++
            }
        }

        var result = removeMinorityUrl(tsUrlPre, m3u8content)
        if (result != null && currentAdCount > 0) result = get(tsUrlPre, result)
        else result = get(tsUrlPre, m3u8content)
        result = keepVodEndList(m3u8content, result)

        if (totalSegments > 0 && currentAdCount > totalSegments * 0.5) {
            AppLog.e("echo-fixAdM3u8 ERROR: removed too many segments " + currentAdCount + "/" + totalSegments + ", using original content")
            currentAdCount = 0
            result = m3u8content
        }
        if (currentAdCount > 0 && !isPlayableMediaPlaylist(result)) {
            AppLog.e("echo-fixAdM3u8 ERROR: invalid playlist after ad removal, using original content")
            currentAdCount = 0
            result = m3u8content
        }

        val cost = System.currentTimeMillis() - start
        AppLog.i("echo-fixAdM3u8 cost: " + cost + "ms, removed: " + currentAdCount + " segments")
        return result
    }

    private fun maxPercent(preUrlMap: HashMap<String, Int>): Double {
        var maxTimes = 0
        var totalTimes = 0
        for (entry in preUrlMap.entries) {
            if (entry.value > maxTimes) {
                maxTimes = entry.value
            }
            totalTimes += entry.value
        }
        return maxTimes * 1.0 / (totalTimes * 1.0)
    }

    private var timesNoAd = 15

    private fun removeMinorityUrl(tsUrlPre: String, m3u8content: String): String? {
        var linesplit = "\n"
        if (m3u8content.contains("\r\n"))
            linesplit = "\r\n"
        val lines = RegexUtils.getPattern(linesplit).split(m3u8content)

        var totalSegments = 0
        for (line in lines) {
            if (line.length > 0 && line[0] != '#') {
                totalSegments++
            }
        }

        val preUrlMap = HashMap<String, Int>()
        for (line in lines) {
            if (line.length == 0 || line[0] == '#') {
                continue
            }
            val absoluteUrl = toAbsoluteUrl(tsUrlPre, line)
            val ilast = absoluteUrl.lastIndexOf('.')
            if (ilast <= 4) {
                continue
            }
            val preUrl = absoluteUrl.substring(0, ilast - 4)
            val cnt = preUrlMap[preUrl]
            if (cnt != null) {
                preUrlMap[preUrl] = cnt + 1
            } else {
                preUrlMap[preUrl] = 1
            }
        }
        if (preUrlMap.size <= 1) return null
        var domainFiltering = false
        if (maxPercent(preUrlMap) < 0.8) {
            preUrlMap.clear()
            for (line in lines) {
                if (line.length == 0 || line[0] == '#') {
                    continue
                }
                val absoluteUrl = toAbsoluteUrl(tsUrlPre, line)
                if (!absoluteUrl.startsWith("http://") && !absoluteUrl.startsWith("https://")) {
                    return null
                }
                val ifirst = absoluteUrl.indexOf('/', 9)
                if (ifirst <= 0) {
                    continue
                }
                val preUrl = absoluteUrl.substring(0, ifirst)
                val cnt = preUrlMap[preUrl]
                if (cnt != null) {
                    preUrlMap[preUrl] = cnt + 1
                } else {
                    preUrlMap[preUrl] = 1
                }
            }
            if (preUrlMap.size <= 1) return null
            if (maxPercent(preUrlMap) < 0.8) {
                return null
            }
            var allDomainsExceedThreshold = true
            for (count in preUrlMap.values) {
                if (count <= 15) {
                    allDomainsExceedThreshold = false
                    break
                }
            }
            if (allDomainsExceedThreshold) return null
            domainFiltering = true
        }

        var maxTimes = 0
        var maxTimesPreUrl = ""
        for (entry in preUrlMap.entries) {
            if (entry.value > maxTimes) {
                maxTimesPreUrl = entry.key
                maxTimes = entry.value
            }
        }
        if (maxTimes == 0) return null

        AppLog.i("echo-fixAdM3u8 URL pattern count: " + preUrlMap.size + ", maxTimes: " + maxTimes + ", total: " + totalSegments)

        val filtered = StringBuilder()
        val pendingSegmentTags = ArrayList<String>()
        for (i in lines.indices) {
            val item = lines[i].trim { it <= ' ' }
            if (item.length == 0) {
                if (pendingSegmentTags.isEmpty()) appendLine(filtered, lines[i], linesplit)
                else pendingSegmentTags.add(lines[i])
                continue
            }
            if (item[0] == '#') {
                val output = if (hasUriAttribute(item)) resolveUriLine(tsUrlPre, lines[i]) else lines[i]
                if (isSegmentTag(item)) pendingSegmentTags.add(output)
                else {
                    flush(filtered, pendingSegmentTags, linesplit)
                    appendLine(filtered, output, linesplit)
                }
                continue
            }

            val absoluteUrl = toAbsoluteUrl(tsUrlPre, lines[i])
            if (shouldKeepMediaUrl(absoluteUrl, domainFiltering, maxTimesPreUrl, preUrlMap)) {
                flush(filtered, pendingSegmentTags, linesplit)
                appendLine(filtered, absoluteUrl, linesplit)
            } else {
                pendingSegmentTags.clear()
                currentAdCount += 1
            }
        }

        if (totalSegments > 0 && currentAdCount > totalSegments * 0.3) {
            AppLog.i("echo-fixAdM3u8 suspicious ad count: " + currentAdCount + "/" + totalSegments + ", skipping URL filtering")
            currentAdCount = 0
            return null
        }

        return normalizeMediaPlaylist(filtered.toString())
    }

    private fun get(tsUrlPre: String, m3u8Content: String): String {
        var line = resolveContent(tsUrlPre, m3u8Content)
        val ads = getRegex(tsUrlPre)
        if (ads != null && !ads.isEmpty()) line = clean(line, ads)
        line = cleanCommonAdMarkers(line)
        if (hasEndList(line) && line.contains(TAG_DISCONTINUITY)) {
            line = cleanDecimalPrecisionGroups(line)
            line = cleanFrameRateGroups(line)
        }
        return cleanDiscontinuityGroups(line)
    }

    private fun cleanDecimalPrecisionGroups(m3u8Content: String): String {
        val groups = buildDiscontinuityGroups(RegexUtils.getPattern("\\n").split(m3u8Content))
        if (groups.size < 2) return m3u8Content

        val precisionCounts = HashMap<Int, Int>()
        var totalSegments = 0
        for (group in groups) {
            for (raw in group.lines) {
                val precision = decimalPrecision(raw)
                if (precision < 0) continue
                totalSegments += 1
                val count = precisionCounts[precision]
                precisionCounts[precision] = if (count == null) 1 else count + 1
            }
        }
        if (totalSegments < 8 || precisionCounts.size < 2) return m3u8Content

        var majorPrecision = -1
        var majorCount = 0
        for (entry in precisionCounts.entries) {
            if (entry.value > majorCount) {
                majorPrecision = entry.key
                majorCount = entry.value
            }
        }
        if (majorPrecision < 0 || majorCount * 1.0 / totalSegments < 0.7) return m3u8Content

        val removeGroups = BooleanArray(groups.size)
        var removableSegments = 0
        for (i in groups.indices) {
            val group = groups[i]
            if (i == groups.size - 1 || group.segmentCount == 0 || group.segmentCount > MAX_FRAME_RATE_AD_BLOCK_SIZE) continue

            val stats = decimalPrecisionStats(group, majorPrecision)
            if (stats.total > 0 && stats.mismatched == stats.total) {
                removeGroups[i] = true
                removableSegments += group.segmentCount
            }
        }

        if (removableSegments == 0 || removableSegments > adSegmentLimit(m3u8Content)
                || removableSegments > totalSegments * 0.3) return m3u8Content

        val sb = StringBuilder()
        var removedBlocks = 0
        for (i in groups.indices) {
            if (removeGroups[i]) {
                currentAdCount += groups[i].segmentCount
                removedBlocks += 1
            } else {
                groups[i].appendTo(sb)
            }
        }
        AppLog.i("echo-fixAdM3u8 decimal precision detected: major=" + majorPrecision + ", blocks=" + removedBlocks + ", removed=" + removableSegments)
        return normalizeMediaPlaylist(sb.toString())
    }

    private fun decimalPrecisionStats(group: Group, majorPrecision: Int): DecimalPrecisionStats {
        val stats = DecimalPrecisionStats()
        for (raw in group.lines) {
            val precision = decimalPrecision(raw)
            if (precision < 0) continue
            stats.total += 1
            if (precision != majorPrecision) stats.mismatched += 1
        }
        return stats
    }

    private fun decimalPrecision(line: String): Int {
        val start = extInfValueStart(line)
        if (start < 0) return -1
        val end = extInfValueEnd(line, start)
        val dot = line.indexOf('.', start)
        return if (dot < 0 || dot >= end) 0 else end - dot - 1
    }

    private fun cleanFrameRateGroups(m3u8Content: String): String {
        val groups = buildDiscontinuityGroups(RegexUtils.getPattern("\\n").split(m3u8Content))
        if (groups.size < 2) return m3u8Content

        val masterFrameRate = findDominantFrameRate(groups)
        if (masterFrameRate == 0) return m3u8Content

        var removableSegments = 0
        val removeGroups = BooleanArray(groups.size)
        for (i in groups.indices) {
            val group = groups[i]
            if (i == groups.size - 1 || group.segmentCount == 0 || group.segmentCount > MAX_FRAME_RATE_AD_BLOCK_SIZE) continue

            val stats = frameRateStats(group, masterFrameRate)
            if (stats.mismatched > 0 && stats.mismatched >= stats.matched) {
                removeGroups[i] = true
                removableSegments += group.segmentCount
            }
        }

        val segmentLimit = adSegmentLimit(m3u8Content)
        if (removableSegments == 0 || removableSegments > segmentLimit) return m3u8Content

        val sb = StringBuilder()
        var removedBlocks = 0
        for (i in groups.indices) {
            if (removeGroups[i]) {
                currentAdCount += groups[i].segmentCount
                removedBlocks += 1
            } else {
                groups[i].appendTo(sb)
            }
        }
        AppLog.i("echo-fixAdM3u8 frame rate detected: master=" + masterFrameRate + ", blocks=" + removedBlocks + ", removed=" + removableSegments)
        return normalizeMediaPlaylist(sb.toString())
    }

    private fun findDominantFrameRate(groups: List<Group>): Int {
        var count30 = 0
        var count25 = 0
        var count24 = 0
        for (group in groups) {
            for (raw in group.lines) {
                if (extInfValueStart(raw) < 0) continue
                val frameRate = exclusiveFrameRate(parseExtInfDuration(raw))
                if (frameRate == 30) count30 += 1
                else if (frameRate == 25) count25 += 1
                else if (frameRate == 24) count24 += 1
            }
        }

        val max = Math.max(count30, Math.max(count25, count24))
        if (max < 2) return 0
        if ((if (count30 == max) 1 else 0) + (if (count25 == max) 1 else 0) + (if (count24 == max) 1 else 0) != 1) return 0
        return if (count30 == max) 30 else (if (count25 == max) 25 else 24)
    }

    private fun frameRateStats(group: Group, masterFrameRate: Int): FrameRateStats {
        val stats = FrameRateStats()
        for (raw in group.lines) {
            if (extInfValueStart(raw) < 0) continue
            val frameRate = exclusiveFrameRate(parseExtInfDuration(raw))
            if (frameRate == masterFrameRate) stats.matched += 1
            else if (frameRate != 0) stats.mismatched += 1
        }
        return stats
    }

    private fun exclusiveFrameRate(duration: BigDecimal): Int {
        val is30 = isFrameAligned(duration, 30)
        val is25 = isFrameAligned(duration, 25)
        val is24 = isFrameAligned(duration, 24)
        if (is30 && !is25 && !is24) return 30
        if (is25 && !is30 && !is24) return 25
        if (is24 && !is30 && !is25) return 24
        return 0
    }

    private fun isFrameAligned(duration: BigDecimal?, frameRate: Int): Boolean {
        if (duration == null) return false
        val features = FRAME_RATE_FEATURES[frameRate]
        if (features == null) return false
        val fraction = duration.remainder(BigDecimal.ONE).abs().stripTrailingZeros()
        return features.contains(fraction)
    }

    private fun prepareFrameRateFeatures(): Map<Int, Set<BigDecimal>> {
        val features = HashMap<Int, Set<BigDecimal>>()
        features[30] = createFrameRateFeatures(30, true)
        features[25] = createFrameRateFeatures(25, false)
        features[24] = createFrameRateFeatures(24, true)
        return features
    }

    private fun createFrameRateFeatures(frameRate: Int, includeNtsc: Boolean): Set<BigDecimal> {
        val features = HashSet<BigDecimal>()
        addFrameRateFeatures(features, frameRate.toDouble(), frameRate)
        if (includeNtsc) addFrameRateFeatures(features, frameRate / 1.001, frameRate * 10)
        return features
    }

    private fun addFrameRateFeatures(features: MutableSet<BigDecimal>, frameRate: Double, maxFrames: Int) {
        val rate = BigDecimal.valueOf(frameRate)
        for (frame in 1..maxFrames) {
            val fraction = BigDecimal.valueOf(frame.toLong()).divide(rate, 10, BigDecimal.ROUND_HALF_UP).remainder(BigDecimal.ONE)
            for (scale in 3..6) {
                val value = fraction.setScale(scale, BigDecimal.ROUND_HALF_UP).stripTrailingZeros()
                if (value.compareTo(BigDecimal.ZERO) != 0) features.add(value)
            }
        }
    }

    private fun parseExtInfDuration(line: String): BigDecimal {
        val start = extInfValueStart(line)
        if (start < 0) return BigDecimal.ZERO
        val end = extInfValueEnd(line, start)
        try {
            return BigDecimal(line.substring(start, end)).stripTrailingZeros()
        } catch (ignored: Exception) {
            AppLog.d("M3u8", "extinf duration parse failed, treat as 0")
        }
        return BigDecimal.ZERO
    }

    private fun extInfValueStart(line: String?): Int {
        if (line == null) return -1
        val length = line.length
        var start = 0
        while (start < length && line[start] <= ' ') start += 1
        if (!line.startsWith(TAG_MEDIA_DURATION, start)) return -1
        start += TAG_MEDIA_DURATION.length
        if (start >= length || line[start] != ':') return -1
        start += 1
        while (start < length && line[start] <= ' ') start += 1
        return if (start < length) start else -1
    }

    private fun extInfValueEnd(line: String, start: Int): Int {
        var end = line.indexOf(',', start)
        if (end < 0) end = line.length
        while (end > start && line[end - 1] <= ' ') end -= 1
        return end
    }

    private fun adSegmentLimit(m3u8Content: String): Int {
        var totalDuration = BigDecimal.ZERO
        for (raw in RegexUtils.getPattern("\\n").split(m3u8Content)) totalDuration = totalDuration.add(parseExtInfDuration(raw))
        val totalMinutes = totalDuration.toDouble() / 60
        if (totalMinutes <= 30) return 18
        if (totalMinutes <= 60) return 24
        if (totalMinutes <= 90) return 30
        return 36
    }

    private class FrameRateStats {
        var matched = 0
        var mismatched = 0
    }

    private class DecimalPrecisionStats {
        var total = 0
        var mismatched = 0
    }

    private fun resolveContent(tsUrlPre: String, m3u8Content: String): String {
        var m3u8Content = m3u8Content.replace(Regex("\r\n"), "\n")
        val sb = StringBuilder()
        for (line in RegexUtils.getPattern("\n").split(m3u8Content)) {
            sb.append(if (shouldResolve(line)) resolve(tsUrlPre, line.trim { it <= ' ' }) else line).append("\n")
        }
        return sb.toString()
    }

    private fun getRegex(tsUrlPre: String): List<String> {
        val hostsRegex = VideoParseRuler.hostsRegex
        var list = ArrayList<String>()
        for (host in hostsRegex.keys) {
            if (!tsUrlPre.contains(host)) continue
            if (hostsRegex[host] == null) continue
            list = hostsRegex[host] ?: continue
            break
        }
        return list
    }

    private fun clean(line: String, ads: List<String>): String {
        var line = line
        var scan = false
        for (ad in ads) {
            if (ad.contains(TAG_DISCONTINUITY) || ad.contains(TAG_MEDIA_DURATION)) line = scanAd(line, ad)
            else if (isDouble(ad)) scan = true
        }
        return if (scan) scan(line, ads) else line
    }

    private fun cleanCommonAdMarkers(line: String): String {
        val sb = StringBuilder()
        val pending = ArrayList<String>()
        var inAdBreak = false
        var changed = false

        for (raw in line.split("\n")) {
            val item = raw.trim { it <= ' ' }
            if (item.length == 0) {
                if (pending.isEmpty()) sb.append(raw).append("\n")
                else pending.add(raw)
                continue
            }
            if (item.startsWith("#")) {
                if (item.startsWith(TAG_CUE_IN)) {
                    if (inAdBreak || hasAdSignal(pending)) {
                        inAdBreak = false
                        pending.clear()
                        changed = true
                        continue
                    }
                }
                if (isAdBreakStart(item)) {
                    flush(sb, pending)
                    inAdBreak = true
                    pending.add(raw)
                    changed = true
                    continue
                }
                if (inAdBreak) {
                    pending.add(raw)
                    changed = true
                    continue
                }
                if (isStandaloneAdTag(item)) {
                    flush(sb, pending)
                    currentAdCount += 1
                    changed = true
                    continue
                }
                if (isSegmentTag(item) || isAdSignalTag(item)) {
                    pending.add(raw)
                } else {
                    flush(sb, pending)
                    sb.append(raw).append("\n")
                }
                continue
            }

            if (inAdBreak || hasAdSignal(pending) || isAdSegmentUri(item) || hasAdDomain(item)) {
                pending.clear()
                currentAdCount += 1
                changed = true
                continue
            }
            flush(sb, pending)
            sb.append(raw).append("\n")
        }

        if (!inAdBreak) flush(sb, pending)
        return if (changed) sb.toString() else line
    }

    private fun flush(sb: StringBuilder, pending: MutableList<String>) {
        for (line in pending) sb.append(line).append("\n")
        pending.clear()
    }

    private fun flush(sb: StringBuilder, pending: MutableList<String>, linesplit: String) {
        for (line in pending) appendLine(sb, line, linesplit)
        pending.clear()
    }

    private fun appendLine(sb: StringBuilder, line: String, linesplit: String) {
        sb.append(line).append(linesplit)
    }

    private fun hasAdSignal(pending: List<String>): Boolean {
        for (line in pending) {
            if (isAdBreakStart(line.trim { it <= ' ' }) || isAdSignalTag(line.trim { it <= ' ' })) return true
        }
        return false
    }

    private fun isAdBreakStart(line: String): Boolean {
        return line.startsWith(TAG_CUE_OUT)
    }

    private fun isAdSignalTag(line: String): Boolean {
        if (line.startsWith("#EXT-OATCLS-SCTE35")) return true
        if (line.startsWith("#EXT-X-SCTE35")) return true
        if (line.startsWith("#EXT-X-SPLICEPOINT-SCTE35")) return true
        if (line.startsWith("#EXT-X-CUE")) return true
        if (line.startsWith("#EXT-X-ASSET")) return true
        if (line.startsWith("#EXT-X-VMAP-AD-BREAK")) return true
        if (line.startsWith("#EXT-X-AD")) return true
        if (line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")) return false
        return false
    }

    private fun isSegmentTag(line: String): Boolean {
        if (line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")) return false
        return line.startsWith(TAG_MEDIA_DURATION) || line.startsWith("#EXT-X-BYTERANGE") || line.startsWith("#EXT-X-PROGRAM-DATE-TIME") || line.startsWith("#EXT-X-DISCONTINUITY") || line.startsWith("#EXT-X-PART") || line.startsWith("#EXT-X-PRELOAD-HINT")
    }

    private fun isStandaloneAdTag(line: String): Boolean {
        if (!line.startsWith(TAG_DATERANGE)) return false
        return isAdLikeText(line) || line.contains("X-ASSET-URI") || line.contains("X-ASSET-LIST")
    }

    private fun isAdLikeText(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("scte") || lower.contains("cue") || lower.contains("interstitial") ||
               lower.contains("vmap") || lower.contains("vast") || lower.contains("advert") ||
               lower.contains("commercial") || lower.contains("ad-") || lower.contains("ad_") ||
               lower.contains("ad.") || lower.contains("preroll") || lower.contains("midroll") ||
               lower.contains("postroll") || lower.contains("bumper");
    }

    private fun isAdSegmentUri(line: String): Boolean {
        return REGEX_AD_SEGMENT_URI.matcher(line).find()
    }

    private fun hasAdDomain(url: String): Boolean {
        val lower = url.lowercase()
        for (keyword in AD_DOMAIN_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true
            }
        }
        return false
    }

    private fun cleanDiscontinuityGroups(m3u8Content: String): String {
        val lines = RegexUtils.getPattern("\n").split(m3u8Content)
        val groups = buildDiscontinuityGroups(lines)
        if (groups.size < 3) return m3u8Content
        val main = findMainGroup(groups)
        if (main == null || main.segmentCount < 3) return m3u8Content

        val sb = StringBuilder()
        var changed = false
        for (group in groups) {
            if (shouldDropGroup(group, main)) {
                currentAdCount += group.segmentCount
                changed = true
                continue
            }
            group.appendTo(sb)
        }
        return if (changed) sb.toString() else m3u8Content
    }

    private fun buildDiscontinuityGroups(lines: Array<String>): List<Group> {
        val groups = ArrayList<Group>()
        var group = Group()
        for (raw in lines) {
            val line = raw.trim { it <= ' ' }
            if (line.startsWith(TAG_DISCONTINUITY) && group.hasMedia()) {
                groups.add(group)
                group = Group()
            }
            group.add(raw)
        }
        if (group.hasMedia() || !group.lines.isEmpty()) groups.add(group)
        return groups
    }

    private fun findMainGroup(groups: List<Group>): Group? {
        var main: Group? = null
        for (group in groups) {
            if (group.segmentCount == 0) continue
            if (main == null || group.score() > main.score()) main = group
        }
        return main
    }

    private fun shouldDropGroup(group: Group, main: Group): Boolean {
        if (group === main || group.segmentCount == 0) return false

        val shortGroup = group.segmentCount <= 2 ||
                       (main.totalDuration > 0 && group.totalDuration > 0 &&
                        group.totalDuration < main.totalDuration * 0.18)

        val differentHost = main.host.length > 0 && group.host.length > 0 &&
                           main.host != group.host

        val differentPath = main.pathPrefix.length > 0 && group.pathPrefix.length > 0 &&
                           main.pathPrefix != group.pathPrefix

        val hasAdFeature = group.adLikeCount > 0 || hasAdDomain(group.host) ||
                          isAdSegmentUri(group.pathPrefix)

        val adLike = hasAdFeature || differentHost || (group.segmentCount <= 2 && differentPath)

        return shortGroup && adLike
    }

    private fun hostOf(url: String): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return ""
        val start = url.indexOf("://") + 3
        val end = url.indexOf('/', start)
        return if (end > start) url.substring(start, end) else url.substring(start)
    }

    private fun pathPrefixOf(url: String): String {
        var clean = url
        val query = clean.indexOf('?')
        if (query >= 0) clean = clean.substring(0, query)
        val slash = clean.lastIndexOf('/')
        return if (slash > 0) clean.substring(0, slash + 1) else ""
    }

    private fun toAbsoluteUrl(base: String, url: String?): String {
        if (url == null) return ""
        val line = url.trim { it <= ' ' }
        if (line.length == 0 || line.startsWith("http://") || line.startsWith("https://")) return line
        return UriUtil.resolve(base, line)
    }

    private fun shouldKeepMediaUrl(absoluteUrl: String, domainFiltering: Boolean, maxTimesPreUrl: String, preUrlMap: HashMap<String, Int>): Boolean {
        if (!domainFiltering) return absoluteUrl.startsWith(maxTimesPreUrl)
        val ifirst = absoluteUrl.indexOf('/', 9)
        val domain = if (ifirst > 0) absoluteUrl.substring(0, ifirst) else absoluteUrl
        val cnt = preUrlMap[domain]
        return domain == maxTimesPreUrl || (cnt != null && cnt > timesNoAd)
    }

    private fun hasUriAttribute(line: String): Boolean {
        return line.startsWith(TAG_KEY) || line.startsWith(TAG_MAP)
    }

    private fun resolveUriLine(base: String, line: String): String {
        val matcher = REGEX_URI.matcher(line)
        val value = if (matcher.find()) matcher.group(1) else null
        return if (value == null) line else line.replace(value, UriUtil.resolve(base, value))
    }

    private fun normalizeMediaPlaylist(content: String): String {
        val sb = StringBuilder()
        var seenMedia = false
        var hasPendingDiscontinuity = false
        var pendingDiscontinuity = ""
        for (raw in content.replace(Regex("\r\n"), "\n").split("\n")) {
            val item = raw.trim { it <= ' ' }
            if (isDiscontinuityTag(item)) {
                if (seenMedia && !hasPendingDiscontinuity) {
                    pendingDiscontinuity = raw
                    hasPendingDiscontinuity = true
                }
                continue
            }
            if (hasPendingDiscontinuity) {
                if (item.length == 0) continue
                if (!item.startsWith(TAG_ENDLIST)) sb.append(pendingDiscontinuity).append("\n")
                hasPendingDiscontinuity = false
            }
            if (item.length == 0 && sb.length == 0) continue
            sb.append(raw).append("\n")
            if (isMediaUriLine(item)) seenMedia = true
        }
        return sb.toString()
    }

    private fun isPlayableMediaPlaylist(content: String?): Boolean {
        if (content == null || !content.startsWith("#EXTM3U")) return false
        var mediaCount = 0
        var pendingExtInf = false
        for (raw in RegexUtils.getPattern("\n").split(content.replace(Regex("\r\n"), "\n"))) {
            val line = raw.trim { it <= ' ' }
            if (line.length == 0) continue
            if (line.startsWith(TAG_MEDIA_DURATION)) {
                if (pendingExtInf) return false
                pendingExtInf = true
            } else if (isMediaUriLine(line)) {
                mediaCount += 1
                pendingExtInf = false
            } else if (line.startsWith(TAG_ENDLIST) && pendingExtInf) {
                return false
            }
        }
        return mediaCount > 0 && !pendingExtInf
    }

    private fun keepVodEndList(original: String, result: String?): String? {
        if (result == null) return null
        if (!hasEndList(original) || hasEndList(result)) return result
        return result + (if (result.endsWith("\n")) "" else "\n") + TAG_ENDLIST + "\n"
    }

    private fun hasEndList(content: String?): Boolean {
        if (content == null) return false
        for (raw in RegexUtils.getPattern("\n").split(content.replace(Regex("\r\n"), "\n"))) {
            if (raw.trim { it <= ' ' }.startsWith(TAG_ENDLIST)) return true
        }
        return false
    }

    private fun isMediaUriLine(line: String): Boolean {
        return line.length > 0 && !line.startsWith("#")
    }

    private fun isDiscontinuityTag(line: String): Boolean {
        return line.startsWith(TAG_DISCONTINUITY) && !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")
    }

    private class Group {
        val lines = ArrayList<String>()
        var segmentCount = 0
        var adLikeCount = 0
        var totalDuration = 0.0
        var host = ""
        var pathPrefix = ""

        fun add(raw: String) {
            lines.add(raw)
            val line = raw.trim { it <= ' ' }
            val durationStart = extInfValueStart(line)
            if (durationStart >= 0) {
                val durationEnd = extInfValueEnd(line, durationStart)
                try {
                    totalDuration += java.lang.Double.parseDouble(line.substring(durationStart, durationEnd))
                } catch (ignored: Exception) {
                    AppLog.d("M3u8", "segment duration parse failed, skip")
                }
            }
            if (line.length == 0 || line.startsWith("#")) {
                if (isAdSignalTag(line) || isStandaloneAdTag(line)) adLikeCount += 1
                return
            }
            segmentCount += 1
            if (isAdSegmentUri(line) || hasAdDomain(line)) adLikeCount += 1
            if (host.length == 0) host = hostOf(line)
            if (pathPrefix.length == 0) pathPrefix = pathPrefixOf(line)
        }

        fun hasMedia(): Boolean {
            return segmentCount > 0
        }

        fun appendTo(sb: StringBuilder) {
            for (line in lines) sb.append(line).append("\n")
        }

        fun score(): Double {
            return if (totalDuration > 0) totalDuration else segmentCount.toDouble()
        }
    }

    private fun scanAd(line: String, TAG_AD: String): String {
        val m1 = RegexUtils.getPattern(TAG_AD).matcher(line)
        val needRemoveAd = ArrayList<String>()
        while (m1.find()) {
            val group = m1.group()
            val groupCleaned = group.replace(TAG_ENDLIST, "")
            val m2 = REGEX_MEDIA_DURATION.matcher(group)
            var tCount = 0
            while (m2.find()) {
                tCount += 1
            }
            needRemoveAd.add(groupCleaned)
            currentAdCount += tCount
        }
        var line = line
        for (rem in needRemoveAd) {
            line = line.replace(rem, "")
        }
        return line
    }

    private fun scan(line: String, ads: List<String>): String {
        val m1 = REGEX_X_DISCONTINUITY.matcher(line)
        val needRemoveAd = ArrayList<String>()
        while (m1.find()) {
            val group = m1.group()
            val groupCleaned = group.replace(TAG_ENDLIST, "")
            val m2 = REGEX_MEDIA_DURATION.matcher(group)
            var ft = BigDecimal.ZERO
            var lt = BigDecimal.ZERO
            var t = BigDecimal.ZERO
            var tCount = 0
            while (m2.find()) {
                if (ft == BigDecimal.ZERO) ft = BigDecimal(m2.group(1))
                lt = BigDecimal(m2.group(1))
                t = t.add(lt)
                tCount += 1
            }

            val ftStr = ft.toString()
            val ltStr = lt.toString()
            val tStr = t.toString()
            for (ad in ads) {
                if (ad.startsWith("-")) {
                    val adClean = ad.substring(1)
                    if (ltStr.startsWith(adClean)) {
                        needRemoveAd.add(groupCleaned)
                        currentAdCount += tCount
                        break
                    }
                } else {
                    if (ftStr.startsWith(ad) || tStr.startsWith(ad)) {
                        needRemoveAd.add(groupCleaned)
                        currentAdCount += tCount
                        break
                    }
                }
            }
        }
        var line = line
        for (rem in needRemoveAd) {
            line = line.replace(rem, "")
        }
        return line
    }

    private fun isDouble(ad: String): Boolean {
        try {
            return java.lang.Double.parseDouble(ad) != 0.0
        } catch (e: Exception) {
            return false
        }
    }

    private fun shouldResolve(line: String): Boolean {
        val item = line.trim { it <= ' ' }
        if (item.length == 0) return false
        return (!item.startsWith("#") && !item.startsWith("http")) || hasUriAttribute(item)
    }

    private fun resolve(base: String, line: String): String {
        if (hasUriAttribute(line)) {
            return resolveUriLine(base, line)
        } else {
            return UriUtil.resolve(base, line)
        }
    }
}
