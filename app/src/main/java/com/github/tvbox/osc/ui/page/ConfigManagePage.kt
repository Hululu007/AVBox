package com.github.tvbox.osc.ui.page

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.ui.activity.ConfigManageActivity
import com.github.tvbox.osc.ui.components.AVBoxBottomSheet
import com.github.tvbox.osc.ui.components.CapsuleSegmentedButton
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.LocalSheetDismiss
import com.github.tvbox.osc.ui.components.SegmentOption
import com.github.tvbox.osc.ui.components.SegmentStyle
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsIconBadge
import com.github.tvbox.osc.ui.components.SettingsOptionRow
import com.github.tvbox.osc.ui.components.SettingsSwitchRow
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.ui.components.glassSurface
import com.github.tvbox.osc.ui.theme.cardContainer
import com.github.tvbox.osc.util.ApiLineSignal
import com.github.tvbox.osc.util.BootGuard
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.removeLocalCopy
import java.util.concurrent.Executors

private const val SubscribeSplit = "\t"

private data class SubscribeSource(val name: String, val url: String)

/**
 * 待二次确认的切源请求。带 `vod` 是必需的:列表在 AnimatedContent 里渲染,过渡期内外两份内容
 * 同时在组合中,读外层 `isVod` 会把正在退场的那份按错的模式切源。
 */
private data class PendingSwitch(val item: SubscribeSource, val vod: Boolean)

private enum class ConfigMode { Vod, Live }

private fun subscribeKeyOf(mode: ConfigMode): String = when (mode) {
    ConfigMode.Vod -> HawkConfig.SUBSCRIBE_LIST
    ConfigMode.Live -> HawkConfig.LIVE_SUBSCRIBE_LIST
}

private fun loadSubscribes(mode: ConfigMode): List<String> =
    KV.get(subscribeKeyOf(mode), ArrayList<String>()).toList()

private fun parseSubscribe(value: String): SubscribeSource {
    val index = value.indexOf(SubscribeSplit)
    return if (index < 0) {
        SubscribeSource(value.trim(), value.trim())
    } else {
        SubscribeSource(
            value.substring(0, index).trim(),
            value.substring(index + SubscribeSplit.length).trim(),
        )
    }
}

private fun saveSubscribe(mode: ConfigMode, name: String, url: String): List<String> {
    val value = (name.ifEmpty { url }) + SubscribeSplit + url
    val list = ArrayList(loadSubscribes(mode))
    val existIndex = list.indexOfFirst { parseSubscribe(it).url == url }
    if (existIndex >= 0) list[existIndex] = value else list.add(value)
    KV.put(subscribeKeyOf(mode), list)
    return list
}

private fun updateSubscribe(mode: ConfigMode, original: SubscribeSource, name: String, url: String): List<String> {
    val value = (name.ifEmpty { url }) + SubscribeSplit + url
    val list = ArrayList(loadSubscribes(mode))
    val index = list.indexOfFirst { parseSubscribe(it).url == original.url }
    if (index < 0) return list
    list[index] = value
    val dupIndex = list.indexOfFirst { it != value && parseSubscribe(it).url == url }
    if (dupIndex >= 0) list.removeAt(dupIndex)
    KV.put(subscribeKeyOf(mode), list)
    return list
}

private fun badgeText(name: String, url: String): String = when {
    name.isNotEmpty() -> name
    url.isEmpty() -> "未配置"
    else -> url.substringAfter("://").substringBefore('/').ifEmpty { url }
}

