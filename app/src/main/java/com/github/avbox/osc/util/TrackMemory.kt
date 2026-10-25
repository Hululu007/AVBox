package com.github.avbox.osc.util

import androidx.media3.common.C

import java.util.Locale

object TrackMemory {

    private const val TYPE_AUDIO = C.TRACK_TYPE_AUDIO
    private const val TYPE_VIDEO = C.TRACK_TYPE_VIDEO
    private const val TYPE_TEXT = C.TRACK_TYPE_TEXT

    private const val KEY_PREFIX = "track_mem_"
    private const val CONTENT_SEP = "@"
    private const val SLOT_AUDIO = "audio"
    private const val SLOT_VIDEO = "video"
    private const val SLOT_TEXT = "text"

    private const val MARK = "#"
    const val SUBTITLE_OFF = "#off"
    private const val SUBTITLE_LOCAL = "#local:"
    private const val SUBTITLE_ONLINE = "#online:"
    private const val ONLINE_SEP = "|"

    @JvmStatic
    fun contentKey(sourceKey: String?, vodId: String?): String {
        val source = trim(sourceKey)
        val id = trim(vodId)
        if (source.isEmpty() || id.isEmpty()) return ""
        return source + CONTENT_SEP + id
    }

    @JvmStatic
    fun audioFingerprint(language: String?, codec: String?, channels: Int): String {
        return "A" + "/" + field(language) + "/" + fieldCodec(codec) + "/" + (if (channels > 0) channels.toString() else "")
    }

    @JvmStatic
    fun videoFingerprint(codec: String?, width: Int, height: Int): String {
        val size = if (width > 0 && height > 0) width.toString() + "x" + height else ""
        return "V" + "/" + fieldCodec(codec) + "/" + size
    }

    @JvmStatic
    fun textFingerprint(language: String?, id: String?, label: String?, codec: String?): String {
        return "T" + "/" + field(language) + "/" + field(id) + "/" + field(label) + "/" + fieldCodec(codec)
    }

    @JvmStatic
    fun usable(fingerprint: String?): Boolean {
        if (fingerprint == null) return false
        val parts = fingerprint.split("/")
        if (parts.size < 2) return false
        for (i in 1 until parts.size) {
            if (parts[i].isNotEmpty()) return true
        }
        return false
    }

    @JvmStatic
    fun pick(available: List<String>?, remembered: String?): Int {
        if (available == null || available.isEmpty() || !usable(remembered)) return -1
        for (i in available.indices) {
            if (remembered == available[i]) return i
        }
        val language = fieldAt(remembered, 1)
        if (language.isEmpty()) return -1
        var matched = -1
        for (i in available.indices) {
            if (language != fieldAt(available[i], 1)) continue
            if (matched >= 0) return -1
            matched = i
        }
        return matched
    }

    @JvmStatic
    fun subtitleLocal(path: String?): String {
        val value = trim(path)
        return if (value.isEmpty()) "" else SUBTITLE_LOCAL + value
    }

    @JvmStatic
    fun subtitleOnline(releaseUrl: String?, fileName: String?): String {
        val release = trim(releaseUrl)
        if (release.isEmpty()) return ""
        return SUBTITLE_ONLINE + release + ONLINE_SEP + trim(fileName)
    }

    @JvmStatic
    fun isSubtitleOff(record: String?): Boolean {
        return SUBTITLE_OFF == record
    }

    @JvmStatic
    fun isSubtitleLocal(record: String?): Boolean {
        return record != null && record.startsWith(SUBTITLE_LOCAL)
    }

    @JvmStatic
    fun isSubtitleOnline(record: String?): Boolean {
        return record != null && record.startsWith(SUBTITLE_ONLINE)
    }

    @JvmStatic
    fun isSubtitleTrack(record: String?): Boolean {
        return record != null && record.isNotEmpty() && !record.startsWith(MARK)
    }

