package com.github.avbox.osc.server

import fi.iki.elonen.NanoHTTPD

interface RequestProcess {
    fun isRequest(session: NanoHTTPD.IHTTPSession, fileName: String?): Boolean

    fun doResponse(
        session: NanoHTTPD.IHTTPSession,
        fileName: String?,
        params: MutableMap<String, String>?,
        files: MutableMap<String, String>?,
    ): NanoHTTPD.Response

    companion object {
        const val KEY_ACTION_PRESSED = 0
        const val KEY_ACTION_DOWN = 1
        const val KEY_ACTION_UP = 2
    }
}