private fun applyVodSource(item: SubscribeSource): Boolean {
    val followLive = ApiConfig.isLiveFollowVod()
    val oldApi = KV.get(HawkConfig.API_URL, "")
    // 跟随态下"直播当前跟着谁":LIVE_API_URL 为空,实际生效地址就是点播地址
    val oldFollowTarget = KV.get(HawkConfig.LIVE_API_URL, "").ifEmpty { oldApi }
    HistoryHelper.setApiHistory(item.url)
    KV.put(HawkConfig.API_URL, item.url)
    if (followLive) {
        KV.put(HawkConfig.LIVE_API_URL, "")
        // 跟随态下直播源会跟着点播源一起变,旧直播仓列表随之失效(2026-09-21)。
        // ⚠️ 只在**直播确实被改动**时才清:否则"直播是独立仓源 + 点播换到别的源"会被误清,
        // 把用户的独立直播仓列表弄丢(直播设置「配置切换」组会退回配置历史)。
        if (item.url != oldFollowTarget) HistoryHelper.clearLiveApiLineList()
    }
    if (!HistoryHelper.isApiLineHistory(item.url)) HistoryHelper.clearApiLineList()
    if (oldApi == item.url) {
        ApiConfig.get().invalidateLiveConfig()
        return followLive
    }
    AppBootstrap.onApiUrlChanged()
    return followLive
}

private fun applyLiveSource(item: SubscribeSource) {
    HistoryHelper.setLiveApiHistory(item.url)
    KV.put(HawkConfig.LIVE_API_URL, item.url)
    // 多仓(2026-09-21):换到仓列表之外的地址即退出仓模式,否则「配置切换」会继续列上一仓的子源
    if (!HistoryHelper.isLiveApiLineHistory(item.url)) HistoryHelper.clearLiveApiLineList()
    ApiConfig.get().invalidateLiveConfig()
}

