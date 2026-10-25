package com.github.avbox.osc.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

import com.github.avbox.osc.ui.SearchActivity
import com.github.avbox.osc.util.ActivityStack


class SearchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras
        if (action == intent.action && extras != null) {
            if (ActivityStack.getInstance().getActivity(SearchActivity::class.java) != null) {
                ActivityStack.getInstance().backActivity(SearchActivity::class.java)
            } else {
                val newIntent = Intent(context, SearchActivity::class.java)
                newIntent.putExtra("title", extras.getString("title"))
                newIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                context.startActivity(newIntent)
            }
        }
    }

    companion object {
        @JvmField
        var action = "android.content.movie.search.Action"
    }
}
