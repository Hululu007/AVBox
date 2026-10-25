package com.github.catvod

import com.github.avbox.osc.server.RemoteServer
import com.github.avbox.osc.util.AppContextHolder

class Proxy {

    companion object {

        private var port: Int = RemoteServer.serverPort

        @JvmStatic
        fun set(port: Int) {
            Proxy.port = port
        }

        @JvmStatic
        fun getPort(): Int {
            return if (port > 0) port else RemoteServer.serverPort
        }

        @JvmStatic
        fun getUrl(local: Boolean): String {
            return "http://" + (if (local) "127.0.0.1" else getIp()) + ":" + getPort() + "/proxy"
        }

        private fun getIp(): String {
            try {
                return RemoteServer.getLocalIPAddress(AppContextHolder.context())
            } catch (th: Throwable) {
                return "127.0.0.1"
            }
        }
    }
}
