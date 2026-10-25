package com.github.avbox.osc.player.engine.iso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MplPlaylistTest {

    @Test
    fun parsesTwoPlayItems() {
        val data = mpls(
            item("00011", 0L, TICKS_PER_MINUTE * 31),
            item("00012", 0L, TICKS_PER_MINUTE * 30),
        )
        val playlist = requireNotNull(parseMplPlaylist("00000.mpls", data))
        assertEquals("00000.mpls", playlist.name)
        assertEquals(2, playlist.segments.size)
        assertEquals("00011", playlist.segments[0].clipId)
        assertEquals("00012", playlist.segments[1].clipId)
        assertEquals(61 * 60_000L, playlist.durationMs)
    }

    @Test
    fun convertsTicks() {
        assertEquals(1_000_000L, mplTicksToMicros(45_000L))
        assertEquals(60_000L, mplTicksToMillis(45_000L * 60))
    }

    @Test
    fun honorsInTime() {
        val data = mpls(item("00011", TICKS_PER_MINUTE, TICKS_PER_MINUTE * 31))
        val playlist = requireNotNull(parseMplPlaylist("00000.mpls", data))
        assertEquals(60_000_000L, playlist.segments[0].inTimeUs)
        assertEquals(30 * 60_000L, playlist.durationMs)
    }

    @Test
    fun handlesVariableLengthItems() {
        val data = mpls(
            item("00011", 0L, TICKS_PER_MINUTE, extraBody = 8),
            item("00012", 0L, TICKS_PER_MINUTE * 2),
        )
        val playlist = requireNotNull(parseMplPlaylist("00000.mpls", data))
        assertEquals(2, playlist.segments.size)
        assertEquals("00012", playlist.segments[1].clipId)
        assertEquals(3 * 60_000L, playlist.durationMs)
    }

    @Test
    fun acceptsRealDiscLayout() {
        val data = mpls(
            item("00016", 189_000_000L, 252_988_299L),
            item("00017", 189_000_000L, 252_990_176L),
            item("00018", 189_000_000L, 252_992_053L),
        )
        val playlist = requireNotNull(parseMplPlaylist("00005.mpls", data))
        assertEquals(3, playlist.segments.size)
        assertEquals(4_200_000_000L, playlist.segments[0].inTimeUs)
        assertEquals(4_266_011L, playlist.durationMs)
    }

    @Test
    fun rejectsBadSignature() {
        val data = mpls(item("00011", 0L, TICKS_PER_MINUTE))
        data[0] = 'X'.code.toByte()
        assertNull(parseMplPlaylist("00000.mpls", data))
    }

    @Test
    fun rejectsZeroClipCount() {
        assertNull(parseMplPlaylist("00000.mpls", mpls()))
    }

    @Test
    fun rejectsItemLengthOutOfRange() {
        val data = mpls(item("00011", 0L, TICKS_PER_MINUTE))
        putBe16(data, PLAY_LIST_OFFSET + PLAY_LIST_HEADER_SIZE, 0x7FFF)
        assertNull(parseMplPlaylist("00000.mpls", data))
    }

    @Test
    fun rejectsTruncatedData() {
        val data = mpls(item("00011", 0L, TICKS_PER_MINUTE))
        assertNull(parseMplPlaylist("00000.mpls", data.copyOf(PLAY_LIST_OFFSET + PLAY_LIST_HEADER_SIZE + 4)))
    }

    @Test
    fun rejectsPlayListStartOutOfRange() {
        val data = mpls(item("00011", 0L, TICKS_PER_MINUTE))
        putBe32(data, PLAY_LIST_START_OFFSET, 0x7FFF_FFFFL)
        assertNull(parseMplPlaylist("00000.mpls", data))
    }

    @Test
    fun rejectsNonNumericClipId() {
        assertNull(parseMplPlaylist("00000.mpls", mpls(item("00x11", 0L, TICKS_PER_MINUTE))))
    }

    @Test
    fun rejectsBadCodecId() {
        assertNull(parseMplPlaylist("00000.mpls", mpls(item("00011", 0L, TICKS_PER_MINUTE, codec = "AVC1"))))
    }

    @Test
    fun rejectsOutTimeBeforeInTime() {
        assertNull(parseMplPlaylist("00000.mpls", mpls(item("00011", TICKS_PER_MINUTE, 0L))))
    }

    @Test
    fun picksLongestPlaylist() {
        val short = requireNotNull(parseMplPlaylist("00001.mpls", mpls(item("00011", 0L, TICKS_PER_MINUTE))))
        val long = requireNotNull(
            parseMplPlaylist("00000.mpls", mpls(item("00011", 0L, TICKS_PER_MINUTE * 90))),
        )
        assertEquals(long.name, requireNotNull(pickLongestPlaylist(listOf(short, long))).name)
        assertNull(pickLongestPlaylist(emptyList()))
    }

    @Test
    fun picksPlaylistByUniqueDurationNotRawDuration() {
        val repeated = (0 until 5)
            .map { item("00002", TICKS_PER_MINUTE, TICKS_PER_MINUTE * 2) }
            .toTypedArray()
        val decoy = requireNotNull(parseMplPlaylist("00100.mpls", mpls(*repeated)))
        val feature = requireNotNull(
            parseMplPlaylist("00001.mpls", mpls(item("00000", 0L, TICKS_PER_MINUTE * 3))),
        )

        assertEquals(5 * 60_000L, decoy.durationMs)
        assertEquals(60_000L, uniqueDurationMs(decoy))
        assertEquals(3 * 60_000L, uniqueDurationMs(feature))
        assertEquals(feature.name, requireNotNull(pickLongestPlaylist(listOf(decoy, feature))).name)
    }

    @Test
    fun readsAudioAndSubtitleLanguages() {
        val data = mpls(
            itemWithStn(
                "00016",
                TICKS_PER_MINUTE,
                listOf(0x1200 to "jpn", 0x1201 to "eng", 0x1202 to "chi"),
            ),
        )
        val playlist = requireNotNull(parseMplPlaylist("00005.mpls", data))
        assertEquals(4, playlist.streams.size)
        assertEquals(0x1100, playlist.streams[0].pid)
        assertEquals("jpn", playlist.streams[0].language)
        assertEquals(0x1200, playlist.streams[1].pid)
        assertEquals("jpn", playlist.streams[1].language)
        assertEquals(0x1201, playlist.streams[2].pid)
        assertEquals("eng", playlist.streams[2].language)
        assertEquals("chi", playlist.streams[3].language)
    }

    @Test
    fun readsStreamLanguagesAcrossPlayItems() {
        val data = mpls(
            itemWithStn("00016", TICKS_PER_MINUTE, listOf(0x1200 to "jpn")),
            itemWithStn("00017", TICKS_PER_MINUTE, listOf(0x1201 to "eng")),
        )
        val playlist = requireNotNull(parseMplPlaylist("00005.mpls", data))
        assertEquals(3, playlist.streams.size)
        assertEquals(0x1200, playlist.streams[1].pid)
        assertEquals(0x1201, playlist.streams[2].pid)
        assertEquals("eng", playlist.streams[2].language)
    }

    @Test
    fun dedupesStreamsRepeatedAcrossPlayItems() {
        val data = mpls(
            itemWithStn("00016", TICKS_PER_MINUTE, listOf(0x1200 to "jpn")),
            itemWithStn("00017", TICKS_PER_MINUTE, listOf(0x1200 to "jpn")),
        )
        val playlist = requireNotNull(parseMplPlaylist("00005.mpls", data))

        assertEquals(2, playlist.streams.size)
        assertEquals(0x1100, playlist.streams[0].pid)
        assertEquals(0x1200, playlist.streams[1].pid)
        assertEquals("jpn", playlist.streams[1].language)
    }

    @Test
    fun ignoresMissingStnTable() {
        val playlist = requireNotNull(parseMplPlaylist("00000.mpls", mpls(item("00011", 0L, TICKS_PER_MINUTE))))
        assertEquals(0, playlist.streams.size)
    }

    @Test
    fun dropsUndeterminedAndZeroLanguage() {
        val data = mpls(
            itemWithStn("00016", TICKS_PER_MINUTE, listOf(0x1200 to "und", 0x1201 to "eng")),
        )
        val playlist = requireNotNull(parseMplPlaylist("00005.mpls", data))
        assertEquals(2, playlist.streams.size)
        assertEquals(0x1100, playlist.streams[0].pid)
        assertEquals(0x1201, playlist.streams[1].pid)
    }

    @Test
    fun readsPrimaryVideoPidFromStn() {
        val data = mpls(
            itemWithStn("00016", TICKS_PER_MINUTE, listOf(0x1200 to "zho")),
        )
        val playlist = requireNotNull(parseMplPlaylist("00005.mpls", data))

        assertEquals(VIDEO_PID, requireNotNull(playlist.videoPid))
    }

    @Test
    fun keepsVideoPidNullWithoutStn() {
        val data = mpls(item("00011", 0L, TICKS_PER_MINUTE))
        val playlist = requireNotNull(parseMplPlaylist("00000.mpls", data))

        assertNull(playlist.videoPid)
    }

    @Test
    fun ignoresSubPathPlayItems() {
        val data = mpls(item("00011", 0L, TICKS_PER_MINUTE), subPaths = subPaths("00012"))

        val playlist = requireNotNull(parseMplPlaylist("00000.mpls", data))

        assertEquals(1, playlist.segments.size)
        assertEquals(0, playlist.streams.size)
    }

    @Test
    fun concatenatesSeamlessPlayItems() {
        val data = mpls(
            item("00011", 0L, TICKS_PER_MINUTE, connectionCondition = 5),
            item("00012", 0L, TICKS_PER_MINUTE, connectionCondition = 5),
        )

        val playlist = requireNotNull(parseMplPlaylist("00000.mpls", data))

        assertEquals(2, playlist.segments.size)
        assertEquals(2 * 60_000L, playlist.durationMs)
    }

    @Test
    fun picksLongestPlaylistEvenOnMultiEpisodeDisc() {
        val episode1 = requireNotNull(parseMplPlaylist("00001.mpls", mpls(item("00011", 0L, TICKS_PER_MINUTE * 40))))
        val episode2 = requireNotNull(parseMplPlaylist("00002.mpls", mpls(item("00012", 0L, TICKS_PER_MINUTE * 45))))
        val episode3 = requireNotNull(parseMplPlaylist("00003.mpls", mpls(item("00013", 0L, TICKS_PER_MINUTE * 42))))

        val chosen = requireNotNull(pickLongestPlaylist(listOf(episode1, episode2, episode3)))

        assertEquals("00002.mpls", chosen.name)
    }

    private fun mpls(vararg items: ByteArray, subPaths: ByteArray = ByteArray(0)): ByteArray {
        var body = ByteArray(0)
        items.forEach { body += it }
        val playListLength = 2 + 2 + 2 + body.size + subPaths.size
        val total = PLAY_LIST_OFFSET + 4 + playListLength
        val data = ByteArray(total)
        "MPLS".forEachIndexed { index, c -> data[index] = c.code.toByte() }
        "0200".forEachIndexed { index, c -> data[VERSION_OFFSET + index] = c.code.toByte() }
        putBe32(data, PLAY_LIST_START_OFFSET, PLAY_LIST_OFFSET.toLong())
        putBe32(data, MARK_START_OFFSET, 4L + playListLength.toLong() + PLAY_LIST_OFFSET)
        putBe32(data, APP_INFO_LENGTH_OFFSET, APP_INFO_LENGTH.toLong())
        putBe32(data, PLAY_LIST_OFFSET, playListLength.toLong())
        putBe16(data, PLAY_LIST_OFFSET + 4, 0)
        putBe16(data, PLAY_LIST_OFFSET + 6, items.size)
        putBe16(data, PLAY_LIST_OFFSET + 8, if (subPaths.isEmpty()) 0 else 1)
        body.copyInto(data, PLAY_LIST_OFFSET + PLAY_LIST_HEADER_SIZE)
        subPaths.copyInto(data, PLAY_LIST_OFFSET + PLAY_LIST_HEADER_SIZE + body.size)
        return data
    }

    private fun subPaths(clipId: String): ByteArray {
        val playItem = ByteArray(SUB_PLAY_ITEM_SIZE)
        putBe16(playItem, 0, SUB_PLAY_ITEM_SIZE - 2)
        clipId.forEachIndexed { index, c -> playItem[2 + index] = c.code.toByte() }
        "M2TS".forEachIndexed { index, c -> playItem[7 + index] = c.code.toByte() }
        putBe16(playItem, 11, 0)
        playItem[13] = 0
        putBe32(playItem, 14, 0L)
        putBe32(playItem, 18, TICKS_PER_MINUTE)
        playItem[22] = 0
        putBe32(playItem, 23, 0L)

        val path = ByteArray(SUB_PATH_HEADER + playItem.size)
        putBe32(path, 0, (path.size - 4).toLong())
        path[4] = 0
        path[5] = 0
        path[6] = 1
        playItem.copyInto(path, SUB_PATH_HEADER)

        val wrapper = ByteArray(SUB_PATHS_HEADER + path.size)
        putBe32(wrapper, 0, (wrapper.size - 4).toLong())
        wrapper[4] = 0
        path.copyInto(wrapper, SUB_PATHS_HEADER)
        return wrapper
    }

    private fun item(
        clipId: String,
        inTime: Long,
        outTime: Long,
        extraBody: Int = 0,
        codec: String = "M2TS",
        connectionCondition: Int = 0,
    ): ByteArray {
        val bodySize = ITEM_BODY_SIZE + extraBody
        val bytes = ByteArray(2 + bodySize)
        putBe16(bytes, 0, bodySize)
        clipId.forEachIndexed { index, c -> bytes[2 + ITEM_CLIP_ID_OFFSET + index] = c.code.toByte() }
        codec.forEachIndexed { index, c -> bytes[2 + ITEM_CODEC_OFFSET + index] = c.code.toByte() }
        putBe16(bytes, 2 + ITEM_FLAGS_OFFSET, connectionCondition shl CONNECTION_CONDITION_SHIFT)
        putBe32(bytes, 2 + ITEM_IN_TIME_OFFSET, inTime)
        putBe32(bytes, 2 + ITEM_OUT_TIME_OFFSET, outTime)
        return bytes
    }

    private fun itemWithStn(
        clipId: String,
        outTime: Long,
        pgs: List<Pair<Int, String>>,
    ): ByteArray {
        val stn = stn(pgs)
        val bodySize = ITEM_STN_OFFSET + stn.size
        val bytes = ByteArray(2 + bodySize)
        putBe16(bytes, 0, bodySize)
        clipId.forEachIndexed { index, c -> bytes[2 + ITEM_CLIP_ID_OFFSET + index] = c.code.toByte() }
        "M2TS".forEachIndexed { index, c -> bytes[2 + ITEM_CODEC_OFFSET + index] = c.code.toByte() }
        putBe32(bytes, 2 + ITEM_OUT_TIME_OFFSET, outTime)
        stn.copyInto(bytes, 2 + ITEM_STN_OFFSET)
        return bytes
    }

    private fun stn(pgs: List<Pair<Int, String>>): ByteArray {
        var body = byteArrayOf(0, 0)
        body += byteArrayOf(1, 1, pgs.size.toByte(), 0, 0, 0, 0)
        body += ByteArray(5)
        body += streamEntry(VIDEO_PID)
        body += byteArrayOf(6, 0x24, 0x81.toByte(), 0x3f, 0, 0, 0)
        body += streamEntry(STREAM_PID)
        body += byteArrayOf(6, 0x80.toByte(), 0x30, 'j'.code.toByte(), 'p'.code.toByte(), 'n'.code.toByte(), 0)
        pgs.forEach { (pid, language) ->
            body += streamEntry(pid)
            body += byteArrayOf(
                4,
                0x90.toByte(),
                language[0].code.toByte(),
                language[1].code.toByte(),
                language[2].code.toByte(),
            )
        }
        val bytes = ByteArray(2 + body.size)
        putBe16(bytes, 0, body.size)
        body.copyInto(bytes, 2)
        return bytes
    }

    private fun streamEntry(pid: Int): ByteArray =
        byteArrayOf(9, 1, ((pid shr 8) and 0xFF).toByte(), (pid and 0xFF).toByte(), 0, 0, 0, 0, 0, 0)

    private fun putBe16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value shr 8) and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }

    private fun putBe32(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) {
            target[offset + index] = ((value shr ((3 - index) * 8)) and 0xFF).toByte()
        }
    }

    private companion object {
        const val PLAY_LIST_OFFSET = 58
        const val PLAY_LIST_HEADER_SIZE = 10
        const val VERSION_OFFSET = 4
        const val PLAY_LIST_START_OFFSET = 8
        const val MARK_START_OFFSET = 12
        const val APP_INFO_LENGTH_OFFSET = 40
        const val APP_INFO_LENGTH = 14
        const val ITEM_BODY_SIZE = 20
        const val ITEM_CLIP_ID_OFFSET = 0
        const val ITEM_CODEC_OFFSET = 5
        const val ITEM_FLAGS_OFFSET = 9
        const val CONNECTION_CONDITION_SHIFT = 12
        const val ITEM_IN_TIME_OFFSET = 12
        const val ITEM_OUT_TIME_OFFSET = 16
        const val SUB_PATHS_HEADER = 5
        const val SUB_PATH_HEADER = 7
        const val SUB_PLAY_ITEM_SIZE = 27
        const val TICKS_PER_SECOND = 45_000L
        const val TICKS_PER_MINUTE = TICKS_PER_SECOND * 60
        const val ITEM_STN_OFFSET = 32
        const val STREAM_PID = 0x1100
        const val VIDEO_PID = 0x1011
    }
}
