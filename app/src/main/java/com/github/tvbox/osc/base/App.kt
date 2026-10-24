package com.github.tvbox.osc.base

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StrictMode
import android.os.SystemClock

import com.github.catvod.crawler.JsLoader
import com.github.tvbox.osc.BuildConfig
import com.github.tvbox.osc.boot.BootGuard
import com.github.tvbox.osc.data.AppDataManager
import com.github.tvbox.osc.io.FileUtils
import com.github.tvbox.osc.net.OkGoHelper
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.util.AppContextHolder
import com.github.tvbox.osc.util.AppManager
import com.github.tvbox.osc.util.EpgUtil
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.util.Probe
import com.github.tvbox.osc.util.VodImages
import com.whl.quickjs.android.QuickJSLoader

import me.jessyan.autosize.AutoSizeConfig
import me.jessyan.autosize.unit.Subunits

class App : Application() {

    override fun attachBaseContext(base: Context) {
        Probe.step("kvInit") { KV.init(base) }
        super.attachBaseContext(LanguageManager.wrap(base))
    }

    private var bootLastMs = 0L

    private fun bootMark(step: String) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        LOG.i("echo-boot $step dt=" + (now - bootLastMs) + "ms")
        bootLastMs = now
    }

    override fun onCreate() {
        super.onCreate()
        bootLastMs = SystemClock.elapsedRealtime()
        installStrictMode()
        bootMark("installStrictMode")
        instance = this
        AppContextHolder.install(this)
        BootGuard.install()
        bootMark("bootGuard")
        initParams()
        bootMark("initParams")
        Probe.trace("boot:okGoHelper") { OkGoHelper.init() }
        bootMark("okGoHelper")
        Probe.trace("boot:epgUtil") { EpgUtil.init() }
        bootMark("epgUtil")
        Probe.trace("boot:controlManager") { ControlManager.init(this) }
        bootMark("controlManager")
        Probe.trace("boot:appDataManager") { AppDataManager.init() }
        bootMark("appDataManager")
        Probe.trace("boot:autoSize") {
            AutoSizeConfig.getInstance().setCustomFragment(true).unitsManager
                .setSupportDP(false)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM)
        }
        bootMark("autoSize")
        Probe.trace("boot:quickJSLoader") { loadQuickJs() }
        bootMark("quickJSLoader")
        Probe.trace("boot:vodImages") { VodImages.init() }
        bootMark("vodImages")
        Thread({
            FileUtils.ensureNoMediaMarker()
            Probe.trace("boot:cleanPlayerCache") { FileUtils.cleanPlayerCache() }
        }, "exo-cache-boot").start()
        bootMark("onCreateDone")
    }

    private fun loadQuickJs() {
        val previous = StrictMode.allowThreadDiskReads()
        try {
            QuickJSLoader.init()
        } finally {
            StrictMode.setThreadPolicy(previous)
        }
    }

    private fun initParams() {
        KV.put(HawkConfig.PLAYER_IS_LIVE, false)
        if (!KV.contains(HawkConfig.PLAY_TYPE)) {
            KV.put(HawkConfig.PLAY_TYPE, 2)
        } else {
            val playType = KV.get(HawkConfig.PLAY_TYPE, 2)
            if (playType == 0 || playType == 1 || (playType > 2 && playType != 13)) {
                KV.put(HawkConfig.PLAY_TYPE, 2)
            }
        }
    }

    private fun installStrictMode() {
        if (!BuildConfig.DEBUG) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .detectFileUriExposure()
                .penaltyLog()
                .build()
        )
    }

    override fun onTerminate() {
        super.onTerminate()
        JsLoader.destroy()
    }

    fun getCurrentActivity(): Activity {
        return AppManager.getInstance().currentActivity()
    }

    fun setDashData(data: String?) {
        dashData = data
    }

    fun getDashData(): String? {
        return dashData
    }

    companion object {
        private var instance: App? = null

        private var dashData: String? = null

        @JvmStatic
        fun getInstance(): App? {
            return instance
        }
    }
}
