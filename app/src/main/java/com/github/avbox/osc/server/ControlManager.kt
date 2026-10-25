package com.github.avbox.osc.server

import android.content.Context

import com.github.avbox.osc.util.LocalAddress

import java.io.IOException

class ControlManager private constructor() {
    @Volatile
    private var httpServer: RemoteServer? = null

    fun getAddress(local: Boolean): String {
        if (httpServer?.isStarting() != true) {
            startServer()
        }
        val server = httpServer ?: return ""
        if (!server.isStarting()) return ""
        return if (local) server.loadAddress() else server.serverAddress()
    }

    fun startServer() {
        if (httpServer?.isStarting() == true) {
            return
        }
        val context = requireNotNull(hostContext) { "control manager context not initialized" }
        do {
            val server = RemoteServer(RemoteServer.serverPort, context)
            httpServer = server
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
        return if (httpServer?.isStarting() == true) RemoteServer.serverPort else 0
    }

    fun stopServer() {
        httpServer?.let {
            if (it.isStarting()) it.stop()
        }
        httpServer = null
    }

    companion object {
        @Volatile
        private var instance: ControlManager? = null

        @JvmField
        var hostContext: Context? = null

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
            hostContext = context
            LocalAddress.provider = { get().getAddress(true) }
        }
    }
}
