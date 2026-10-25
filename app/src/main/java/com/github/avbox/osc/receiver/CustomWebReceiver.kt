package com.github.avbox.osc.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.ArrayList

class CustomWebReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras
        if (action == intent.action && extras != null) {
            val actionValue = extras.getString("action")
            if (actionValue == REFRESH_PARSE) {
                return
            } else if (actionValue == REFRESH_LIVE) {
                return
            } else {
                return
            }
        }
    }

    companion object {
        @JvmField
        var action = "android.content.movie.custom.web.Action"

        @JvmField
        var REFRESH_SOURCE = "source"

        @JvmField
        var REFRESH_LIVE = "live"

        @JvmField
        var REFRESH_PARSE = "parse"

        @JvmField
        var callback: MutableList<Callback> = ArrayList()
    }

    interface Callback {
        fun onChange(action: String?, obj: Any?)
    }
}
