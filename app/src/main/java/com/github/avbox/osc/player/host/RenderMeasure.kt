package com.github.avbox.osc.player.host

object RenderMeasure {

    const val SCALE_DEFAULT = 0
    const val SCALE_16_9 = 1
    const val SCALE_4_3 = 2
    const val SCALE_MATCH_PARENT = 3
    const val SCALE_ORIGINAL = 4
    const val SCALE_CENTER_CROP = 5

    fun measure(
        widthSpec: Int,
        heightSpec: Int,
        width: Int,
        height: Int,
        scaleType: Int,
        videoWidth: Int,
        videoHeight: Int,
        videoRotationDegree: Int,
    ): IntArray {
        var w = width
        var h = height
        if (videoRotationDegree == 90 || videoRotationDegree == 270) {
            val swapped = w
            w = h
            h = swapped
        }
        if (videoWidth == 0 || videoHeight == 0) {
            return intArrayOf(w, h)
        }
        when (scaleType) {
            SCALE_ORIGINAL -> {
                w = videoWidth
                h = videoHeight
            }
            SCALE_16_9 -> {
                if (h > w / 16 * 9) {
                    h = w / 16 * 9
                } else {
                    w = h / 9 * 16
                }
            }
            SCALE_4_3 -> {
                if (h > w / 4 * 3) {
                    h = w / 4 * 3
                } else {
                    w = h / 3 * 4
                }
            }
            SCALE_MATCH_PARENT -> {
                return if (videoRotationDegree == 90 || videoRotationDegree == 270) {
                    intArrayOf(heightSpec, widthSpec)
                } else {
                    intArrayOf(widthSpec, heightSpec)
                }
            }
            SCALE_CENTER_CROP -> {
                if (videoWidth * h > w * videoHeight) {
                    w = h * videoWidth / videoHeight
                } else {
                    h = w * videoHeight / videoWidth
                }
            }
            else -> {
                if (videoWidth * h < w * videoHeight) {
                    w = h * videoWidth / videoHeight
                } else if (videoWidth * h > w * videoHeight) {
                    h = w * videoHeight / videoWidth
                }
            }
        }
        return intArrayOf(w, h)
    }
}
