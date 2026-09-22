package com.freebuff.feature.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.core.model.ChatMsg
import com.freebuff.core.model.ToolCard
import com.freebuff.core.model.toolDisplayName
import com.freebuff.core.model.toolGlyph
import com.freebuff.core.ui.MiniMarkdown
import com.freebuff.core.ui.R14
import com.freebuff.core.ui.RFull
import com.freebuff.core.ui.navigation.AppNavState
import com.freebuff.core.ui.navigation.LocalAppNavigator
import com.freebuff.core.ui.theme.LocalTokens

@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val t = LocalTokens.current
    val navigator = LocalAppNavigator.current
    val session by viewModel.activeSession.collectAsState()
    val streaming by viewModel.streaming.collectAsState()
    val modelId by viewModel.modelId.collectAsState()
    val modelList by viewModel.modelList.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(session?.messages?.size) {
        if (session != null && session!!.messages.isNotEmpty()) {
            listState.animateScrollToItem(session!!.messages.size - 1)
        }
    }
    val badge = modelList.firstOrNull { it.id == modelId }?.badge ?: "M"
    Column(Modifier.fillMaxSize()) {
        ChatHeader(
            title = session?.title ?: "新对话",
            badge = badge,
            onBack = { navigator.navigate(AppNavState.ROUTE_HOME) },
            onModel = { navigator.openSheet(AppNavState.SHEET_MODEL) },
        )
        val msgs = session?.messages.orEmpty()
        if (msgs.isEmpty()) {
            ChatEmpty(
                canPick = session != null,
                onPick = { viewModel.sendMessage(it) },
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().weight(1f),
                contentPadding = PaddingValues(16.dp)) {
                items(msgs, key = { it.id }) { m ->
                    MessageItem(
                        m = m,
                        isStreaming = streaming && msgs.lastOrNull()?.id == m.id,
                        onCopy = viewModel::copyText,
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        Composer(
            input = viewModel.chatInput.collectAsState().value,
            streaming = streaming,
            onInput = { viewModel.updateInput(it) },
            onSend = { viewModel.sendInput() },
            onStop = { viewModel.stopStreaming() },
            onTool = { tok -> viewModel.updateInput(viewModel.chatInput.value + tok + " ") },
        )
        // 切换会话时清空输入框,避免把 A 会话草稿发进 B 会话
        LaunchedEffect(session?.id) { viewModel.clearInputIfStale() }
    }
}

/** 空会话:原型中的 Hero + 示例任务卡片,点卡片直接发起。 */
@Composable
private fun ChatEmpty(canPick: Boolean, onPick: (String) -> Unit) {
    val t = LocalTokens.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(46.dp))
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(t.accentSoft),
            contentAlignment = Alignment.Center,
        ) { Text("›_", color = t.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(14.dp))
        Text("今天想构建点什么?", color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (canPick) "可以直接描述需求,或从下面的示例开始" else "从首页点「+」发起任务",
            color = t.text3, fontSize = 12.5.sp,
        )
        if (!canPick) return@Column
        Spacer(Modifier.height(22.dp))
        Text("示例", color = t.text3, fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        CHAT_SUGGESTS.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                pair.forEachIndexed { i, (icon, text) ->
                    Column(
                        Modifier.weight(1f).height(96.dp).clip(RoundedCornerShape(16.dp))
                            .background(t.surface).clickable { onPick(text) }.padding(13.dp),
                    ) {
                        Box(
                            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(t.accentSoft),
                            contentAlignment = Alignment.Center,
                        ) { Text(icon, color = t.accent, fontSize = 14.sp) }
                        Spacer(Modifier.height(10.dp))
                        Text(text, color = t.text2, fontSize = 12.5.sp, lineHeight = 17.sp, maxLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    if (i == 0 && pair.size > 1) Spacer(Modifier.width(10.dp))
                }
            }
        }
    }
}

@Composable
private fun ChatHeader(title: String, badge: String, onBack: () -> Unit, onModel: () -> Unit) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().background(t.elev).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("‹", color = t.text2, fontSize = 22.sp,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onBack() }.padding(horizontal = 8.dp, vertical = 2.dp))
        Text(title, color = t.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
        Text(badge, color = t.accentInk, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RFull).background(t.accent).clickable { onModel() }.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

@Composable
private fun MessageItem(m: ChatMsg, isStreaming: Boolean, onCopy: (String) -> Unit) {
    val t = LocalTokens.current
    if (m.role == "user") {
        Column(horizontalAlignment = Alignment.End) {
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(t.userBubble).padding(horizontal = 14.dp, vertical = 10.dp)) {
                m.ctxRepo.takeIf { it.isNotBlank() }?.let { CtxChip(it, t.accent) }
                m.ctxModel.takeIf { it.isNotBlank() }?.let { CtxChip(it, t.ok) }
                Text(m.text, color = t.text, fontSize = 14.sp, lineHeight = 21.sp)
            }
        }
    } else {
        AgentBody(m, isStreaming, onCopy)
    }
}

@Composable
private fun AgentCodeBlock(lang: String, code: String, onCopy: () -> Unit) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(t.codeBg)) {
        Row(Modifier.fillMaxWidth().background(t.codeHeader).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(lang, color = t.text3, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text("复制", color = t.accent, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onCopy() }.padding(horizontal = 6.dp))
        }
        Text(code, color = t.codeText, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp,
            modifier = Modifier.padding(10.dp))
    }
}

