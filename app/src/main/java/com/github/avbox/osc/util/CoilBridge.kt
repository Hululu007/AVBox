package com.github.avbox.osc.util

import coil3.network.NetworkFetcher
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.Call

object CoilBridge {

    @JvmStatic
    fun okhttpFetcher(callFactory: () -> Call.Factory): NetworkFetcher.Factory {
        return OkHttpNetworkFetcherFactory(callFactory)
    }
}
