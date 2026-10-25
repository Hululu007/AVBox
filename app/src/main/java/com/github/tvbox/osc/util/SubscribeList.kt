package com.github.tvbox.osc.util

object SubscribeList {

    private const val Split = "\t"

    fun vodUrls(): Set<String> = parseNamed(HawkConfig.SUBSCRIBE_LIST) + raw(HawkConfig.API_HISTORY)

    private fun parseNamed(key: String): Set<String> =
        KV.get(key, ArrayList<String>())
            .map { value ->
                val index = value.indexOf(Split)
                if (index < 0) value else value.substring(index + Split.length)
            }
            .map { it.filterNot { ch -> ch.isISOControl() }.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun raw(key: String): Set<String> =
        KV.get(key, ArrayList<String>())
            .map { it.filterNot { ch -> ch.isISOControl() }.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
}
