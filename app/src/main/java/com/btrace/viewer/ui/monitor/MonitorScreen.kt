package com.btrace.viewer.ui.monitor

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.gestures.detectTapGestures
import com.btrace.viewer.data.EventFilter
import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.model.Direction
import com.btrace.viewer.model.StackFrame
import com.btrace.viewer.model.StackQuality
import com.btrace.viewer.model.StackTrace
import com.btrace.viewer.model.isOneway
import com.btrace.viewer.parser.CoverageBucket
import com.btrace.viewer.parser.CoverageSnapshot
import com.btrace.viewer.parser.DecodedArgument
import com.btrace.viewer.parser.ReplyParser
import com.btrace.viewer.parser.decoders.Confidence
import com.btrace.viewer.parser.decoders.DecodeSource
import com.btrace.viewer.ui.apps.AppsScreen
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(
    viewModel: MonitorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // IDLE 态让应用选择 UI 直接内联进来:上游 AppsViewModel 完成 daemon 启动 / 握手后,
    // 通过 onMonitoringStarted 回调转给 MonitorViewModel.startMonitoring(),把状态翻到
    // MONITORING,重组后自然落到下面的事件流 UI。整条流程不经过 NavController,
    // 用户也没有"从监控态回应用页"的合法入口。
    if (uiState.monitoringState == MonitoringState.IDLE) {
        // AppsViewModel.startMonitoring 内部走 MonitoringServiceConnector,
        // controller.state 转出 IDLE 后这里 recompose 自动切到下面的事件流 UI,
        // 无需 callback。
        AppsScreen()
        return
    }

    val events by viewModel.displayEvents.collectAsState()
    val eventCount by viewModel.eventCount.collectAsState()
    val eventRate by viewModel.eventRate.collectAsState()
    val coverage by viewModel.coverage.collectAsState()
    val currentFilter by viewModel.currentFilter.collectAsState()
    val selectedDetail by viewModel.selectedEvent.collectAsState()
    val context = LocalContext.current

    // 显示错误消息
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearErrorMessage()
        }
    }

    // 过滤器对话框:直接读 currentFilter,勾选立即生效,无需"应用"按钮。
    if (uiState.showFilterDialog) {
        FilterDialog(
            currentFilter = currentFilter,
            coverage = coverage,
            onInterfaceFilterChange = viewModel::updateInterfaceFilter,
            onMethodFilterChange = viewModel::updateMethodFilter,
            onStackModuleFilterChange = viewModel::updateStackModuleFilter,
            onBucketToggle = viewModel::toggleBucketFilter,
            onClear = viewModel::clearFilter,
            onDismiss = viewModel::hideFilterDialog
        )
    }

    // Binder 事件详情:点击列表项弹出底部 sheet
    selectedDetail?.let { detail ->
        EventDetailSheet(
            detail = detail,
            onDismiss = viewModel::dismissDetail
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when (uiState.monitoringState) {
                                    MonitoringState.MONITORING -> "监控中"
                                    MonitoringState.PAUSED -> "已暂停"
                                    MonitoringState.STOPPING -> "停止中"
                                    MonitoringState.IDLE -> "未监控"
                                }
                            )
                            if (uiState.targetAppName.isNotEmpty()) {
                                Text(
                                    text = ": ${uiState.targetAppName}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    // 过滤按钮:有活跃过滤条件时用 primary 色高亮,提示用户当前列表被过滤。
                    val filterActive = !currentFilter.isEmpty()
                    IconButton(onClick = viewModel::showFilterDialog) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = "过滤",
                            tint = if (filterActive)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    
                    // 暂停/恢复按钮
                    when (uiState.monitoringState) {
                        MonitoringState.MONITORING -> {
                            IconButton(onClick = viewModel::pauseMonitoring) {
                                Icon(
                                    imageVector = Icons.Default.Pause,
                                    contentDescription = "暂停"
                                )
                            }
                        }
                        MonitoringState.PAUSED -> {
                            IconButton(onClick = viewModel::resumeMonitoring) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "恢复"
                                )
                            }
                        }
                        else -> {}
                    }
                    
                    // 停止按钮
                    if (uiState.monitoringState != MonitoringState.IDLE) {
                        IconButton(onClick = viewModel::stopMonitoring) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "停止",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 统计信息栏
            StatsBar(
                eventCount = eventCount,
                eventRate = eventRate
            )
            
            // 事件列表
            if (events.isEmpty()) {
                EmptyState(
                    monitoringState = uiState.monitoringState
                )
            } else {
                // 底层数据按时间升序追加(events[0] 最旧, events.last 最新),LazyColumn 顺序渲染
                // → 最新事件在物理底部,与 logcat / Charles / Wireshark 等业界工具一致,
                // 也避免每次更新都对 5000 个元素做 reversed() 拷贝(P0-2 优化)。
                //
                // 自动跟随:用户停留在底部时,新事件到达自动滚到底;一旦用户上滑离开底部,
                // 暂停跟随,直到用户主动滑回底部。
                val listState = rememberLazyListState()

                var followTail by remember { mutableStateOf(true) }
                var lastEventCount by remember { mutableStateOf(0) }

                // 用户每次手动滑动结束后,根据"最后可见 item 是否就是列表末尾"判定是否仍在底部。
                // 程序触发的 animateScrollToItem 也会经过 isScrollInProgress=true→false,
                // 但落点恰好在底部,followTail 会被正确地保留/恢复为 true,不会破坏状态。
                LaunchedEffect(listState) {
                    snapshotFlow { listState.isScrollInProgress }
                        .collect { scrolling ->
                            if (!scrolling) {
                                val info = listState.layoutInfo
                                followTail = if (info.totalItemsCount == 0) true
                                else (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >=
                                    info.totalItemsCount - 1
                            }
                        }
                }

                // events 增长时,若仍跟随尾部则滚到底。只在 size 真的增大时才跟随,
                // 避免清空/筛选导致 size 缩小时也强制滚动。
                LaunchedEffect(events.size) {
                    val current = events.size
                    // 跟随尾部只在实时模式(无过滤);检索模式列表是 DB 分页(最新在顶),不自动滚。
                    if (current > lastEventCount && followTail && current > 0 && currentFilter.isEmpty()) {
                        listState.animateScrollToItem(current) // 末尾的 Spacer 索引,确保最后一条事件贴底
                    }
                    lastEventCount = current
                }

                // 检索模式(有过滤):列表是全量 DB 分页,滑到接近底部时加载下一页更旧的匹配。
                if (!currentFilter.isEmpty()) {
                    LaunchedEffect(listState, events.size) {
                        snapshotFlow {
                            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        }.collect { lastVisible ->
                            if (lastVisible >= events.size - 5) viewModel.loadMore()
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = events,
                            key = { it.id }
                        ) { event ->
                            EventCard(
                                event = event,
                                onClick = { viewModel.selectEvent(event.id) }
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    // 列表条目 ≥5000 时靠手指滑很难回头 —— 右侧滑动条既展示当前位置,
                    // 又支持拖拽跳转。reverseLayout=false 后顺序与物理布局一致:
                    // thumb 在顶 = 看最旧,thumb 在底 = 看最新。
                    VerticalScrollbar(
                        listState = listState,
                        reverseLayout = false,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsBar(
    eventCount: Int,
    eventRate: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "事件: $eventCount",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "速率: $eventRate/s",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyState(
    monitoringState: MonitoringState
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = when (monitoringState) {
                    MonitoringState.IDLE -> "请从「应用」页选择目标开始监控"
                    MonitoringState.MONITORING -> "等待 Binder 事件..."
                    MonitoringState.PAUSED -> "监控已暂停"
                    MonitoringState.STOPPING -> "正在停止..."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun EventCard(
    event: BinderEvent,
    onClick: () -> Unit
) {
    // 长按卡片把全限定接口名复制到剪贴板,方便贴到 IDE / grep 查源码。
    // 用 LocalClipboardManager 保持纯 Compose,不引入 platform ClipboardManager。
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    clipboardManager.setText(AnnotatedString(event.interfaceName))
                    Toast.makeText(
                        context,
                        "已复制接口名: ${event.interfaceName}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // 时间戳 + 方向/模式/类型/置信度 badge(spec § 6.5)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = event.formattedTime,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                DirectionBadge(direction = event.direction, isReplyFallback = event.isReply)
                Spacer(modifier = Modifier.width(4.dp))
                ModeBadge(flags = event.flags)
                // TypeBadge / ConfidenceMarker 在条件不满足时返回空 Composable,
                // 不需要在调用端二次判断;留 4dp 间距避免与左侧 badge 视觉粘连。
                Spacer(modifier = Modifier.width(4.dp))
                TypeBadge(decodeSource = event.decodeSource)
                Spacer(modifier = Modifier.width(4.dp))
                ConfidenceMarker(confidence = event.confidence)
            }

            Divider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // 调用方
            EventInfoRow(
                label = "调用方",
                value = event.callerPackage
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 被调用方:展示全限定接口名。
            // maxLines=2 给长类名(`android.content.pm.IPackageManager`)留余量。
            // 兜底场景(DecodeSource.TARGET_REF / 解不出 InterfaceToken 的 `target@0xN`)
            // 时,如 toPackage 反查到了,追加 → toPackage 让卡片仍可读;其余场景
            // (AIDL token / HIDL / REPLY)保持 interfaceName 原样,不污染。
            val calleeDisplay = if (event.decodeSource == DecodeSource.TARGET_REF &&
                !event.toPackage.isNullOrEmpty()
            ) {
                "${event.interfaceName} → ${event.toPackage}"
            } else {
                event.interfaceName
            }
            EventInfoRow(
                label = "被调用",
                value = calleeDisplay,
                maxLines = 2
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 方法名
            EventInfoRow(
                label = "方法名",
                value = event.methodName,
                valueColor = MaterialTheme.colorScheme.tertiary
            )

            // 参数摘要:有精确解码走 parsedArgs 前 2 条;没有但嗅到启发式签名则什么都不显示
            // (签名已内联进 methodName)。spec § 11.1。
            val previewArgs = event.parsedArgs.take(2)
            if (previewArgs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                ArgumentSummaryLine(previewArgs, more = event.parsedArgs.size - previewArgs.size)
            }
        }
    }
}

/**
 * 详情页"方向"行的纯文本标签。和 [DirectionBadge] 共用一套语义。
 */
private fun directionLabel(direction: Direction, isReplyFallback: Boolean): String {
    val resolved = if (direction == Direction.UNKNOWN) {
        if (isReplyFallback) Direction.INCOMING_REPLY else Direction.OUTGOING_REQUEST
    } else direction
    return when (resolved) {
        Direction.OUTGOING_REQUEST -> "→ 请求 (出向)"
        Direction.INCOMING_REQUEST -> "← 请求 (入向)"
        Direction.OUTGOING_REPLY -> "→ 回复 (出向)"
        Direction.INCOMING_REPLY -> "← 回复 (入向)"
        Direction.UNKNOWN -> "未知"
    }
}

/**
 * 方向 badge(spec § 6.5):4 个具名方向 + UNKNOWN 兜底。
 *   → 请求 / ← 请求 / → 回复 / ← 回复 / ·
 * 箭头视角统一为"目标 App",→ 表示目标 App 主动发出,← 表示目标 App 被动接收。
 *
 * UNKNOWN 时(targetUid 未灌入)仍按 isReplyFallback 退化为旧的"请求/回复"两态显示,
 * 避免用户在监控状态下看到中性圆点而误以为元数据缺失。
 */
@Composable
private fun DirectionBadge(direction: Direction, isReplyFallback: Boolean = false) {
    val resolved = if (direction == Direction.UNKNOWN) {
        if (isReplyFallback) Direction.INCOMING_REPLY else Direction.OUTGOING_REQUEST
    } else direction

    val (text, container, onContainer) = when (resolved) {
        Direction.OUTGOING_REQUEST -> Triple(
            "→ 请求",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        Direction.INCOMING_REQUEST -> Triple(
            "← 请求",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        Direction.OUTGOING_REPLY -> Triple(
            "→ 回复",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        Direction.INCOMING_REPLY -> Triple(
            "← 回复",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        // 进入这里只在 isReplyFallback 也无法决定时(理论上不会发生,留兜底)
        Direction.UNKNOWN -> Triple(
            "·",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Box(
        modifier = Modifier
            .background(color = container, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = onContainer,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 模式 badge(spec § 6.5):flags 最低位 = oneway。
 *   ⚡ oneway / 🔁 twoway
 * 与 [DirectionBadge] 用同一套 padding/字号/圆角,横排在时间戳右侧。
 * 无三态,任何 BinderEvent 都能给出明确答案,所以不需要 nullable / 隐藏路径。
 */
@Composable
private fun ModeBadge(flags: Int) {
    val oneway = isOneway(flags)
    val text = if (oneway) "⚡ oneway" else "🔁 twoway"
    val (container, onContainer) = if (oneway) {
        // oneway 用 secondary 系(更冷),twoway 用 surfaceVariant(中性)。
        // 选这个组合是因为列表里大多数事件是 twoway,放中性色不抢视觉;
        // oneway 偏少且语义"火信号 fire-and-forget",冷色高亮一下提醒用户。
        MaterialTheme.colorScheme.secondaryContainer to
            MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    BadgeBox(text = text, container = container, onContainer = onContainer)
}

/**
 * 类型 badge(spec § 6.5):
 *   - SPECIAL_CODE → ⚙ 协议
 *   - HIDL_DESCRIPTOR → 🔧 HIDL
 *   - 其他档位(AIDL_x / REPLY / RAW_ASCII / TARGET_REF / null)→ 不显示
 *
 * vendor 桶要靠 [com.btrace.viewer.model.BinderDev.VNDBINDER] 区分,但当前 BPF 还没
 * 把 binder_dev 字段灌成 VND 真值(永远是 0/UNKNOWN),本期跳过 vendor 显示,
 * 等 daemon 落值后再补一档。
 */
@Composable
private fun TypeBadge(decodeSource: DecodeSource?) {
    val text = when (decodeSource) {
        DecodeSource.SPECIAL_CODE -> "⚙ 协议"
        DecodeSource.HIDL_DESCRIPTOR -> "🔧 HIDL"
        else -> return  // 不显示
    }
    BadgeBox(
        text = text,
        container = MaterialTheme.colorScheme.tertiaryContainer,
        onContainer = MaterialTheme.colorScheme.onTertiaryContainer
    )
}

/**
 * 置信度小角标(spec § 6.5):confidence != HIGH 时挂一个浅灰 `?`,
 * HIGH / null 都不显示。null 走"不显示"是因为 EventRepository 在 decodePipeline
 * 命中后必填 confidence,只有 mock / 旧测试事件才会留 null,这种场景不该误报。
 *
 * 浅灰底 + 极小字号,提示用户"结果可能不精确"但不抢视线。
 */
@Composable
private fun ConfidenceMarker(confidence: Confidence?) {
    if (confidence == null || confidence == Confidence.HIGH) return
    BadgeBox(
        text = "?",
        container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        onContainer = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    )
}

/**
 * 通用 badge 容器,统一 [DirectionBadge] / [ModeBadge] / [TypeBadge] / [ConfidenceMarker]
 * 的 padding、圆角、字号,避免 4 个 badge 横排时尺寸/基线不一致。
 */
@Composable
private fun BadgeBox(text: String, container: Color, onContainer: Color) {
    Box(
        modifier = Modifier
            .background(color = container, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = onContainer,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 列表卡片里的参数摘要行,只展示前 N 个 parsedArgs。
 * 格式:`int=10086, String="com.foo.app" (+2)`。
 */
@Composable
private fun ArgumentSummaryLine(args: List<DecodedArgument>, more: Int) {
    val text = buildString {
        args.forEachIndexed { i, a ->
            if (i > 0) append(", ")
            append(a.declaredType).append('=')
            if (a.status == DecodedArgument.Status.SUCCESS) {
                append(a.displayValue)
            } else {
                append("⚠")
            }
        }
        if (more > 0) append(" (+$more)")
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "参数: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EventInfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    maxLines: Int = 1
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventDetailSheet(
    detail: MonitorViewModel.EventDetail,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val event = detail.event
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "Binder 事件详情",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            // 调用方 / 接口 / 方法 / 参数值 → 单击整行复制到剪贴板。
            // 这 4 类标签是用户最常需要复制到 IDE / grep / chat 的内容,长按选中再复制
            // 在手机上效率太低,直接 tap-to-copy 体验更顺。其它字段(时间/PID/Code/Flags
            // /Hex)保留 SelectionContainer 的"选中复制"路径,因为有时只想复制其中一段。
            val clipboardManager = LocalClipboardManager.current
            val context = LocalContext.current
            val copyToClipboard: (String, String) -> Unit = { label, value ->
                clipboardManager.setText(AnnotatedString(value))
                Toast.makeText(context, "已复制 $label", Toast.LENGTH_SHORT).show()
            }

            DetailRow("时间", event.formattedFullTime, mono = true)
            DetailRow("方向", directionLabel(event.direction, event.isReply))
            DetailRow("PID", event.pid.toString(), mono = true)
            DetailRow("UID", event.uid.toString(), mono = true)
            DetailRow("调用方", event.callerPackage,
                onClick = { copyToClipboard("调用方", event.callerPackage) })
            DetailRow("接口", event.interfaceName,
                onClick = { copyToClipboard("接口", event.interfaceName) })
            DetailRow("方法", event.methodName, valueColor = MaterialTheme.colorScheme.tertiary,
                onClick = { copyToClipboard("方法", event.methodName) })
            DetailRow("Code", event.code.toString(), mono = true)
            DetailRow("Flags", event.flagsHex, mono = true)
            DetailRow("Parcel 大小", "${event.rawParcel.size} bytes", mono = true)

            // spec § 6.2 v2 协议新字段。reply 帧或目标信息有意义时才展示。
            if (event.binderDev != com.btrace.viewer.model.BinderDev.UNKNOWN) {
                DetailRow("Binder Dev", event.binderDev.name, mono = true)
            }
            if (event.targetRef != 0L) {
                DetailRow("Target Ref", event.targetRefHex, mono = true)
            }
            if (event.toPid != 0) {
                DetailRow("→ ToPID", event.toPid.toString(), mono = true)
            }
            if (event.toUid != 0) {
                DetailRow("→ ToUID", event.toUid.toString(), mono = true)
            }
            // 目标进程的可读名:app uid 走包名,系统 uid 走助记名(system / audioserver…)。
            // 与 ToUID 同区,排在 ToUID 后面更直观。空时不显示。
            event.toPackage?.let { tp ->
                DetailRow("→ ToPackage", tp,
                    onClick = { copyToClipboard("ToPackage", tp) })
            }
            if (event.pairId != 0L) {
                DetailRow("PairId", event.pairId.toString(), mono = true)
            }

            // ─── 请求数据 ───
            // 数据源:event 自身是 request → 用 event;event 是 reply → 反向 lookup
            // 配对 request(detail.linkedRequest)。后者可能为 null:配对 request 已被
            // FIFO 淘汰 / 是 orphan reply / daemon 启动前已发起。
            Divider(modifier = Modifier.padding(vertical = 10.dp))
            Text(
                text = "请求数据",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            val requestEvent: BinderEvent? =
                if (event.isReply) detail.linkedRequest else event
            val requestHex: String =
                if (event.isReply) detail.linkedRequestHex.orEmpty() else detail.hexDump
            if (requestEvent == null) {
                Text(
                    text = "(配对 request 未捕获或已被 FIFO 淘汰)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            } else {
                ParsedArgsBlock(
                    parsedArgs = requestEvent.parsedArgs,
                    sniffedSignature = requestEvent.sniffedSignature,
                    copyToClipboard = copyToClipboard,
                )
                // request hex 默认展开:request 一般不含敏感返回值。
                ParcelHexRegion(
                    title = "Parcel 数据 (Hex)",
                    hex = requestHex,
                    byteCount = requestEvent.rawParcel.size,
                    defaultExpanded = true,
                    rememberKey = requestEvent.id,
                    copyLabel = "Hexdump",
                    collapsedButtonLabel = "展开 ${requestEvent.rawParcel.size} 字节 Parcel(默认折叠)",
                    copyToClipboard = copyToClipboard,
                )
            }

            // ─── 响应数据 ───
            // 显示条件:
            //  - event 是 reply:必显示(就是 event 自身)
            //  - event 是 twoway request 且有 pairId:显示 linkedReply,等待中也显示等待提示
            //  - oneway request:不显示(协议上无 reply)
            val showResponseSection = when {
                event.isReply -> true
                isOneway(event.flags) -> false
                event.pairId != 0L -> true
                else -> false
            }
            if (showResponseSection) {
                Divider(modifier = Modifier.padding(vertical = 10.dp))
                Text(
                    text = "响应数据",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                val responseEvent: BinderEvent? =
                    if (event.isReply) event else detail.linkedReply
                val responseHex: String =
                    if (event.isReply) detail.hexDump else detail.linkedReplyHex.orEmpty()
                if (responseEvent == null) {
                    Text(
                        text = "等待 reply…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                    Text(
                        text = "可能原因:reply 仍在路上 / daemon 启动前已发起 / 已被 FIFO 淘汰 / " +
                            "失败掉(BR_FAILED_REPLY 等)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                    )
                } else {
                    // 时延仅在「request 视角」展示(reply 视角时延总为 0,无意义)。
                    if (!event.isReply) {
                        val latencyMs = (responseEvent.timestamp - event.timestamp) / 1_000_000.0
                        val latencyColor = if (latencyMs < 0.0)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurface
                        DetailRow("时延", "%.1f ms".format(latencyMs), mono = true,
                            valueColor = latencyColor)
                        DetailRow("Reply 大小", "${responseEvent.rawParcel.size} bytes", mono = true)
                    }
                    // parsedReply 三选一(异常 > 返回值 > 原始 hex 摘要),都没有则给兜底文案。
                    ParsedReplyBlock(
                        parsedReply = responseEvent.parsedReply,
                        copyToClipboard = copyToClipboard,
                    )
                    // reply hex 默认折叠(spec § 8.2 隐私:可能含 token / cookie)。
                    ParcelHexRegion(
                        title = "Reply Parcel (Hex)",
                        hex = responseHex,
                        byteCount = responseEvent.rawParcel.size,
                        defaultExpanded = false,
                        rememberKey = responseEvent.id,
                        copyLabel = "Reply Hexdump",
                        collapsedButtonLabel = "展开 ${responseEvent.rawParcel.size} 字节 Reply Parcel(默认折叠)",
                        copyToClipboard = copyToClipboard,
                    )
                }
            }

            // ─── 调用栈 ───
            // spec 2026-05-03 § 4.5.1:全量永远开启,栈缺失即解析失败。
            Divider(modifier = Modifier.padding(vertical = 10.dp))
            StackTraceSection(stackTrace = event.stackTrace)
        }
    }
}

/**
 * 渲染 BinderEvent 的参数解析行(请求数据 section 用)。
 * 优先 parsedArgs,无精确解码时降级 sniffedSignature 指纹,都没有则空 placeholder。
 */
@Composable
private fun ParsedArgsBlock(
    parsedArgs: List<DecodedArgument>,
    sniffedSignature: List<String>,
    copyToClipboard: (String, String) -> Unit,
) {
    when {
        parsedArgs.isNotEmpty() -> {
            Text(
                text = "参数 (${parsedArgs.size})",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            parsedArgs.forEach {
                ArgumentDetailRow(it,
                    onClick = { copyToClipboard("参数 #${it.index}", it.displayValue) })
            }
        }
        sniffedSignature.isNotEmpty() -> {
            DetailRow(
                "参数指纹",
                sniffedSignature.joinToString(", ", prefix = "(", postfix = ")") +
                    "  — 启发式推断,非精确签名",
                valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> {
            Text(
                text = "(无解析参数)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}

/**
 * 渲染 ParsedReply 的返回值 / 异常 / raw hex 摘要(响应数据 section 用)。
 * 三选一,都没有则兜底文案。
 */
@Composable
private fun ParsedReplyBlock(
    parsedReply: ReplyParser.ReplyDecodeResult?,
    copyToClipboard: (String, String) -> Unit,
) {
    when {
        parsedReply?.exception != null -> {
            DetailRow(
                "异常",
                parsedReply.exception,
                valueColor = MaterialTheme.colorScheme.error,
                onClick = { copyToClipboard("异常", parsedReply.exception) }
            )
        }
        parsedReply?.value != null -> {
            DetailRow(
                "返回值",
                parsedReply.value,
                valueColor = MaterialTheme.colorScheme.tertiary,
                onClick = { copyToClipboard("返回值", parsedReply.value) }
            )
        }
        !parsedReply?.rawHexHint.isNullOrEmpty() -> {
            DetailRow(
                "返回值(原始)",
                parsedReply!!.rawHexHint!!,
                valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
                mono = true,
            )
        }
        else -> {
            Text(
                text = "(reply 已到达,但未解出结构化返回值;可展开下方完整 hex 自查)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
            )
        }
    }
}

/**
 * Parcel hex 区:标题 + 复制按钮 + 折叠/展开切换。
 * defaultExpanded:request 默认展开,reply 默认折叠(隐私 spec § 8.2)。
 * rememberKey:不同 event 之间不复用展开状态(常见 key = event.id)。
 */
@Composable
private fun ParcelHexRegion(
    title: String,
    hex: String,
    byteCount: Int,
    defaultExpanded: Boolean,
    rememberKey: Long,
    copyLabel: String,
    collapsedButtonLabel: String,
    copyToClipboard: (String, String) -> Unit,
) {
    Spacer(modifier = Modifier.height(6.dp))
    var expanded by remember(rememberKey) { mutableStateOf(defaultExpanded) }
    if (expanded) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (byteCount > 0 && hex.isNotEmpty()) {
                IconButton(
                    onClick = { copyToClipboard(copyLabel, hex) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制 $copyLabel",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        HexDumpBlock(hex = hex, byteCount = byteCount)
    } else {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(collapsedButtonLabel)
        }
    }
}

/**
 * hex dump 展示块。
 *   - 外层 Column 已 verticalScroll,这里附加 horizontalScroll 让每条 `offset: bytes  |ascii|`
 *     保持在同一行,避免文本换行导致的错位(原来竖条边界会被折断)。
 *   - 文本放进 SelectionContainer,长按可选中复制(方便贴到外部工具里对比/分析)。
 *   - 字号 11sp、行高紧凑,手机上一屏能看更多字节。
 */
@Composable
private fun HexDumpBlock(hex: String, byteCount: Int) {
    if (byteCount == 0 || hex.isEmpty()) {
        Text(
            text = "(无 parcel 数据)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        return
    }
    val hScroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            )
    ) {
        SelectionContainer {
            Text(
                text = hex.trimEnd(),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                softWrap = false,
                maxLines = Int.MAX_VALUE,
                modifier = Modifier
                    .horizontalScroll(hScroll)
                    .padding(10.dp)
            )
        }
    }
}

/**
 * 详情页单参数行。两行布局:
 *   第一行:#idx  <类型全名>     —— 类型名永远完整显示,不截断
 *   第二行:       <value>
 *   第三行(仅失败时):↳ errorMessage
 *
 * 改为两行是因为类型名可能很长(`WindowManager$LayoutParams`、`List<ComponentName>`),
 * 之前放一行时给类型固定 120dp 宽度,长类型被省略号截断,反而看不到"是什么类型"。
 */
@Composable
private fun ArgumentDetailRow(
    arg: DecodedArgument,
    onClick: (() -> Unit)? = null
) {
    val valueColor = when (arg.status) {
        DecodedArgument.Status.SUCCESS -> MaterialTheme.colorScheme.onSurface
        DecodedArgument.Status.UNPARSED -> MaterialTheme.colorScheme.error
    }
    val columnMod = Modifier
        .fillMaxWidth()
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(vertical = 4.dp)
    Column(modifier = columnMod) {
        // 第一行:#idx + 类型(允许换行,softWrap)
        Row {
            Text(
                text = "#${arg.index}",
                modifier = Modifier.width(40.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = arg.declaredType,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
        }
        // 第二行:value。带 onClick 时整 Column 已 clickable,直接展示纯 Text;
        // 不带 onClick 时仍包 SelectionContainer,允许长按选中部分文本。
        if (onClick != null) {
            Text(
                text = arg.displayValue,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 40.dp, top = 1.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
                fontFamily = FontFamily.Monospace
            )
        } else {
            SelectionContainer(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 40.dp, top = 1.dp)
            ) {
                Text(
                    text = arg.displayValue,
                    style = MaterialTheme.typography.bodyMedium,
                    color = valueColor,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        // 第三行:失败原因
        arg.errorMessage?.let {
            Text(
                text = "↳ $it",
                modifier = Modifier.padding(start = 40.dp, top = 1.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.75f),
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    mono: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null
) {
    val rowMod = Modifier
        .fillMaxWidth()
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(vertical = 3.dp)
    Row(
        modifier = rowMod,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.width(84.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Box(modifier = Modifier.weight(1f)) {
            // onClick 模式下整行已 clickable,内层走纯 Text;否则保留 SelectionContainer
            // 让用户能选中复制片段。
            if (onClick != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = valueColor,
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
                )
            } else {
                SelectionContainer {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = valueColor,
                        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
                    )
                }
            }
        }
    }
}

/**
 * 事件过滤弹窗。
 *
 * 设计原则:
 * - 桶 chip 勾选立即生效(onBucketToggle → 直接推 EventRepository),关闭弹窗不丢失选择。
 * - 文本框在 ViewModel 侧做 debounce 300ms,UI 侧维护本地临时字符串状态以保持输入流畅。
 * - 去掉"应用过滤"按钮;底部保留"清除全部"(快捷复位)和"关闭"。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FilterDialog(
    currentFilter: EventFilter,
    coverage: CoverageSnapshot,
    onInterfaceFilterChange: (String) -> Unit,
    onMethodFilterChange: (String) -> Unit,
    onStackModuleFilterChange: (String) -> Unit,
    onBucketToggle: (CoverageBucket) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    // 本地文本状态:跟随 currentFilter 初始化,之后由用户输入驱动(不反向同步,
    // 避免 debounce 期间 currentFilter 未更新导致光标跳回)。
    var interfaceText by remember(currentFilter.interfaceContains) {
        mutableStateOf(currentFilter.interfaceContains)
    }
    var methodText by remember(currentFilter.methodContains) {
        mutableStateOf(currentFilter.methodContains)
    }
    var stackModuleText by remember(currentFilter.stackModuleContains) {
        mutableStateOf(currentFilter.stackModuleContains)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("事件过滤")
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭"
                    )
                }
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 类型筛选(spec § 6.5):5 桶 FilterChip,与覆盖率仪表盘共用 CoverageSnapshot
                // 数据源,实时计数。多选 = OR;默认全选 = 显示全部。勾选立即生效。
                BucketFilterSection(
                    bucketFilter = currentFilter.bucketsAllowed,
                    coverage = coverage,
                    onBucketToggle = onBucketToggle
                )

                // 接口名子串过滤:本地 state 驱动文本框,输入传给 ViewModel 做 debounce 推送。
                // ✕ 按钮仅在有内容时显示,一键清空。
                OutlinedTextField(
                    value = interfaceText,
                    onValueChange = { v ->
                        interfaceText = v
                        onInterfaceFilterChange(v)
                    },
                    label = { Text("Interface 包含") },
                    placeholder = { Text("如 IPackageManager 或 android.app.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        if (interfaceText.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    interfaceText = ""
                                    onInterfaceFilterChange("")
                                },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "清空 Interface 过滤",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                )

                OutlinedTextField(
                    value = methodText,
                    onValueChange = { v ->
                        methodText = v
                        onMethodFilterChange(v)
                    },
                    label = { Text("Method 包含") },
                    placeholder = { Text("如: startActivity") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        if (methodText.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    methodText = ""
                                    onMethodFilterChange("")
                                },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "清空 Method 过滤",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                )

                // 调用栈 .so 过滤:任一栈帧 module 子串命中即通过。
                // 典型用法:输入 `libgui` 看哪些 binder 调用经过了 libgui.so。
                // 该字段非空时,无栈事件一律不通过(没栈无法验证)。
                OutlinedTextField(
                    value = stackModuleText,
                    onValueChange = { v ->
                        stackModuleText = v
                        onStackModuleFilterChange(v)
                    },
                    label = { Text("经过的 .so") },
                    placeholder = { Text("如: libgui / libandroid_runtime") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        if (stackModuleText.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    stackModuleText = ""
                                    onStackModuleFilterChange("")
                                },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "清空 .so 过滤",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                )
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = {
                    onClear()
                    // 同步本地文本状态,避免 debounce 未触发时残留旧值。
                    interfaceText = ""
                    methodText = ""
                    stackModuleText = ""
                }) {
                    Text("清除全部")
                }
                Button(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun BucketFilterSection(
    bucketFilter: Set<CoverageBucket>,
    coverage: CoverageSnapshot,
    onBucketToggle: (CoverageBucket) -> Unit
) {
    val nf = remember { NumberFormat.getIntegerInstance(Locale.US) }
    // 有效选中集:空集合按"全选"处理,避免 UI 全灰无法判断状态。
    val effective = if (bucketFilter.isEmpty()) EventFilter.ALL_BUCKETS else bucketFilter
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "类型筛选",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            BUCKET_DISPLAY.forEach { (bucket, label) ->
                val count = coverage.buckets[bucket] ?: 0L
                val selected = effective.contains(bucket)
                FilterChip(
                    selected = selected,
                    onClick = { onBucketToggle(bucket) },
                    label = {
                        // label 文字:名称加粗;计数用 monospace + 略大字号,视觉醒目。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = label,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "(${nf.format(count)})",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    },
                    // 选中态:高饱和度 primary,图标 √;未选中:outline 风格,无图标。
                    leadingIcon = if (selected) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                            )
                        }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }
    }
}

private val BUCKET_DISPLAY: List<Pair<CoverageBucket, String>> = listOf(
    CoverageBucket.AIDL to "AIDL",
    CoverageBucket.HIDL to "HIDL",
    CoverageBucket.REPLY to "Reply",
    CoverageBucket.SPECIAL to "特殊 code",
    CoverageBucket.UNKNOWN to "Unknown"
)

/**
 * 为 LazyColumn 提供右侧滑动指示 + 拖拽跳转。
 *
 * 与 [reverseLayout] 的交互要点:LazyColumn 的 [LazyListState.firstVisibleItemIndex]
 * 始终指向"物理第一个可见条目"在 items 参数中的 index —— reverseLayout=true 时,
 * 物理顶部对应数据 list 的末尾。为了让"指示器在顶对应最新事件"这个直觉成立,
 * 这里做一次反向映射:把 firstVisibleItemIndex 归一到 [0,1] 后再 1- 翻转,
 * 拖拽时反算回 index 再 scrollToItem。
 *
 * 条目不足以铺满屏幕(visible >= total)时直接隐藏,避免鬼影指示器。
 */
@Composable
private fun VerticalScrollbar(
    listState: LazyListState,
    reverseLayout: Boolean = false,
    modifier: Modifier = Modifier,
    width: Dp = 4.dp,
    minThumbHeight: Dp = 48.dp,
) {
    val scope = rememberCoroutineScope()
    val thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)

    Canvas(
        modifier = modifier
            .width(20.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, _ ->
                    val info = listState.layoutInfo
                    val total = info.totalItemsCount
                    val visible = info.visibleItemsInfo.size
                    if (total == 0 || visible >= total) return@detectVerticalDragGestures

                    val trackH = size.height.toFloat()
                    if (trackH <= 0f) return@detectVerticalDragGestures

                    // fraction: 0 = 物理顶, 1 = 物理底
                    // reverseLayout 下 firstVisibleItemIndex 指向 items 顺序的小端,
                    // 物理顶对应 items 末尾(最新),所以拖拽需要镜像映射:
                    //   fraction=0(拖到物理顶) ↔ firstIdx=range(显示 items 末尾=最新)
                    //   fraction=1(拖到物理底) ↔ firstIdx=0(显示 items 头部=最旧)
                    val fraction = (change.position.y / trackH).coerceIn(0f, 1f)
                    val scrollableRange = (total - visible).coerceAtLeast(1)
                    val firstIdxTarget = if (reverseLayout) {
                        ((1f - fraction) * scrollableRange).toInt()
                    } else {
                        (fraction * scrollableRange).toInt()
                    }.coerceIn(0, scrollableRange)

                    scope.launch { listState.scrollToItem(firstIdxTarget) }
                }
            }
    ) {
        val info = listState.layoutInfo
        val total = info.totalItemsCount
        val visible = info.visibleItemsInfo.size
        // 条目不足以铺满屏幕时直接隐藏,避免鬼影指示器。
        if (total == 0 || visible >= total) return@Canvas

        val scrollableRange = (total - visible).coerceAtLeast(1)
        val firstIdx = listState.firstVisibleItemIndex.coerceIn(0, scrollableRange)

        // reverseLayout=true 时,firstIdx 越小 ↔ 物理上越靠下(看更旧的 items)。
        // 所以 topFraction(0=物理顶) = 1 - firstIdx/range,这样:
        //   看最新(物理顶)→ firstIdx=range → topFraction=0 → thumb 在顶 ✓
        //   往历史方向滑 → firstIdx 变小 → topFraction 变大 → thumb 往下 ✓
        val topFraction = if (reverseLayout) {
            1f - firstIdx.toFloat() / scrollableRange
        } else {
            firstIdx.toFloat() / scrollableRange
        }

        val trackH = size.height
        val thumbH = (visible.toFloat() / total * trackH)
            .coerceAtLeast(minThumbHeight.toPx())
            .coerceAtMost(trackH)
        val thumbY = topFraction * (trackH - thumbH)
        val thumbW = width.toPx()
        val thumbX = size.width - thumbW - 2.dp.toPx()

        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(x = thumbX, y = thumbY),
            size = Size(width = thumbW, height = thumbH),
            cornerRadius = CornerRadius(thumbW / 2)
        )
    }
}

// =====================================================
// spec 2026-05-03 § 4.5.1:调用栈展示(全量永远开启,无栈即解析失败)
// =====================================================

/**
 * 详情页"调用栈"区段。
 *
 * - stackTrace == null → daemon 端 stack record 缺失或解析失败,展示提示
 * - stackTrace != null → 默认折叠,展开后按 FULL/FP_ONLY/DEGRADED/FAILED 配色,
 *   长按帧复制 `module!symbol+0xoffset`
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StackTraceSection(stackTrace: StackTrace?) {
    if (stackTrace == null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "调用栈",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "无栈数据(daemon 端抓取失败或解析失败)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    // 有栈
    var expanded by remember { mutableStateOf(false) }
    val qualityColor = when (stackTrace.quality) {
        StackQuality.FULL -> Color(0xFF4CAF50)        // 绿
        StackQuality.FP_ONLY -> Color(0xFFFFB300)     // 琥珀
        StackQuality.DEGRADED -> Color(0xFFFF7043)    // 橙红
        StackQuality.FAILED -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "调用栈",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stackTrace.quality.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = qualityColor,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (stackTrace.kstackTruncated || stackTrace.ustackTruncated) {
                Text(
                    text = "(已截断 — kstack=${stackTrace.kstackTruncated} ustack=${stackTrace.ustackTruncated})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (stackTrace.failureReason.isNotEmpty()) {
                Text(
                    text = "失败原因: ${stackTrace.failureReason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                if (stackTrace.uFrames.isNotEmpty()) {
                    Text(
                        text = "[Native]",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    // Native 栈展示顺序反转(spec § 4.5.1 增强):bpf_get_stack 返回的是
                    // **栈顶在前**(#0 = __ioctl,即最近调用)。但人类阅读调用栈时更习惯
                    // **调用方在前**:Java framework → JNI → BpBinder → IPCThreadState →
                    // libc!__ioctl。这里 reverse 一次让显示从"最早调用方"开始往下递进到
                    // "最深 ioctl"。原始数据顺序保持不变(StackTrace.uFrames 仍是 BPF 原序),
                    // 只在渲染层调换。
                    stackTrace.uFrames.asReversed().forEachIndexed { idx, frame ->
                        StackFrameRow(idx = idx, frame = frame, color = qualityColor)
                    }
                }
                // [Kernel] 段已不再渲染:BPF 端不再抓内核栈(详见
                // daemon/binder_transaction.byte.c st->kstack_depth = 0 的注释),
                // stackTrace.kFrames 永远空。此处保留 if (kFrames.isNotEmpty()) 的死分支
                // 供旧 daemon 兼容 / 未来 kallsyms 可用时一行重启用。
                if (stackTrace.kFrames.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "[Kernel]",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    stackTrace.kFrames.asReversed().forEachIndexed { idx, frame ->
                        StackFrameRow(idx = idx, frame = frame, color = qualityColor)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StackFrameRow(idx: Int, frame: StackFrame, color: Color) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val text = frame.displayText()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { /* 单击空操作,留长按复制 */ },
                onLongClick = {
                    clipboardManager.setText(AnnotatedString(text))
                    Toast.makeText(context, "已复制: $text", Toast.LENGTH_SHORT).show()
                }
            )
            .padding(vertical = 1.dp)
    ) {
        Text(
            text = "#$idx",
            modifier = Modifier.width(36.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontFamily = FontFamily.Monospace,
            softWrap = true
        )
    }
}
