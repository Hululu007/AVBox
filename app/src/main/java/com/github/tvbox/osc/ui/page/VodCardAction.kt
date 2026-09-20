package com.github.tvbox.osc.ui.page

import android.content.Context
import android.widget.Toast
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.ui.activity.PartitionListActivity
import com.github.tvbox.osc.util.SourceIndexFlags

sealed interface VodCardTarget {
    data class Action(val video: Movie.Video) : VodCardTarget

    data class Folder(val video: Movie.Video) : VodCardTarget

    data class Search(val title: String) : VodCardTarget

    data class Detail(val video: Movie.Video) : VodCardTarget
}

/**
 * 站点级 `indexs` 标记:索引型源的卡片只是关键词/分类入口,点进去没有可播详情,只该走搜索;
 * 普通站点才直接进详情。站点身份缺失时按搜索处理(进详情大概率失败)。
 */
private fun isSearchOnlySource(sourceKey: String?): Boolean {
    if (sourceKey.isNullOrEmpty()) return true
    return SourceIndexFlags.isIndexSource(sourceKey)
}

internal fun Movie.Video.isFolderCard(): Boolean = tag == "folder"

internal fun Movie.Video.hasOpenableDetailId(): Boolean {
    val value = id.orEmpty()
    return value.isNotEmpty() && !value.startsWith("msearch:")
}

fun resolveVodCardTarget(video: Movie.Video): VodCardTarget = when {
    !video.action.isNullOrEmpty() -> VodCardTarget.Action(video)
    video.isFolderCard() -> VodCardTarget.Folder(video)
    !isSearchOnlySource(video.sourceKey) && video.hasOpenableDetailId() ->
        VodCardTarget.Detail(video)
    else -> VodCardTarget.Search(video.name.orEmpty())
}

fun Context.dispatchVodCardClick(video: Movie.Video, onAction: (Movie.Video) -> Unit = {}) {
    when (val target = resolveVodCardTarget(video)) {
        is VodCardTarget.Action -> onAction(video)
        is VodCardTarget.Folder -> openVodFolder(target.video)
        is VodCardTarget.Search -> jumpToSearch(target.title)
        is VodCardTarget.Detail -> jumpToDetail(
            target.video.id,
            target.video.sourceKey,
            target.video.name,
            target.video.pic,
        )
    }
}

fun Context.openVodCardOrDetail(video: Movie.Video) {
    if (video.isFolderCard()) {
        openVodFolder(video)
        return
    }
    jumpToDetail(video.id, video.sourceKey, video.name, video.pic)
}

private fun Context.openVodFolder(video: Movie.Video) {
    val folderId = video.id.orEmpty()
    if (folderId.isEmpty()) {
        Toast.makeText(this, "目录数据缺失,无法打开", Toast.LENGTH_SHORT).show()
        return
    }
    PartitionListActivity.startForFolder(this, folderId, video.name.orEmpty())
}
