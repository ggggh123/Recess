package com.example.personal_studio.feature.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.personal_studio.data.deep.DeepChoice
import com.example.personal_studio.data.deep.DeepModelOption
import com.example.personal_studio.data.deep.DeepQuestion
import com.example.personal_studio.data.deep.DeepStep
import com.example.personal_studio.data.deep.DeepStepKind
import com.example.personal_studio.ui.components.TgChip
import com.example.personal_studio.ui.components.TgDialog
import com.example.personal_studio.ui.components.TgPillButton
import com.example.personal_studio.ui.components.PillStyle
import com.example.personal_studio.ui.components.TgTextField
import com.example.personal_studio.ui.theme.Tg
import com.example.personal_studio.ui.theme.TgMotion
import kotlinx.coroutines.delay

/**
 * 深度模式一轮的过程：搜索、读网页、运行代码……
 *
 * 进行中时展开，逐条列出步骤，右上角是已用时间；结束后收成一行摘要（“过程 · 搜索 1 次 · 运行 Python 2 次”），
 * 点一下展开。命令、代码和输出用等宽字体，长的先折叠。
 */
@Composable
fun DeepProcessCard(
    steps: List<DeepStep>,
    live: Boolean,
    modifier: Modifier = Modifier,
    startedAt: Long? = null,
    note: String? = null,
) {
    val c = Tg.colors
    val shape = RoundedCornerShape(16.dp)
    var expanded by rememberSaveable { mutableStateOf(false) }
    val open = live || expanded
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            .animateContentSize(TgMotion.standard()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .then(if (live) Modifier else Modifier.clickable(role = Role.Button, onClickLabel = if (expanded) "收起过程" else "展开过程") { expanded = !expanded })
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (live) {
                CircularProgressIndicator(Modifier.size(14.dp), color = c.accent, strokeWidth = 1.6.dp)
                Text("正在处理", style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
                if (startedAt != null) ElapsedClock(startedAt)
            } else {
                Icon(Icons.Outlined.ListAlt, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
                Text(
                    processSummary(steps),
                    style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(18.dp),
                )
            }
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(TgMotion.standard()), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (steps.isEmpty() && live) {
                    Text("正在思考怎么做…", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                }
                steps.forEach { StepRow(it) }
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun ElapsedClock(startedAt: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Text(formatElapsed(now - startedAt), style = MaterialTheme.typography.bodySmall, color = Tg.colors.textTertiary)
}

@Composable
private fun StepRow(step: DeepStep) {
    val c = Tg.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tint = when {
                step.running -> c.accent
                step.success == false -> c.error
                else -> c.textSecondary
            }
            Icon(stepIcon(step.kind), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Text(
                step.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (step.running) c.text else c.textSecondary,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (step.running) {
                CircularProgressIndicator(Modifier.size(12.dp), color = c.accent, strokeWidth = 1.4.dp)
            } else if (step.success == false) {
                Text("失败", style = MaterialTheme.typography.bodySmall, color = c.error)
            }
        }
        step.detail?.takeIf { it.isNotBlank() }?.let { CodeBlock(it, maxCollapsedLines = 8, muted = false) }
        step.output?.takeIf { it.isNotBlank() }?.let { CodeBlock(it, maxCollapsedLines = 6, muted = true) }
    }
}

/** 等宽代码块：横向可滚，超过 [maxCollapsedLines] 行先折叠，点“展开”看全部。 */
@Composable
private fun CodeBlock(text: String, maxCollapsedLines: Int, muted: Boolean) {
    val c = Tg.colors
    val lines = remember(text) { text.trimEnd().lines() }
    var full by remember(text) { mutableStateOf(false) }
    val shown = if (full || lines.size <= maxCollapsedLines) lines else lines.take(maxCollapsedLines)
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp)
            .clip(shape)
            .background(c.bg)
            .border(1.dp, c.line, shape),
    ) {
        Text(
            shown.joinToString("\n"),
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
            color = if (muted) c.textSecondary else c.text,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        if (lines.size > maxCollapsedLines) {
            Text(
                if (full) "收起" else "展开全部 ${lines.size} 行",
                style = MaterialTheme.typography.bodySmall,
                color = c.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { full = !full }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

private fun stepIcon(kind: DeepStepKind): ImageVector = when (kind) {
    DeepStepKind.SEARCH -> Icons.Outlined.Search
    DeepStepKind.FETCH -> Icons.Outlined.Language
    DeepStepKind.RUN -> Icons.Outlined.Terminal
    DeepStepKind.WRITE -> Icons.Outlined.EditNote
    DeepStepKind.READ -> Icons.Outlined.Description
    DeepStepKind.OTHER -> Icons.Outlined.Tune
}

/** “过程 · 搜索 1 次 · 运行 Python 2 次”；没有步骤时只写“过程”。最多列三类，按步骤多少排。 */
internal fun processSummary(steps: List<DeepStep>): String {
    val parts = steps.groupBy { summaryLabel(it) }
        .map { (label, items) -> label to items.size }
        .sortedByDescending { it.second }
        .take(3)
        .joinToString(" · ") { (label, n) -> "$label $n 次" }
    return if (parts.isEmpty()) "过程" else "过程 · $parts"
}

private fun summaryLabel(step: DeepStep): String = when (step.kind) {
    DeepStepKind.SEARCH -> "搜索"
    DeepStepKind.FETCH -> "读网页"
    DeepStepKind.RUN -> if (step.title.contains("Python")) "运行 Python" else "运行命令"
    DeepStepKind.WRITE -> "写文件"
    DeepStepKind.READ -> "读文件"
    DeepStepKind.OTHER -> "其他"
}

/** 服务器的模型 ID 换成给人看的名字：gpt-6.2-sol → GPT-6.2 Sol；认不出的原样返回。 */
internal fun deepModelLabel(id: String?): String? {
    if (id.isNullOrBlank()) return null
    val parts = id.split('-')
    if (parts.size < 2 || !parts[0].equals("gpt", ignoreCase = true)) return id
    val rest = parts.drop(2).joinToString(" ") { p -> p.replaceFirstChar { it.uppercaseChar() } }
    return ("GPT-" + parts[1] + if (rest.isEmpty()) "" else " $rest")
}

/** 标题栏第二行：“深度模式 · GPT-6.2 Sol”，不知道模型时只写“深度模式”。 */
internal fun deepSubtitle(model: String?): String = deepModelLabel(model)?.let { "深度模式 · $it" } ?: "深度模式"

/** 0:07、1:23、12:05。 */
internal fun formatElapsed(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * Agent 向用户提问：每个字段一组选项（单选或多选），允许时可以自己填；必填的都答了才能提交。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeepQuestionCard(
    question: DeepQuestion,
    onSubmit: (Map<String, List<String>>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Tg.colors
    val shape = RoundedCornerShape(16.dp)
    val picked = remember(question.formId) { mutableStateMapOf<String, List<String>>() }
    val typed = remember(question.formId) { mutableStateMapOf<String, String>() }
    var missing by remember(question.formId) { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(shape)
            .background(c.surfaceRaised)
            .border(1.dp, c.accent, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Agent 想先问你", style = MaterialTheme.typography.labelSmall, color = c.accent)
        question.fields.forEach { field ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                field.title?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = c.text) }
                field.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
                if (field.options.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        field.options.forEach { option ->
                            val current = picked[field.key].orEmpty()
                            TgChip(
                                label = option.label,
                                selected = option.value in current,
                                onClick = {
                                    missing = false
                                    picked[field.key] = when {
                                        !field.multiple -> listOf(option.value)
                                        option.value in current -> current - option.value
                                        else -> current + option.value
                                    }
                                },
                            )
                        }
                    }
                }
                if (field.options.isEmpty() || field.allowCustom) {
                    TgTextField(
                        value = typed[field.key].orEmpty(),
                        onValueChange = { typed[field.key] = it; missing = false },
                        placeholder = if (field.options.isEmpty()) "填写回答" else "或者自己填",
                    )
                }
            }
        }
        if (missing) Text("还有必答的问题没有回答", style = MaterialTheme.typography.bodySmall, color = c.error)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            TgPillButton("回答", onClick = {
                val answer = questionAnswer(question, picked, typed)
                if (question.fields.any { it.required && answer[it.key].isNullOrEmpty() }) missing = true else onSubmit(answer)
            })
        }
    }
}

/** 自己填的内容优先；单选字段只取一个值。 */
internal fun questionAnswer(
    question: DeepQuestion,
    picked: Map<String, List<String>>,
    typed: Map<String, String>,
): Map<String, List<String>> = question.fields.associate { field ->
    val custom = typed[field.key]?.trim().orEmpty()
    val chosen = picked[field.key].orEmpty()
    field.key to when {
        custom.isNotEmpty() && field.multiple -> chosen + custom
        custom.isNotEmpty() -> listOf(custom)
        field.multiple -> chosen
        else -> chosen.take(1)
    }
}.filterValues { it.isNotEmpty() }

/** 第一次用深度模式前的说明。 */
@Composable
fun DeepPrivacyDialog(onAccept: () -> Unit, onOpenPrivacy: () -> Unit, onDismiss: () -> Unit) {
    val c = Tg.colors
    TgDialog(onDismiss = onDismiss, title = "使用深度模式前") {
        Text(DEEP_PRIVACY_TEXT, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        Text(
            "查看隐私与安全",
            style = MaterialTheme.typography.bodyMedium, color = c.accent,
            modifier = Modifier
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onOpenPrivacy)
                .padding(vertical = 6.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TgPillButton("取消", onClick = onDismiss, style = PillStyle.SECONDARY, modifier = Modifier.weight(1f))
            TgPillButton("同意并继续", onClick = onAccept, modifier = Modifier.weight(1f))
        }
    }
}

internal const val DEEP_PRIVACY_TEXT =
    "深度模式下，你的题目和图片会发到课间服务器，由服务器上的 Agent 调用 OpenAI 的模型处理；" +
        "它会在一个断网的沙箱里运行代码，需要时联网搜索、读网页。\n\n" +
        "服务器上的会话在最后一次使用 7 天后删除，手机上的记录不受影响。不会发送学号、学校账号或其他学校数据。"

/** 服务器上的会话没了（过期或被删除）：只能看，不能再发。 */
@Composable
fun DeepExpiredNote(onNewConversation: () -> Unit, modifier: Modifier = Modifier) {
    val c = Tg.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Block, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
            Text(
                "服务器上的会话已过期，这里只能查看。",
                style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
            )
        }
        TgPillButton("新开深度对话", onClick = onNewConversation, style = PillStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
    }
}

/** 推理等级的中文名。 */
internal fun effortLabel(effort: String?): String = when (effort) {
    "none" -> "不思考"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "很高"
    "max" -> "最高"
    null -> "默认"
    else -> effort
}

/** 输入框上方标签上的字：“GPT-6.2 Sol · 推理 中”。 */
internal fun choiceLabel(models: List<DeepModelOption>, choice: DeepChoice): String {
    val name = models.firstOrNull { it.id == choice.model }?.name ?: deepModelLabel(choice.model) ?: choice.model
    return if (choice.effort == null) name else "$name · 推理 ${effortLabel(choice.effort)}"
}

/** 深度对话输入框上方的小标签：当前的模型和推理等级，点开换。 */
@Composable
fun DeepChoiceChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Tg.colors
    Row(
        modifier
            .padding(start = 16.dp, top = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = "换模型或推理等级", onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Outlined.Tune, contentDescription = null, tint = c.accent, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1)
        Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
    }
}

/**
 * 选模型和推理等级：上面是模型（名字和一句话说明），下面是推理等级。选了立即生效（从下一条消息起），
 * 换模型时如果原来的推理等级新模型不支持，换成它支持的中间一档。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeepChoiceSheet(
    models: List<DeepModelOption>,
    choice: DeepChoice,
    onSelect: (DeepChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Tg.colors
    val current = models.firstOrNull { it.id == choice.model }
    com.example.personal_studio.ui.components.TgBottomSheet(onDismiss = onDismiss, header = "模型与推理等级") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            models.forEach { m ->
                val selected = m.id == choice.model
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(role = Role.RadioButton) {
                            val effort = choice.effort?.takeIf { it in m.efforts }
                                ?: m.efforts.getOrNull(m.efforts.size / 2)
                            onSelect(DeepChoice(m.id, effort))
                        }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.bodyLarge, color = c.text)
                        if (m.description.isNotBlank()) {
                            Text(m.description, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                        }
                    }
                    if (selected) Icon(Icons.Outlined.Check, contentDescription = "当前模型", tint = c.accent, modifier = Modifier.size(20.dp))
                }
            }
            val efforts = current?.efforts.orEmpty()
            if (efforts.isNotEmpty()) {
                Text(
                    "推理等级", style = MaterialTheme.typography.labelLarge, color = c.textSecondary,
                    modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp),
                )
                FlowRow(
                    Modifier.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    efforts.forEach { e ->
                        TgChip(label = effortLabel(e), selected = e == choice.effort, onClick = { onSelect(choice.copy(effort = e)) })
                    }
                }
                Text(
                    "越高想得越久：回答更仔细，也更慢、更耗额度。从下一条消息起生效。",
                    style = MaterialTheme.typography.bodySmall, color = c.textTertiary,
                    modifier = Modifier.padding(start = 8.dp, top = 10.dp),
                )
            }
        }
    }
}
