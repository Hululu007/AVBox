package com.github.avbox.osc.api

import android.text.TextUtils

import com.github.avbox.osc.bean.SourceBean
import com.github.avbox.osc.util.AppLog

import java.util.HashSet
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class WarmQueue(private val owner: ApiConfig, private val spiderLoader: SpiderLoader) {

    private companion object {
        private const val WARM_ITEM_TIMEOUT_MS = 10_000L
    }

    private val warmExecutor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "spider-warm") }
    private val warmItemExecutor: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "spider-warm-item") }
    private val configGeneration = AtomicInteger()

    fun bumpGeneration() {
        configGeneration.incrementAndGet()
    }

    fun warmSearchSpiders(sources: List<SourceBean?>, home: SourceBean?) {
        val sharedSpiderApis: MutableSet<String> = HashSet()
        val spiderApis: MutableSet<String> = HashSet()
        for (source in sources) {
            if (source == null || source.type != 3) continue
            val spiderApiKey = source.jar + "|" + source.api
            if (!spiderApis.add(spiderApiKey)) sharedSpiderApis.add(spiderApiKey)
        }
        val generation = configGeneration.get()
        warmExecutor.execute(Runnable {
            AppLog.i("echo-warm-spider start")
            var eligibleCount = 0
            for (source in sources) {
                if (generation != configGeneration.get()) {
                    AppLog.i("echo-warm-spider stop: config changed")
                    break
                }
                if (source == null || source.type != 3 || !source.isSearchable()) continue
                if (home != null && TextUtils.equals(home.key, source.key)) continue
                if (sharedSpiderApis.contains(source.jar + "|" + source.api)) continue
                if (eligibleCount >= 10) break
                eligibleCount++
                val warmKey = source.key + "|" + source.api + "|" + source.jar + "|" + source.ext
                if (!spiderLoader.markWarmed(warmKey)) continue
                AppLog.i("echo-warm-spider load:" + warmKey)
                if (!warmOneSource(source, warmKey, generation)) break
            }
        })
    }

    private fun warmOneSource(source: SourceBean, warmKey: String, generation: Int): Boolean {
        val task: Future<*> = warmItemExecutor.submit(Runnable {
            if (generation != configGeneration.get()) {
                AppLog.i("echo-warm-spider drop:" + warmKey)
                return@Runnable
            }
            owner.getCSP(source)
        })
        try {
            task.get(WARM_ITEM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            return true
        } catch (e: TimeoutException) {
            AppLog.e("echo-warm-spider timeout:" + warmKey)
            return true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } catch (e: ExecutionException) {
            AppLog.e("echo-warm-search-spider-error " + source.key + ":" + e.message)
            return true
        }
    }
}
