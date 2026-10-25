package com.github.avbox.osc.player.engine.iso

fun interface RangeBlockReader {
    fun read(start: Long, length: Int): ByteArray
}
