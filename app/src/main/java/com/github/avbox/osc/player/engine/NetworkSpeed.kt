package com.github.avbox.osc.player.engine

import android.content.Context
import android.net.TrafficStats

object NetworkSpeed {

    private var lastTotalRxBytes = 0L
    private var lastTimeStamp = 0L

    @JvmStatic
    fun netSpeed(context: Context?): Long {
        if (context == null) {
            return 0
        }
        val nowTotalRxBytes = if (TrafficStats.getUidRxBytes(context.applicationInfo.uid) == TrafficStats.UNSUPPORTED.toLong()) {
            0L
        } else {
            TrafficStats.getTotalRxBytes()
        }
        val nowTimeStamp = System.currentTimeMillis()
        val calculationTime = nowTimeStamp - lastTimeStamp
        if (calculationTime == 0L) {
            return calculationTime
        }
        val speed = (nowTotalRxBytes - lastTotalRxBytes) * 1000 / calculationTime
        lastTimeStamp = nowTimeStamp
        lastTotalRxBytes = nowTotalRxBytes
        return speed
    }
}