@Composable
private fun AgentBody(m: ChatMsg, isStreaming: Boolean, onCopy: (String) -> Unit) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().clip(R14).background(t.surface).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Freebuff", color = t.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            m.time.takeIf { it.isNotBlank() }?.let { Text(it, color = t.text3, fontSize = 10.5.sp) }
        }
        if (m.steps.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            m.steps.forEach { st ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Text("✓", color = t.ok, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(" " + st.name + " · ", color = t.text2, fontSize = 11.5.sp)
                    Text(st.sub, color = t.text3, fontSize = 11.5.sp)
                }
            }
        }
        // 工具卡片时间线(agent-architecture.md §4:按到达顺序渲染,无需排序)
        if (m.tools.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ToolCards(m.tools)
        }
        if (m.text.isNotBlank()) {
            // 真实流式回复写入 text;历史演示消息走 md/code/md2 结构化渲染
            Spacer(Modifier.height(8.dp))
            MiniMarkdown(m.text)
        }
        m.md.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            MiniMarkdown(it)
        }
        m.code.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            AgentCodeBlock(m.codeLang, it) { onCopy(it) }
        }
        m.md2.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            MiniMarkdown(it)
        }
        if (isStreaming) {
            Spacer(Modifier.height(6.dp))
            Text("● 正在生成…", color = t.accent, fontSize = 11.sp)
        } else {
            // 原型 .msg-actions:一条消息一个复制动作,复制完整正文(含代码块)
            Spacer(Modifier.height(6.dp))
            val payload = buildString {
                append(m.text)
                if (m.md.isNotBlank()) append(if (isEmpty()) "" else "\n").append(m.md)
                if (m.code.isNotBlank()) {
                    append(if (isEmpty()) "" else "\n").append("```").append(m.codeLang).append("\n")
                        .append(m.code).append("\n```")
                }
                if (m.md2.isNotBlank()) append(if (isEmpty()) "" else "\n").append(m.md2)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⧉ 复制", color = t.text3, fontSize = 11.5.sp,
                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).clickable { onCopy(payload) }
                        .padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}

/** 工具卡片列表:垂直时间线,左侧连线。 */
@Composable
private fun ToolCards(cards: List<ToolCard>) {
    Column {
        cards.forEachIndexed { i, card ->
            ToolCardItem(card, last = i == cards.lastIndex)
            if (i != cards.lastIndex) Spacer(Modifier.height(6.dp))
        }
    }
}

