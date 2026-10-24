package com.github.tvbox.osc.server

import android.content.Context

import com.github.tvbox.osc.util.LocalAddress

import java.io.IOException

class ControlManager private constructor() {
    @Volatile
    private var mServer: RemoteServer? = null

    fun getAddress(local: Boolean): String {
        if (mServer?.isStarting() != true) {
            startServer()
        }
        val server = mServer ?: return ""
        if (!server.isStarting()) return ""
        return if (local) server.getLoadAddress() else server.getServerAddress()
    }

    fun startServer() {
        if (mServer?.isStarting() == true) {
            return
        }
        val context = requireNotNull(mContext) { "control manager context not initialized" }
        do {
            val server = RemoteServer(RemoteServer.serverPort, context)
            mServer = server
            try {
                server.start()
                onProxyPortReady?.invoke(RemoteServer.serverPort)
                break
            } catch (ex: IOException) {
                RemoteServer.serverPort++
                server.stop()
            }
        } while (RemoteServer.serverPort < 9999)
    }

    fun startedPort(): Int {
        return if (mServer?.isStarting() == true) RemoteServer.serverPort else 0
    }

    fun stopServer() {
        mServer?.let {
            if (it.isStarting()) it.stop()
        }
        mServer = null
    }

    companion object {
        @Volatile
        private var instance: ControlManager? = null

        @JvmField
        var mContext: Context? = null

        @Volatile
        var onProxyPortReady: ((Int) -> Unit)? = null

        @JvmStatic
        fun get(): ControlManager {
            if (instance == null) {
                synchronized(ControlManager::class.java) {
                    if (instance == null) {
                        instance = ControlManager()
                    }
                }
            }
            return requireNotNull(instance) { "control manager instance missing" }
        }

        @JvmStatic
        fun init(context: Context) {
            mContext = context
            LocalAddress.provider = { get().getAddress(true) }
        }
    }
}
