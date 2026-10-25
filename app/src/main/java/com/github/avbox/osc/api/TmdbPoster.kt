package com.github.avbox.osc.api

import com.github.avbox.osc.api.TmdbApi
import com.github.avbox.osc.bean.TmdbDetail
import com.github.avbox.osc.bean.TmdbEpisode
import com.github.avbox.osc.bean.TmdbPerson
import com.github.avbox.osc.bean.TmdbSearchHit
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.MD5
import com.github.avbox.osc.util.TmdbImages
import com.github.avbox.osc.util.net.HttpException
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object TmdbPoster {

    private const val CACHE_KEY_PREFIX = "cache_tmdb_"
    private const val HITS_CACHE_PREFIX = "cache_tmdb_hits_"
    private const val IMAGES_CACHE_PREFIX = "cache_tmdb_images_"
    private const val LOGOS_CACHE_PREFIX = "cache_tmdb_logos_"
    private const val DETAIL_CACHE_PREFIX = "cache_tmdb_detail2_"
    private const val EPISODES_CACHE_PREFIX = "cache_tmdb_episodes2_"
    private const val SEASON_CACHE_PREFIX = "cache_tmdb_season_"
    private const val STILLS_CACHE_PREFIX = "cache_tmdb_stills_"
    private const val PERSON_CACHE_PREFIX = "cache_tmdb_person_"
    private const val ALIAS_HIT_CACHE_PREFIX = "cache_tmdb_alias_"
    private const val ALTS_CACHE_PREFIX = "cache_tmdb_alts_"
    private const val MAX_ALIAS_CANDIDATES = 2
    private const val NEGATIVE_MARK = "-"
    private const val NEGATIVE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val BACKOFF_MS = 60_000L
    private const val MAX_MEMORY_ENTRIES = 512
    private const val MAX_CONCURRENT_REQUESTS = 4
    private const val LIST_SEPARATOR = ","

    private val hitsType: Type = object : TypeToken<List<TmdbSearchHit>>() {}.type

    private val detailType: Type = object : TypeToken<TmdbDetail>() {}.type

    private val personType: Type = object : TypeToken<TmdbPerson>() {}.type

    private val aliasHitType: Type = object : TypeToken<TmdbSearchHit>() {}.type

    private val titlesType: Type = object : TypeToken<List<String>>() {}.type

    private val episodeListType: Type = object : TypeToken<List<TmdbEpisode>>() {}.type

    private val gson = Gson()

    internal var clientFactory: () -> TmdbApi.TmdbClient = { TmdbApi.DefaultTmdbClient() }

    @Volatile
    private var backoffUntil = 0L

    private val memory = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_MEMORY_ENTRIES
    }

    private val inflight = HashMap<String, Deferred<String?>>()

    private val semaphore: Semaphore by lazy { Semaphore(MAX_CONCURRENT_REQUESTS) }

    private val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    fun notifyConfigChanged() {
        synchronized(memory) { memory.clear() }
        try {
            for (key in KV.keys(CACHE_KEY_PREFIX)) KV.delete(key)
        } catch (e: Exception) {
            AppLog.e("TmdbPoster", e)
        }
        TmdbImages.notifyConfigChanged()
    }

    fun clearCachedPosters() {
        notifyConfigChanged()
    }

    private fun isActive(): Boolean = TmdbImages.isActive()

    internal fun nameCandidates(name: String?, altName: String?): List<String> {
        val primary = name.orEmpty().trim()
        val alt = altName.orEmpty().trim()
        return when {
            primary.isEmpty() -> if (alt.isEmpty()) emptyList() else listOf(alt)
            alt.isEmpty() || alt == primary -> listOf(primary)
            else -> listOf(primary, alt)
        }
    }

    fun cachedPoster(name: String?, year: Int, altName: String? = null): String? {
        if (!isActive()) return null
        var known = false
        for (candidate in nameCandidates(name, altName)) {
            val hits = cachedHits(candidate) ?: continue
            known = true
            val best = bestHit(hits, candidate, year) ?: cachedAliasHit(candidate) ?: continue
            return best.posterPath
        }
        return if (known) "" else null
    }

    fun cachedImages(name: String?, year: Int, altName: String? = null): List<String>? {
        if (!isActive()) return null
        var known = false
        for (candidate in nameCandidates(name, altName)) {
            val hits = cachedHits(candidate) ?: continue
            known = true
            val best = bestHit(hits, candidate, year) ?: cachedAliasHit(candidate) ?: continue
            return cachedImagesById(best.mediaType, best.id)
        }
        return if (known) emptyList() else null
    }

    fun cachedBackdrops(name: String?, year: Int, altName: String? = null): List<String>? {
        if (!isActive()) return null
        var known = false
        for (candidate in nameCandidates(name, altName)) {
            val hits = cachedHits(candidate) ?: continue
            known = true
            val best = bestHit(hits, candidate, year) ?: cachedAliasHit(candidate) ?: continue
            return cachedStillsById(best.mediaType, best.id)
        }
        return if (known) emptyList() else null
    }

    fun cachedLogo(name: String?, year: Int, altName: String? = null): String? {
        if (!isActive()) return ""
        var known = false
        for (candidate in nameCandidates(name, altName)) {
            val hits = cachedHits(candidate) ?: continue
            known = true
            val best = bestHit(hits, candidate, year) ?: cachedAliasHit(candidate) ?: continue
            val logos = cachedLogosById(best.mediaType, best.id) ?: return null
            return logos.firstOrNull().orEmpty()
        }
        return if (known) "" else null
    }

    suspend fun resolvePoster(name: String?, year: Int, altName: String? = null): String? {
        for (candidate in nameCandidates(name, altName)) {
            val hits = ensureHits(candidate) ?: continue
            val best = pickBestWithAlias(hits, candidate, year)
            val tvOnly = TmdbApi.parseSeasonHint(candidate) != null
            AppLog.i("echo-tmdb pick q=${TmdbApi.searchQuery(candidate)} tvOnly=$tvOnly -> ${best?.posterPath ?: "none"}")
            best?.let { return it.posterPath }
        }
        return ""
    }

    suspend fun resolveImages(name: String?, year: Int, altName: String? = null): List<String> {
        for (candidate in nameCandidates(name, altName)) {
            val hits = ensureHits(candidate) ?: continue
            val best = pickBestWithAlias(hits, candidate, year) ?: continue
            return ensureImages(best.mediaType, best.id)
        }
        return emptyList()
    }

    suspend fun resolveBackdrops(name: String?, year: Int, altName: String? = null): List<String> {
        for (candidate in nameCandidates(name, altName)) {
            val hits = ensureHits(candidate) ?: continue
            val best = pickBestWithAlias(hits, candidate, year) ?: continue
            return ensureStills(best.mediaType, best.id)
        }
        return emptyList()
    }

    suspend fun resolveLogo(name: String?, year: Int, altName: String? = null): String {
        if (!isActive()) return ""
        for (candidate in nameCandidates(name, altName)) {
            val hits = ensureHits(candidate) ?: continue
            val best = pickBestWithAlias(hits, candidate, year) ?: continue
            return ensureLogos(best.mediaType, best.id).firstOrNull().orEmpty()
        }
        return ""
    }

    fun cachedDetail(name: String?, year: Int, altName: String? = null): TmdbDetail? {
        if (!isActive()) return null
        for (candidate in nameCandidates(name, altName)) {
            val hits = cachedHits(candidate) ?: continue
            val best = bestHit(hits, candidate, year) ?: cachedAliasHit(candidate) ?: continue
            val raw = readCached(detailKey(best.mediaType, best.id)) ?: return null
            return decodeDetailValue(raw)
        }
        return null
    }

    fun cachedPerson(id: Int): TmdbPerson? {
        if (id <= 0 || !isActive()) return null
        val raw = readCached(personKey(id)) ?: return null
        return decodePersonValue(raw)
    }

    suspend fun resolvePerson(id: Int): TmdbPerson? {
        if (id <= 0) return null
        val key = personKey(id)
        val cachedRaw = readCached(key)
        if (cachedRaw != null) return decodePersonValue(cachedRaw)
        if (!isActive() || onBackoff()) return null
        val raw = requestShared(key) { fetchPerson(id) } ?: return null
        writeCached(key, raw)
        return decodePersonValue(raw)
    }

    suspend fun resolveDetail(name: String?, year: Int, altName: String? = null): TmdbDetail? {
        for (candidate in nameCandidates(name, altName)) {
            val hits = ensureHits(candidate) ?: continue
            val best = pickBestWithAlias(hits, candidate, year) ?: continue
            return ensureDetail(best.mediaType, best.id)
        }
        return null
    }

    fun cachedEpisodeMeta(
        name: String?,
        episodeCount: Int,
        seasonHint: Int?,
        episodeNumbers: List<Int>?,
        altName: String? = null,
    ): List<TmdbEpisode>? {
        if (!isActive() || episodeCount <= 0) return null
        for (candidate in nameCandidates(name, altName)) {
            val key = episodesKey(candidate, episodeCount, seasonHint, episodeNumbers) ?: continue
            val raw = readCached(key) ?: continue
            return decodeEpisodesValue(raw)
        }
        return null
    }

    suspend fun resolveEpisodeMeta(
        name: String?,
        year: Int,
        episodeCount: Int,
        seasonHint: Int?,
        episodeNumbers: List<Int>?,
        altName: String? = null,
    ): List<TmdbEpisode>? {
        if (episodeCount <= 0) return null
        val candidates = nameCandidates(name, altName)
        for (candidate in candidates) {
            val key = episodesKey(candidate, episodeCount, seasonHint, episodeNumbers) ?: continue
            val cachedRaw = readCached(key)
            if (cachedRaw != null) return decodeEpisodesValue(cachedRaw)
            val resolved = resolveEpisodeMetaForName(candidate, year, episodeCount, seasonHint, episodeNumbers)
            if (resolved != null) return resolved
        }
        AppLog.i("echo-tmdb episodes names=${candidates.joinToString("|")} count=$episodeCount")
        return null
    }

    private suspend fun resolveEpisodeMetaForName(
        name: String,
        year: Int,
        episodeCount: Int,
        seasonHint: Int?,
        episodeNumbers: List<Int>?,
    ): List<TmdbEpisode>? {
        val key = episodesKey(name, episodeCount, seasonHint, episodeNumbers) ?: return null
        val query = TmdbApi.searchQuery(name)
        val hits = ensureHits(name) ?: return null
        val best = pickBestWithAlias(hits, name, year)
        if (best == null) {
            AppLog.i("echo-tmdb episodes skip q=$query reason=nomatch count=$episodeCount")
            return null
        }
        if (best.mediaType != TmdbApi.MEDIA_TV) {
            AppLog.i("echo-tmdb episodes movie=${best.id} count=$episodeCount thumbs=off")
            writeCached(key, "[]")
            return emptyList()
        }
        val detail = ensureDetail(best.mediaType, best.id)
        if (detail == null) {
            AppLog.i("echo-tmdb episodes skip q=$query reason=detail count=$episodeCount")
            return null
        }
        val byNumber = episodeNumbers?.let {
            TmdbApi.mapEpisodesByNumber(detail.seasons, episodeCount, seasonHint, it)
        }
        val refs = byNumber ?: TmdbApi.mapEpisodes(detail.seasons, episodeCount, seasonHint)
        if (refs == null) {
            AppLog.i(
                "echo-tmdb episodes skip q=$query reason=map count=$episodeCount hint=$seasonHint " +
                    "seasons=${detail.seasons.joinToString("|") { it.seasonNumber.toString() + ":" + it.episodeCount }}",
            )
            return null
        }
        val seasonCache = HashMap<Int, List<TmdbEpisode>>()
        val episodes = ArrayList<TmdbEpisode>(episodeCount)
        for (ref in refs) {
            val seasonEpisodes = seasonCache.getOrPut(ref.seasonNumber) {
                ensureSeason(best.id, ref.seasonNumber)
            }
            episodes.add(seasonEpisodes.getOrNull(ref.episodeNumber - 1) ?: TmdbEpisode("", ""))
        }
        val fold = TmdbApi.foldSegmentsPerEpisode(detail.seasons, episodeCount, seasonHint)
        AppLog.i("echo-tmdb episodes tv=${best.id} count=$episodeCount hint=$seasonHint fold=$fold byNumber=${byNumber != null}")
        val encoded = gson.toJson(episodes, episodeListType)
        writeCached(key, encoded)
        return episodes
    }

    internal fun encodeHitsValue(hits: List<TmdbSearchHit>, now: Long): String =
        if (hits.isEmpty()) NEGATIVE_MARK + (now + NEGATIVE_TTL_MS) else gson.toJson(hits, hitsType)

    internal fun decodeHitsValue(raw: String, now: Long): List<TmdbSearchHit>? {
        if (raw.startsWith(NEGATIVE_MARK)) {
            val expireAt = raw.substring(1).toLongOrNull() ?: return null
            return if (expireAt > now) emptyList() else null
        }
        return try {
            gson.fromJson<List<TmdbSearchHit>>(raw, hitsType).orEmpty()
        } catch (e: Exception) {
            AppLog.e("TmdbPoster", e)
            null
        }
    }

    internal fun decodeImagesValue(raw: String, now: Long): List<String>? {
        if (!raw.startsWith(NEGATIVE_MARK)) return splitPaths(raw)
        val expireAt = raw.substring(1).toLongOrNull() ?: return null
        return if (expireAt > now) emptyList() else null
    }

    internal fun bestHit(
        hits: List<TmdbSearchHit>,
        name: String?,
        year: Int,
    ): TmdbSearchHit? {
        val tvOnly = TmdbApi.parseSeasonHint(name) != null
        TmdbApi.pickBest(hits, name, year, tvOnly)?.let { return it }
        val variant = TmdbApi.qualityTagVariant(name)
        if (variant.isEmpty()) return null
        return TmdbApi.pickBest(hits, variant, year, tvOnly)
    }

    private suspend fun pickBestWithAlias(
        hits: List<TmdbSearchHit>,
        name: String?,
        year: Int,
    ): TmdbSearchHit? {
        bestHit(hits, name, year)?.let { return it }
        cachedAliasHit(name)?.let { return it }
        return findHitByAlias(hits, name)
    }

    private fun cachedAliasHit(name: String?): TmdbSearchHit? {
        val key = aliasHitKey(name) ?: return null
        val raw = readCached(key) ?: return null
        val hit = decodeAliasHitValue(raw) ?: return null
        if (TmdbApi.parseSeasonHint(name) != null && hit.mediaType != TmdbApi.MEDIA_TV) return null
        return hit
    }

    private suspend fun findHitByAlias(
        hits: List<TmdbSearchHit>,
        name: String?,
    ): TmdbSearchHit? {
        val target = TmdbApi.normalizeForMatch(name)
        if (target.isEmpty()) return null
        val tvOnly = TmdbApi.parseSeasonHint(name) != null
        val candidates = hits.filter { it.posterPath.isNotEmpty() && (!tvOnly || it.mediaType == TmdbApi.MEDIA_TV) }
        for (hit in candidates.take(MAX_ALIAS_CANDIDATES)) {
            val titles = ensureAlternativeTitles(hit.mediaType, hit.id) ?: continue
            if (titles.any { TmdbApi.normalizeForMatch(it) == target }) {
                AppLog.i("echo-tmdb alias hit id=${hit.id} media=${hit.mediaType} title=${hit.title}")
                writeAliasHit(name, hit)
                return hit
            }
        }
        return null
    }

    private fun writeAliasHit(name: String?, hit: TmdbSearchHit) {
        val key = aliasHitKey(name) ?: return
        writeCached(key, gson.toJson(hit, aliasHitType))
    }

    private suspend fun ensureAlternativeTitles(mediaType: String, id: Int): List<String>? {
        val key = altsKey(mediaType, id)
        val cachedRaw = readCached(key)
        if (cachedRaw != null) return decodeTitlesValue(cachedRaw)
        if (!isActive() || onBackoff()) return null
        val raw = requestShared(key) { fetchAlternativeTitles(mediaType, id) } ?: return null
        writeCached(key, raw)
        return decodeTitlesValue(raw)
    }

    private fun aliasHitKey(name: String?): String? {
        val normalized = TmdbApi.normalizeForMatch(name)
        if (normalized.isEmpty()) return null
        return ALIAS_HIT_CACHE_PREFIX + MD5.encode(normalized)
    }

    private fun altsKey(mediaType: String, id: Int): String = ALTS_CACHE_PREFIX + mediaType + "_" + id

    private fun episodesKey(
        name: String?,
        episodeCount: Int,
        seasonHint: Int?,
        episodeNumbers: List<Int>?,
    ): String? {
        val normalized = TmdbApi.normalizeForMatch(name)
        if (normalized.isEmpty()) return null
        val numbers = episodeNumbers?.joinToString(",").orEmpty()
        return EPISODES_CACHE_PREFIX + MD5.encode(
            normalized + "|" + episodeCount + "|" + (seasonHint ?: 0) + "|" + numbers,
        )
    }

    private fun seasonKey(tvId: Int, seasonNumber: Int): String = SEASON_CACHE_PREFIX + tvId + "_" + seasonNumber

    internal fun decodeAliasHitValue(raw: String): TmdbSearchHit? = try {
        gson.fromJson(raw, aliasHitType)
    } catch (e: Exception) {
        AppLog.e("TmdbPoster", e)
        null
    }

    internal fun decodeTitlesValue(raw: String): List<String>? = try {
        gson.fromJson<List<String>>(raw, titlesType).orEmpty()
    } catch (e: Exception) {
        AppLog.e("TmdbPoster", e)
        null
    }

    private fun cachedHits(name: String?): List<TmdbSearchHit>? {
        val key = hitsKey(name) ?: return emptyList()
        val raw = readCached(key) ?: return null
        return decodeHitsValue(raw, System.currentTimeMillis())
    }

    private suspend fun ensureHits(name: String?): List<TmdbSearchHit>? {
        val key = hitsKey(name) ?: return emptyList()
        val cached = cachedHits(name)
        if (cached != null) return cached
        if (!isActive() || onBackoff()) return null
        val raw = requestShared(key) { fetchHits(name.orEmpty()) } ?: return null
        writeCached(key, raw)
        return decodeFreshHits(raw)
    }

    private fun cachedImagesById(mediaType: String, id: Int): List<String>? {
        val raw = readCached(imagesKey(mediaType, id)) ?: return null
        return decodeImagesValue(raw, System.currentTimeMillis())
    }

    private fun cachedStillsById(mediaType: String, id: Int): List<String>? {
        val raw = readCached(stillsKey(mediaType, id)) ?: return null
        return decodeImagesValue(raw, System.currentTimeMillis())
    }

    private fun cachedLogosById(mediaType: String, id: Int): List<String>? {
        val raw = readCached(logosKey(mediaType, id)) ?: return null
        return decodeImagesValue(raw, System.currentTimeMillis())
    }

    private suspend fun ensureImages(mediaType: String, id: Int): List<String> {
        val key = imagesKey(mediaType, id)
        val cached = cachedImagesById(mediaType, id)
        if (cached != null) return cached
        if (!isActive() || onBackoff()) return emptyList()
        val raw = requestShared(key) { fetchImages(mediaType, id) } ?: return emptyList()
        writeCached(key, raw)
        return decodeFreshImages(raw)
    }

    private suspend fun ensureDetail(mediaType: String, id: Int): TmdbDetail? {
        val key = detailKey(mediaType, id)
        val cachedRaw = readCached(key)
        if (cachedRaw != null) decodeDetailValue(cachedRaw)?.let { return it }
        if (!isActive() || onBackoff()) return null
        val raw = requestShared(key) { fetchDetail(mediaType, id) } ?: return null
        writeCached(key, raw)
        return decodeDetailValue(raw)
    }

    private suspend fun ensureStills(mediaType: String, id: Int): List<String> {
        val key = stillsKey(mediaType, id)
        val cachedRaw = readCached(key)
        if (cachedRaw != null) return decodeImagesValue(cachedRaw, System.currentTimeMillis()).orEmpty()
        if (!isActive() || onBackoff()) return emptyList()
        val raw = requestShared(key) { fetchStills(mediaType, id) } ?: return emptyList()
        writeCached(key, raw)
        return decodeFreshImages(raw)
    }

    private suspend fun ensureLogos(mediaType: String, id: Int): List<String> {
        val key = logosKey(mediaType, id)
        val cached = cachedLogosById(mediaType, id)
        if (cached != null) return cached
        if (!isActive() || onBackoff()) return emptyList()
        val raw = requestShared(key) { fetchLogos(mediaType, id) } ?: return emptyList()
        writeCached(key, raw)
        return decodeFreshImages(raw)
    }

    private suspend fun ensureSeason(tvId: Int, seasonNumber: Int): List<TmdbEpisode> {
        val key = seasonKey(tvId, seasonNumber)
        val cachedRaw = readCached(key)
        if (cachedRaw != null) return decodeEpisodesValue(cachedRaw).orEmpty()
        if (!isActive() || onBackoff()) return emptyList()
        val raw = requestShared(key) { fetchSeason(tvId, seasonNumber) } ?: return emptyList()
        writeCached(key, raw)
        return decodeEpisodesValue(raw).orEmpty()
    }

    internal fun decodeDetailValue(raw: String): TmdbDetail? = try {
        gson.fromJson(raw, detailType)
    } catch (e: Exception) {
        AppLog.e("TmdbPoster", e)
        null
    }

    internal fun decodeEpisodesValue(raw: String): List<TmdbEpisode>? = try {
        gson.fromJson<List<TmdbEpisode>>(raw, episodeListType).orEmpty()
    } catch (e: Exception) {
        AppLog.e("TmdbPoster", e)
        null
    }

    internal fun decodePersonValue(raw: String): TmdbPerson? = try {
        gson.fromJson(raw, personType)
    } catch (e: Exception) {
        AppLog.e("TmdbPoster", e)
        null
    }

    private fun detailKey(mediaType: String, id: Int): String = DETAIL_CACHE_PREFIX + mediaType + "_" + id

    private fun personKey(id: Int): String = PERSON_CACHE_PREFIX + id

    private fun hitsKey(name: String?): String? {
        val normalized = TmdbApi.normalizeForMatch(name)
        if (normalized.isEmpty()) return null
        return HITS_CACHE_PREFIX + MD5.encode(normalized)
    }

    private fun imagesKey(mediaType: String, id: Int): String = IMAGES_CACHE_PREFIX + mediaType + "_" + id

    private fun stillsKey(mediaType: String, id: Int): String = STILLS_CACHE_PREFIX + mediaType + "_" + id

    private fun logosKey(mediaType: String, id: Int): String = LOGOS_CACHE_PREFIX + mediaType + "_" + id

    private fun decodeFreshHits(raw: String): List<TmdbSearchHit> =
        decodeHitsValue(raw, System.currentTimeMillis()).orEmpty()

    private fun decodeFreshImages(raw: String): List<String> =
        if (raw.startsWith(NEGATIVE_MARK)) emptyList() else splitPaths(raw)

    private fun splitPaths(raw: String): List<String> =
        if (raw.isEmpty()) emptyList() else raw.split(LIST_SEPARATOR).filter { it.isNotEmpty() }

    private fun readCached(key: String): String? {
        synchronized(memory) { memory[key] }?.let { return it }
        val raw = kvGetString(key) ?: return null
        synchronized(memory) { memory[key] = raw }
        return raw
    }

    private fun writeCached(key: String, value: String) {
        synchronized(memory) { memory[key] = value }
        kvPutString(key, value)
    }

    private suspend fun requestShared(key: String, fetch: suspend () -> String?): String? {
        val deferred = synchronized(inflight) {
            inflight.getOrPut(key) { scope.async { fetch() } }
        }
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("TmdbPoster", e)
            null
        } finally {
            synchronized(inflight) { if (inflight[key] === deferred) inflight.remove(key) }
        }
    }

    private suspend fun fetchHits(name: String): String? = semaphore.withPermit {
        val query = TmdbApi.searchQuery(name)
        try {
            var hits = clientFactory().search(readApiKey(), readApiBase(), query)
            AppLog.i("echo-tmdb search q=$query hits=${hits.size} top=${describeHits(hits)}")
            if (hits.isEmpty()) {
                val variant = TmdbApi.qualityTagVariant(name)
                if (variant.isNotEmpty() && variant != query) {
                    hits = clientFactory().search(readApiKey(), readApiBase(), variant)
                    AppLog.i("echo-tmdb search retry q=$variant hits=${hits.size} top=${describeHits(hits)}")
                }
            }
            encodeHitsValue(hits, System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.i("echo-tmdb fail q=$query code=${httpCodeOf(e)} msg=${e.message}")
            markBackoff(e)
            null
        }
    }

    private suspend fun fetchImages(mediaType: String, id: Int): String? = semaphore.withPermit {
        try {
            val paths = clientFactory().images(readApiKey(), readApiBase(), mediaType, id)
            AppLog.i("echo-tmdb images id=$id media=$mediaType count=${paths.size}")
            if (paths.isEmpty()) negativeValue() else paths.joinToString(LIST_SEPARATOR)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.i("echo-tmdb fail path=images id=$id code=${httpCodeOf(e)} msg=${e.message}")
            markBackoff(e)
            null
        }
    }

    private suspend fun fetchStills(mediaType: String, id: Int): String? = semaphore.withPermit {
        try {
            val paths = clientFactory().stills(readApiKey(), readApiBase(), mediaType, id)
            AppLog.i("echo-tmdb stills id=$id media=$mediaType count=${paths.size}")
            if (paths.isEmpty()) negativeValue() else paths.joinToString(LIST_SEPARATOR)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.i("echo-tmdb fail path=stills id=$id code=${httpCodeOf(e)} msg=${e.message}")
            markBackoff(e)
            null
        }
    }

    private suspend fun fetchLogos(mediaType: String, id: Int): String? = semaphore.withPermit {
        try {
            val paths = clientFactory().logos(readApiKey(), readApiBase(), mediaType, id)
            AppLog.i("echo-tmdb logos id=$id media=$mediaType count=${paths.size}")
            if (paths.isEmpty()) negativeValue() else paths.joinToString(LIST_SEPARATOR)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.i("echo-tmdb fail path=logos id=$id code=${httpCodeOf(e)} msg=${e.message}")
            markBackoff(e)
            null
        }
    }

    private suspend fun fetchDetail(mediaType: String, id: Int): String? = semaphore.withPermit {
        try {
            val detail = clientFactory().detail(readApiKey(), readApiBase(), mediaType, id)
                ?: return@withPermit null
            AppLog.i("echo-tmdb detail id=$id media=$mediaType cast=${detail.cast.size} genres=${detail.genres.size}")
            gson.toJson(detail, detailType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.i("echo-tmdb fail path=detail id=$id code=${httpCodeOf(e)} msg=${e.message}")
            markBackoff(e)
            null
        }
    }

    private suspend fun fetchPerson(id: Int): String? = semaphore.withPermit {
        try {
            val person = clientFactory().person(readApiKey(), readApiBase(), id) ?: return@withPermit null
            AppLog.i("echo-tmdb person id=$id bio=${person.biography.length}")
            gson.toJson(person, personType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.i("echo-tmdb fail path=person id=$id code=${httpCodeOf(e)} msg=${e.message}")
            markBackoff(e)
            null
        }
    }

    private suspend fun fetchAlternativeTitles(mediaType: String, id: Int): String? = semaphore.withPermit {
        try {
            val titles = clientFactory().alternativeTitles(readApiKey(), readApiBase(), mediaType, id)
            AppLog.i("echo-tmdb alts id=$id media=$mediaType count=${titles.size}")
            gson.toJson(titles, titlesType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.i("echo-tmdb fail path=alts id=$id code=${httpCodeOf(e)} msg=${e.message}")
            markBackoff(e)
            null
        }
    }

    private suspend fun fetchSeason(tvId: Int, seasonNumber: Int): String? = semaphore.withPermit {
        try {
            val episodes = clientFactory().season(readApiKey(), readApiBase(), tvId, seasonNumber)
            AppLog.i("echo-tmdb season tv=$tvId s=$seasonNumber count=${episodes.size}")
            gson.toJson(episodes, episodeListType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.i("echo-tmdb fail path=season tv=$tvId s=$seasonNumber code=${httpCodeOf(e)} msg=${e.message}")
            markBackoff(e)
            null
        }
    }

    private fun describeHits(hits: List<TmdbSearchHit>): String =
        hits.take(3).joinToString("|") { it.title + "#" + it.year }

    private fun httpCodeOf(e: Exception): Int = (e as? HttpException)?.code ?: -1

    private fun negativeValue(): String = NEGATIVE_MARK + (System.currentTimeMillis() + NEGATIVE_TTL_MS)

    private fun markBackoff(e: Exception) {
        if (e is HttpException && e.code == TmdbApi.HTTP_TOO_MANY_REQUESTS) {
            backoffUntil = System.currentTimeMillis() + BACKOFF_MS
        }
        AppLog.e("TmdbPoster", e)
    }

    private fun onBackoff(): Boolean = System.currentTimeMillis() < backoffUntil

    private fun readApiKey(): String = try {
        KV.get(KVKeys.TMDB_API_KEY, "").trim()
    } catch (e: Exception) {
        AppLog.e("TmdbPoster", e)
        ""
    }

    private fun readApiBase(): String = try {
        KV.get(KVKeys.TMDB_API_BASE, "")
    } catch (e: Exception) {
        AppLog.e("TmdbPoster", e)
        ""
    }

    private fun kvGetString(key: String): String? {
        return try {
            val value: String? = KV.get(key)
            value
        } catch (e: Exception) {
            AppLog.e("TmdbPoster", e)
            null
        }
    }

    private fun kvPutString(key: String, value: String) {
        try {
            KV.put(key, value)
        } catch (e: Exception) {
            AppLog.e("TmdbPoster", e)
        }
    }
}