/** 单张工具卡片:状态点 + 展示名 + 输入摘要;点击展开输入/输出详情;运行中带呼吸动画。 */
@Composable
private fun ToolCardItem(card: ToolCard, last: Boolean) {
    val t = LocalTokens.current
    val expanded = remember(card.callId) { mutableStateOf(false) }
    val stateColor = when {
        card.isRunning -> t.accent
        card.isError -> t.danger
        else -> t.ok
    }
    val subagent = card.subagentName
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(t.surface2)
            .clickable { expanded.value = !expanded.value }.padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (card.isRunning) {
                val alpha = rememberInfiniteAlpha()
                Box(
                    Modifier.size(7.dp).clip(RFull).background(stateColor.copy(alpha = 0.35f + 0.65f * alpha)),
                )
            } else {
                Text(if (card.isError) "⚠" else "✓", color = stateColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(7.dp))
            if (subagent != null) {
                Text("✷ ", color = t.accent, fontSize = 11.5.sp)
                Text("子代理 · ", color = t.text2, fontSize = 11.5.sp)
                Text(subagent, color = t.text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            } else {
                Text(toolGlyph(card.tool) + " ", color = t.accent, fontSize = 11.sp)
                Text(toolDisplayName(card.tool), color = t.text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    card.isRunning -> "运行中"
                    card.isError -> "失败"
                    else -> "完成"
                },
                color = stateColor, fontSize = 10.sp, fontWeight = FontWeight.Medium,
            )
            Text(if (expanded.value) " ⌃" else " ⌄", color = t.text3, fontSize = 10.sp)
        }
        // 摘要行:输入摘要(路径/命令/搜索词);子代理显示实时输出尾部
        val summary = if (subagent != null && card.isRunning) {
            card.input.lines().lastOrNull { it.isNotBlank() }?.take(80).orEmpty().ifBlank { "启动中…" }
        } else card.input.take(80)
        if (summary.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(summary, color = t.text3, fontSize = 10.5.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        // 展开区:完整输入/输出
        if (expanded.value) {
            Spacer(Modifier.height(8.dp))
            DetailLine("输入", card.input.ifBlank { "(无参数)" })
            if (card.output.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                DetailLine("输出", card.output)
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    val t = LocalTokens.current
    Row {
        Text(label, color = t.text3, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text(value, color = t.text2, fontSize = 10.5.sp, lineHeight = 15.sp)
    }
}

/** 0..1 往返的呼吸透明度(卡片运行态指示)。 */
@Composable
private fun rememberInfiniteAlpha(): Float {
    return rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    ).value
}

@Composable
private fun CtxChip(label: String, color: Color) {
    Text("⚑ " + label, color = color, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RFull).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
private fun Composer(
    input: String,
    streaming: Boolean,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onTool: (String) -> Unit,
) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().background(t.elev).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.text.BasicTextField(
            value = input,
            onValueChange = onInput,
            modifier = Modifier.weight(1f).clip(RFull).background(t.surface2).padding(horizontal = 14.dp, vertical = 10.dp),
            textStyle = androidx.compose.ui.text.TextStyle(color = t.text, fontSize = 13.5.sp),
            singleLine = true,
            decorationBox = { inner ->
                Box {
                    if (input.isEmpty()) Text("描述任务…", color = t.text3, fontSize = 13.5.sp)
                    inner()
                }
            },
        )
        // 原型 .composer-tools:@ 与「引用文件」插入 token
        if (!streaming) {
            Text("@", color = t.text2, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onTool("@") }
                    .padding(horizontal = 8.dp, vertical = 6.dp))
            Text("📎", color = t.text2, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onTool("@文件") }
                    .padding(horizontal = 6.dp, vertical = 6.dp))
        }
        Spacer(Modifier.width(4.dp))
        if (streaming) {
            Text("停止", color = t.danger, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RFull).clickable { onStop() }.padding(horizontal = 12.dp, vertical = 9.dp))
        } else {
            val canSend = input.isNotBlank()
            Text("发送", color = if (canSend) t.accentInk else t.text3,
                fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RFull)
                    .background(if (canSend) t.accent else t.surface3)
                    .clickable(enabled = canSend) { onSend() }
                    .padding(horizontal = 14.dp, vertical = 9.dp))
        }
    }
}

/** 原型空会话中的示例任务(图标 + 文案)。 */
private val CHAT_SUGGESTS: List<Pair<String, String>> = listOf(
    "⌨" to "帮我写一个定时清理临时文件的脚本",
    "⚡" to "这段代码为什么慢?帮我优化",
    "▤" to "给我一份 30 天 Python 学习路线",
    "🐞" to "排查报错:undefined 的 map 调用",
)