private fun applyLiveFollowVod() {
    KV.put(HawkConfig.LIVE_API_URL, "")
    HistoryHelper.clearLiveApiLineList()
    ApiConfig.get().invalidateLiveConfig()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConfigManageScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(ConfigMode.Vod) }
    var vodItems by remember { mutableStateOf(loadSubscribes(ConfigMode.Vod)) }
    var liveItems by remember { mutableStateOf(loadSubscribes(ConfigMode.Live)) }
    var activeUrl by remember { mutableStateOf(KV.get(HawkConfig.API_URL, "")) }
    var liveActiveUrl by remember { mutableStateOf(KV.get(HawkConfig.LIVE_API_URL, "")) }
    var liveFollow by remember { mutableStateOf(ApiConfig.isLiveFollowVod()) }
    var addDialogOpen by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<SubscribeSource?>(null) }
    var manageMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var repoSheetOpen by remember { mutableStateOf(false) }
    /**
     * 被看门狗自动停用过的源地址(黑名单)。只在首次组合读一次 —— 本页是独立 Activity、
     * 每次进入都是新实例;页内的增删(二次确认启用 / 删除订阅)都由本页自己改这份状态。
     */
    var disabledUrls by remember { mutableStateOf(BootGuard.disabledSources().toSet()) }
    /** 点到黑名单里的源时先挂起,由二次确认对话框决定是否放行 */
    var pendingSwitch by remember { mutableStateOf<PendingSwitch?>(null) }

    /**
     * 多仓的地址改写由异步 loadConfig 完成(仓地址 → 仓内首条子源),它不产生任何 Compose 状态
     * 变化 ⇒ 这几份"只在首次组合读一次"的当前态不会自更新,换仓入口与"使用中"标记要退出重进才正确。
     *
     * <p>刷新只走一条:改写点发的 [ApiLineSignal]。反推"加载什么时候完成"不可靠 —— 点播的完成态
     * 要等 jar 装载也跑完,那时改写早已结束;也不必再挂 ON_RESUME,因为本页存活期间唯一会改写仓
     * 关系的只有点播这一路(它必发信号),直播那路只在直播页拉配置时才改写,届时本页早已重建,
     * 首次组合读到的就是新值。只重读"当前态"而**不**重读订阅列表 —— 列表的增删改都同步写 KV,
     * 重读只会与 manageMode 的勾选集错位。
     */
    fun refreshActiveSnapshot() {
        activeUrl = KV.get(HawkConfig.API_URL, "")
        liveActiveUrl = KV.get(HawkConfig.LIVE_API_URL, "")
        liveFollow = ApiConfig.isLiveFollowVod()
    }

    val apiLineVersion by ApiLineSignal.version.collectAsState()
    LaunchedEffect(apiLineVersion) { refreshActiveSnapshot() }

    val isVod = mode == ConfigMode.Vod
    val currentItems = if (isVod) vodItems else liveItems

    LaunchedEffect(selected, currentItems) {
        if (manageMode && selected.isEmpty()) manageMode = false
    }

    LaunchedEffect(mode) {
        manageMode = false
        selected = emptySet()
        editTarget = null
        // 换仓 sheet 也关掉:它列的是"当前模式"那份仓列表,切模式后台面下的列表已经换了,
        // 留着会出现"点的是直播的子源、实际按点播语义切"的错配(分段按钮在遮罩之下点不到,
        // 但系统返回键/手势能先关 sheet,防的是这一类时序)
        repoSheetOpen = false
    }

    fun exitManageMode() {
        manageMode = false
        selected = emptySet()
        editTarget = null
    }

    BackHandler(enabled = manageMode) { exitManageMode() }

    /**
     * 这一条源是不是"正在使用"。
     *
     * <p>多仓生效后 {@code API_URL} 已被改写成仓里第一条子源的地址,订阅列表里那条仓地址匹配不上,
     * 所以还要认"它正是当前仓的来源地址"(否则切到仓之后重进页面,所有源都显示未使用)。
     */
    fun isInUse(url: String): Boolean =
        if (isVod) {
            url == activeUrl || HistoryHelper.isApiLineSourceOf(url, activeUrl)
        } else {
            (!liveFollow && url == liveActiveUrl) ||
                (!liveFollow && HistoryHelper.isLiveApiLineSourceOf(url, liveActiveUrl))
        }

    /** 该地址在点播/直播任一侧仍在生效(激活源或仓来源)—— 只用于挡副本清理,不放宽上面的删除保护 */
    fun activeInEitherMode(url: String): Boolean {
        val vodApi = KV.get(HawkConfig.API_URL, "")
        val liveApi = KV.get(HawkConfig.LIVE_API_URL, "")
        return url == vodApi || url == liveApi ||
            HistoryHelper.isApiLineSourceOf(url, vodApi) ||
            HistoryHelper.isLiveApiLineSourceOf(url, liveApi)
    }

    /** 任一模式的订阅列表里还留着该地址(同地址允许跨模式重复添加)—— 副本同样不能删 */
    fun referencedBySubscribes(url: String): Boolean =
        loadSubscribes(ConfigMode.Vod).any { parseSubscribe(it).url == url } ||
            loadSubscribes(ConfigMode.Live).any { parseSubscribe(it).url == url }

    /** 多仓的子源条目里还留着该地址 —— 仓的多个子源只有当前生效那个会被上面查到,其余必须在这里挡 */
    fun referencedByRepo(url: String): Boolean =
        (HistoryHelper.getApiLines().orEmpty() + HistoryHelper.getLiveApiLines().orEmpty())
            .any { HistoryHelper.getApiLineUrl(it) == url }

    fun switchToVod(item: SubscribeSource) {
        if (activeUrl == item.url) return
        val followLive = applyVodSource(item)
        activeUrl = item.url
        if (followLive) {
            liveActiveUrl = ""
            liveFollow = true
        }
        Toast.makeText(context, "已切换到:" + item.name, Toast.LENGTH_SHORT).show()
    }

    fun switchToLive(item: SubscribeSource) {
        if (!liveFollow && liveActiveUrl == item.url) return
        applyLiveSource(item)
        liveActiveUrl = item.url
        liveFollow = false
        Toast.makeText(context, "已切换到:" + item.name, Toast.LENGTH_SHORT).show()
    }

    /**
     * 切源统一入口:黑名单里的源**不当场切** —— 它上次就是在这个源上把应用崩掉的,
     * 直接切等于再崩一次,所以先弹二次确认(用户可能知道远端已经修好了)。
     */
    fun requestSwitch(item: SubscribeSource, vod: Boolean) {
        if (item.url in disabledUrls) {
            pendingSwitch = PendingSwitch(item, vod)
        } else if (vod) {
            switchToVod(item)
        } else {
            switchToLive(item)
        }
    }

    /** 二次确认"仍要启用":移出黑名单再切;真坏的话下次启动会重新记入 */
    fun enableAndSwitch() {
        val pending = pendingSwitch ?: return
        pendingSwitch = null
        BootGuard.enableSource(pending.item.url)
        disabledUrls = disabledUrls - pending.item.url
        if (pending.vod) switchToVod(pending.item) else switchToLive(pending.item)
    }

    // ---------- 换仓(2026-09-21) ----------
    // 多仓生效后启动地址被改写成仓里某个子源,订阅卡与"使用中"都不再指向用户填的仓地址,
    // 故需要独立入口:右上角图标 → bottom sheet。列表取与「配置切换」同一份数据,不另建状态。

    /** 当前源是否来自多仓 —— 不是仓源就没有可换的子源,入口整体隐藏 */
    val canSwitchRepo = if (isVod) {
        HistoryHelper.isApiLineUrl(activeUrl)
    } else {
        ApiConfig.get().isLiveApiLineMode() && HistoryHelper.isLiveApiLineUrl(liveActiveUrl)
    }

    /** 仓里的子源条目("名字\t链接") */
    val repoEntries = if (isVod) HistoryHelper.getApiLines() else HistoryHelper.getLiveApiLines()

    /** 当前生效的子源地址:换仓列表据此打选中标记 */
    val repoActiveUrl = if (isVod) activeUrl else liveActiveUrl

    fun followLiveNow() {
        applyLiveFollowVod()
        liveActiveUrl = ""
        liveFollow = true
        Toast.makeText(context, "直播已跟随点播源", Toast.LENGTH_SHORT).show()
    }

    fun deleteSelected() {
        val target = selected.filterNot { isInUse(parseSubscribe(it).url) }
        if (target.size != selected.size) {
            Toast.makeText(context, "正在使用的源不能删除", Toast.LENGTH_SHORT).show()
        }
        val remaining = currentItems.filterNot { it in target }
        KV.put(subscribeKeyOf(mode), ArrayList(remaining))
        // 源都删了,就别再留着它的"崩过"记录 —— 否则名单里堆的是用户已经不要的地址
        val removedUrls = target.map { parseSubscribe(it).url }
        BootGuard.forgetSources(removedUrls)
        disabledUrls = disabledUrls - removedUrls
        // 副本清理要跨模式判"仍在用":点播页删除时,同一地址可能正被直播侧当激活源/仓来源,或被另一模式的订阅/仓子源引用
        val copyUrls = removedUrls.filterNot {
            activeInEitherMode(it) || referencedBySubscribes(it) || referencedByRepo(it)
        }
        if (copyUrls.isNotEmpty()) {
            val executor = Executors.newSingleThreadExecutor()
            executor.execute { copyUrls.forEach { removeLocalCopy(it) } }
            executor.shutdown()
        }
        if (isVod) {
            vodItems = remaining
            if (remaining.isEmpty()) {
                ApiConfig.get().clearVodConfig()
                activeUrl = ""
                AppBootstrap.retry()
            }
        } else {
            liveItems = remaining
            if (remaining.isEmpty()) {
                applyLiveFollowVod()
                liveActiveUrl = ""
                liveFollow = true
            }
        }
        selected = emptySet()
    }

    fun commitAdd(name: String, url: String) {
        val newItems = saveSubscribe(mode, name, url)
        if (isVod) vodItems = newItems else liveItems = newItems
        addDialogOpen = false
        if (newItems.size == 1) {
            val item = parseSubscribe(newItems.first())
            if (isVod) switchToVod(item) else switchToLive(item)
        }
    }

    fun commitEdit(target: SubscribeSource, name: String, url: String) {
        if (url.isEmpty()) return
        val newValue = (name.ifEmpty { url }) + SubscribeSplit + url
        val oldValue = selected.firstOrNull { parseSubscribe(it).url == target.url }
        val updated = updateSubscribe(mode, target, name, url)
        if (isVod) vodItems = updated else liveItems = updated
        if (oldValue != null) selected = selected - oldValue + newValue
        editTarget = null
        val item = parseSubscribe(newValue)
        if (isVod) {
            if (target.url == activeUrl && url != activeUrl) switchToVod(item)
        } else if (!liveFollow && target.url == liveActiveUrl && url != liveActiveUrl) {
            switchToLive(item)
        }
    }

    val vodBadge = remember(vodItems, activeUrl) {
        badgeText(vodItems.firstOrNull { parseSubscribe(it).url == activeUrl }?.let { parseSubscribe(it).name }.orEmpty(), activeUrl)
    }
    val liveBadge = remember(liveItems, liveActiveUrl, liveFollow) {
        if (liveFollow) {
            ApiConfig.LIVE_FOLLOW_ITEM_NAME
        } else {
            badgeText(liveItems.firstOrNull { parseSubscribe(it).url == liveActiveUrl }?.let { parseSubscribe(it).name }.orEmpty(), liveActiveUrl)
        }
    }

    AppTopBarScaffold(
        collapseEnabled = false,
        titleContent = {
            Text(
                text = "配置管理",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            TopBarActionBox(
                R.drawable.ic_arrow_left,
                "返回",
                onClick = { if (manageMode) exitManageMode() else onNavigateBack() },
            )
        },
        actions = {
            AnimatedContent(
                targetState = manageMode && currentItems.isNotEmpty(),
                transitionSpec = {
                    (fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMedium)) +
                        scaleIn(initialScale = 0.8f, animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                        .togetherWith(fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                },
                label = "configTopAction",
            ) { managing ->
                if (managing) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ManageActionIcon(
                            iconRes = R.drawable.ic_edit,
                            contentDescription = "编辑",
                            enabled = selected.size == 1,
                            onClick = { editTarget = selected.firstOrNull()?.let { parseSubscribe(it) } },
                        )
                        ManageActionIcon(
                            iconRes = R.drawable.ic_delete,
                            contentDescription = "删除",
                            enabled = selected.isNotEmpty(),
                            onClick = { deleteSelected() },
                        )
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 「换仓」入口(2026-09-21):仅在**当前源来自多仓**时出现 ——
                        // 不是仓源时没有可换的子源,按钮出现只会让人白点一次。
                        if (canSwitchRepo) {
                            TopBarActionBox(
                                iconRes = R.drawable.ic_switch_repo,
                                contentDescription = "换仓",
                                onClick = { repoSheetOpen = true },
                            )
                        }
                        TopBarActionBox(
                            iconRes = R.drawable.ic_subscribe_add,
                            contentDescription = if (isVod) "添加订阅" else "添加直播源",
                            onClick = { addDialogOpen = true },
                        )
                    }
                }
            }
        },
    ) { topPad, _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            CapsuleSegmentedButton(
                options = listOf(
                    SegmentOption(label = "点播", value = ConfigMode.Vod, badge = vodBadge),
                    SegmentOption(label = "直播", value = ConfigMode.Live, badge = liveBadge),
                ),
                selectedValue = mode,
                onOptionSelected = { mode = it },
                style = SegmentStyle.Track,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = topPad + 8.dp),
            )
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    val toRight = targetState == ConfigMode.Live
                    (
                        slideInHorizontally(spring(stiffness = Spring.StiffnessMedium)) { full ->
                            if (toRight) full / 4 else -full / 4
                        } + fadeIn(spring(stiffness = Spring.StiffnessMedium))
                        ).togetherWith(
                        slideOutHorizontally(spring(stiffness = Spring.StiffnessMedium)) { full ->
                            if (toRight) -full / 4 else full / 4
                        } + fadeOut(spring(stiffness = Spring.StiffnessMedium))
                    )
                },
                label = "configSegment",
            ) { m ->
                val mIsVod = m == ConfigMode.Vod
                val mItems = if (mIsVod) vodItems else liveItems
                if (mIsVod && mItems.isEmpty()) {
                    LoadStateBox(
                        state = LoadState.Empty,
                        emptyText = "暂无订阅",
                        errorText = "",
                        retryText = "",
                        emptyIconRes = R.drawable.ic_empty_record,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    val mOrdered = remember(mItems, activeUrl, liveActiveUrl, liveFollow, mIsVod) {
                        mItems.sortedByDescending {
                            val url = parseSubscribe(it).url
                            if (mIsVod) url == activeUrl else !liveFollow && url == liveActiveUrl
                        }
                    }
                    LazyColumn(
                        state = rememberLazyListState(),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (!mIsVod) {
                            item(key = "Live#follow") {
                                FollowVodCard(
                                    checked = liveFollow,
                                    subtitle = if (activeUrl.isEmpty()) "未配置点播源" else "当前点播源:$vodBadge",
                                    onFollow = { followLiveNow() },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                        items(mOrdered, key = { "${m.name}#$it" }) { value ->
                            val item = parseSubscribe(value)
                            // 2026-09-21 多仓:与上面 isInUse 同一套判定 —— 之前只比地址本身,
                            // 点了带"使用中"标记的仓卡会因为 activeUrl(仓地址)与 API_URL(仓里首条)
                            // 不等而误判成"未使用",再点一次又白跑一遍完整换源流程
                            val inUse = if (mIsVod) {
                                item.url == activeUrl || HistoryHelper.isApiLineSourceOf(item.url, activeUrl)
                            } else {
                                !liveFollow && (
                                    item.url == liveActiveUrl ||
                                        HistoryHelper.isLiveApiLineSourceOf(item.url, liveActiveUrl)
                                    )
                            }
                            SubscribeCard(
                                modifier = Modifier.animateItem(),
                                item = item,
                                active = inUse,
                                disabled = item.url in disabledUrls,
                                manageMode = manageMode,
                                selected = value in selected,
                                onClick = {
                                    if (manageMode) {
                                        selected = if (value in selected) selected - value else selected + value
                                    } else {
                                        requestSwitch(item, mIsVod)
                                    }
                                },
                                onLongClick = {
                                    manageMode = true
                                    selected = setOf(value)
                                },
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        requestSwitch(item, mIsVod)
                                    } else if (!mIsVod) {
                                        followLiveNow()
                                    }
                                },
                            )
                        }
                        if (!mIsVod && mItems.isEmpty()) {
                            item(key = "Live#empty") {
                                LoadStateBox(
                                    state = LoadState.Empty,
                                    emptyText = "暂无直播源",
                                    errorText = "",
                                    retryText = "",
                                    emptyIconRes = R.drawable.ic_empty_record,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val editing = editTarget
    if (addDialogOpen || editing != null) {
        AddSubscribeDialog(
            title = if (editing != null) {
                if (isVod) "编辑订阅" else "编辑直播源"
            } else {
                if (isVod) "添加订阅" else "添加直播源"
            },
            urlSupportingText = if (isVod) "" else "支持配置 JSON / m3u / txt 直播源",
            initialName = editing?.name.orEmpty(),
            initialUrl = editing?.url.orEmpty(),
            onDismiss = {
                addDialogOpen = false
                editTarget = null
            },
            onSave = { name, url ->
                if (editing != null) commitEdit(editing, name, url) else commitAdd(name, url)
            },
            onPickFile = { onPicked ->
                (context as? ConfigManageActivity)?.launchLocalConfig { api -> onPicked(api) }
            },
        )
    }

    val pending = pendingSwitch
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text("该源已被自动停用") },
            text = {
                Text(
                    "「" + pending.item.name + "」此前导致应用崩溃，已被自动停用。\n\n" +
                        "若该源已经修好，可以重新启用；否则切换后很可能再次闪退。",
                )
            },
            confirmButton = {
                TextButton(onClick = { enableAndSwitch() }) { Text("仍要启用") }
            },
            dismissButton = {
                TextButton(onClick = { pendingSwitch = null }) { Text("取消") }
            },
        )
    }

    if (repoSheetOpen) {
        RepoSwitchSheet(
            entries = repoEntries,
            activeUrl = repoActiveUrl,
            disabledUrls = disabledUrls,
            onDismiss = { repoSheetOpen = false },
            onSelect = { url ->
                val name = HistoryHelper.getApiLineName(
                    repoEntries.firstOrNull { HistoryHelper.getApiLineUrl(it) == url }.orEmpty(),
                )
                // 与在订阅列表里点同一条源等价 —— switchToVod 里已经处理了"是否落在仓里"的仓列表保留判定,
                // 所以换完仓后入口仍在。统一走 requestSwitch:仓里藏着的坏子源同样要过二次确认
                requestSwitch(SubscribeSource(name, url), isVod)
            },
        )
    }
}

/**
 * 「换仓」bottom sheet:列出当前仓里的全部子源,点一条即切换。
 *
 * <p>样式同 `AVBoxOptionSheet`,但每条多带一行地址 —— 仓里常有同名子源,只给名字分不清。
 * 被看门狗停用过的子源额外打「已禁用」标记(坏子源通常就藏在仓里,不标出来用户只会觉得"点了没反应")。
 */
@Composable
private fun RepoSwitchSheet(
    entries: List<String>,
    activeUrl: String,
    disabledUrls: Set<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val dismissAnimated = LocalSheetDismiss.current
    // 防连点(与 AVBoxOptionSheet 同款)
    var accepted by remember { mutableStateOf(false) }
    AVBoxBottomSheet(
        onDismissRequest = onDismiss,
        title = "换仓",
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        SettingsGroup(
            title = null,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            entries.forEachIndexed { index, entry ->
                val url = HistoryHelper.getApiLineUrl(entry)
                SettingsCard(
                    position = when {
                        entries.size <= 1 -> SettingsCardPosition.SINGLE
                        index == 0 -> SettingsCardPosition.FIRST
                        index == entries.size - 1 -> SettingsCardPosition.LAST
                        else -> SettingsCardPosition.MIDDLE
                    },
                    color = MaterialTheme.colorScheme.surfaceBright,
                ) {
                    SettingsOptionRow(
                        title = HistoryHelper.getApiLineName(entry),
                        selected = url == activeUrl,
                        onClick = onClick@{
                            // 先吃掉点击并关面板:点"当前已选中"那条时切换逻辑会直接返回,
                            // 把关闭放进守卫里会让面板卡住关不掉。
                            if (accepted) return@onClick
                            accepted = true
                            if (url.isNotEmpty() && url != activeUrl) onSelect(url)
                            // 只走动画关闭(它播完才回调 onDismiss);这里再置 repoSheetOpen=false 会把面板先拆掉
                            dismissAnimated()
                        },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (url in disabledUrls) {
                                    DisabledSourceTag()
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    text = url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 180.dp),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FollowVodCard(
    checked: Boolean,
    subtitle: String,
    onFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsCard(position = SettingsCardPosition.SINGLE, modifier = modifier) {
        SettingsSwitchRow(
            title = "跟随点播源",
            subtitle = subtitle,
            checked = checked,
            onCheckedChange = { next -> if (next) onFollow() },
        )
    }
}

@Composable
private fun SubscribeCard(
    item: SubscribeSource,
    active: Boolean,
    disabled: Boolean,
    manageMode: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.cardContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsIconBadge(iconRes = R.drawable.ic_subscribe_source)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (disabled) {
                        Spacer(Modifier.width(8.dp))
                        DisabledSourceTag()
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            AnimatedContent(
                targetState = manageMode,
                transitionSpec = {
                    (fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMedium)) +
                        scaleIn(initialScale = 0.7f, animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                        .togetherWith(fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                },
                label = "configRowControl",
            ) { managing ->
                if (managing) {
                    Checkbox(checked = selected, onCheckedChange = { onClick() })
                } else {
                    Switch(checked = active, onCheckedChange = onCheckedChange)
                }
            }
        }
    }
}

/** 被看门狗停用过的源标记:红底小圆角,贴在源名(或换仓条目的地址)旁边 */
@Composable
private fun DisabledSourceTag() {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text = "已禁用",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun AddSubscribeDialog(
    title: String,
    urlSupportingText: String,
    initialName: String,
    initialUrl: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onPickFile: (onPicked: (String) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    val urlHint: (@Composable () -> Unit)? = if (urlSupportingText.isEmpty()) {
        null
    } else {
        {
            Text(
                text = urlSupportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .glassSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
                        .clickable { onPickFile { picked -> url = picked } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_file_choose),
                        contentDescription = "从本地选择",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("名字") },
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("链接") },
                    supportingText = urlHint,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), url.trim()) },
                enabled = url.isNotBlank(),
            ) { Text("保存") }
        },
    )
}
