package com.github.avbox.osc.ui

internal const val FlatHeroMinSmallestWidthDp = 600

internal fun flatHeroLayout(smallestWidthDp: Int, widthDp: Int, heightDp: Int): Boolean =
    smallestWidthDp >= FlatHeroMinSmallestWidthDp && widthDp > heightDp
