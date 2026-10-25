package com.github.avbox.osc.bean

import java.util.ArrayList

class LiveChannelGroup {
    var groupIndex: Int = 0
    var groupName: String? = null
    var groupPassword: String? = null
    var liveChannels: ArrayList<LiveChannelItem>? = null
}