    @JvmStatic
    fun localPath(record: String?): String {
        if (!isSubtitleLocal(record)) return ""
        val value = record ?: return ""
        return value.substring(SUBTITLE_LOCAL.length)
    }

    @JvmStatic
    fun onlineRelease(record: String?): String {
        if (!isSubtitleOnline(record)) return ""
        val value = record ?: return ""
        val rest = value.substring(SUBTITLE_ONLINE.length)
        val sep = rest.indexOf(ONLINE_SEP)
        return if (sep < 0) rest else rest.substring(0, sep)
    }

    @JvmStatic
    fun onlineFileName(record: String?): String {
        if (!isSubtitleOnline(record)) return ""
        val value = record ?: return ""
        val rest = value.substring(SUBTITLE_ONLINE.length)
        val sep = rest.indexOf(ONLINE_SEP)
        return if (sep < 0) "" else rest.substring(sep + ONLINE_SEP.length)
    }

    @JvmStatic
    fun saveTrack(contentKey: String?, type: Int, fingerprint: String?) {
        if (type != TYPE_AUDIO && type != TYPE_VIDEO) return
        if (isEmpty(contentKey) || !usable(fingerprint)) return
        val key = contentKey ?: return
        KV.put(slotKey(key, type), fingerprint)
        AppLog.i("echo-track-memory save " + slot(type) + "=" + fingerprint)
    }

    @JvmStatic
    fun saveSubtitle(contentKey: String?, record: String?) {
        if (isEmpty(contentKey) || isEmpty(record)) return
        if (isSubtitleTrack(record) && !usable(record)) {
            AppLog.i("echo-track-memory skip unusable text=" + record)
            return
        }
        val key = contentKey ?: return
        KV.put(slotKey(key, TYPE_TEXT), record)
        AppLog.i("echo-track-memory save text=" + record)
    }

    @JvmStatic
    fun loadTrack(contentKey: String?, type: Int): String? {
        if (type != TYPE_AUDIO && type != TYPE_VIDEO) return null
        if (isEmpty(contentKey)) return null
        val key = contentKey ?: return null
        val value = KV.get(slotKey(key, type), "")
        return if (usable(value)) value else null
    }

    @JvmStatic
    fun loadSubtitle(contentKey: String?): String? {
        if (isEmpty(contentKey)) return null
        val key = contentKey ?: return null
        val value = KV.get(slotKey(key, TYPE_TEXT), "")
        return if (isEmpty(value)) null else value
    }

    @JvmStatic
    fun delete(contentKey: String?) {
        if (isEmpty(contentKey)) return
        val key = contentKey ?: return
        KV.delete(slotKey(key, TYPE_AUDIO))
        KV.delete(slotKey(key, TYPE_VIDEO))
        KV.delete(slotKey(key, TYPE_TEXT))
    }

    @JvmStatic
    fun deleteAll() {
        for (key in KV.keys(KEY_PREFIX)) {
            KV.delete(key)
        }
    }

    private fun slotKey(contentKey: String, type: Int): String {
        return KEY_PREFIX + contentKey + "_" + slot(type)
    }

    private fun slot(type: Int): String {
        if (type == TYPE_AUDIO) return SLOT_AUDIO
        if (type == TYPE_VIDEO) return SLOT_VIDEO
        return SLOT_TEXT
    }

    private fun isEmpty(text: String?): Boolean {
        return text == null || text.isEmpty()
    }

    private fun trim(text: String?): String {
        return if (text == null) "" else text.trim { it <= ' ' }
    }

    private fun field(text: String?): String {
        return trim(text).replace("/", " ")
    }

    private fun fieldCodec(codec: String?): String {
        return field(codec).lowercase(Locale.ROOT)
    }

    private fun fieldAt(fingerprint: String?, index: Int): String {
        if (fingerprint == null) return ""
        val parts = fingerprint.split("/")
        return if (index >= 0 && index < parts.size) parts[index] else ""
    }
}
