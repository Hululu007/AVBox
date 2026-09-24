package com.github.tvbox.osc.ui.activity

internal object DetailFullScreenGate {

    internal fun refusalReason(
        state: DetailViewModel.PageState,
        loadingText: () -> String,
        emptyText: () -> String,
    ): String? = when (state) {
        is DetailViewModel.PageState.Empty -> state.msg?.takeIf { it.isNotBlank() } ?: emptyText()
        DetailViewModel.PageState.Ready -> null
        DetailViewModel.PageState.Loading -> loadingText()
    }
}
