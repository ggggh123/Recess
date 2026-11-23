package com.example.personal_studio.feature.settings.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.personal_studio.domain.chat.ChatPrompts
import com.example.personal_studio.feature.settings.vm.SystemPromptViewModel
import com.example.personal_studio.feature.timeline.today.enterUp
import com.example.personal_studio.ui.components.PillStyle
import com.example.personal_studio.ui.components.TgConfirmDialog
import com.example.personal_studio.ui.components.TgPillButton
import com.example.personal_studio.ui.components.TgTextField
import com.example.personal_studio.ui.components.TgTopBar
import com.example.personal_studio.ui.shell.LocalShellToast
import com.example.personal_studio.ui.theme.Tg

/**
 * 系统提示词：问答每次请求开头发给模型的那段话。编辑后保存；“恢复默认”回到内置的那段。
 * 有没保存的修改时返回会先问一下。
 */
@Composable
fun SystemPromptScreen(onBack: () -> Unit, vm: SystemPromptViewModel = hiltViewModel()) {
    val c = Tg.colors
    val toast = LocalShellToast.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    // 第一次读到保存的值时填进输入框（之后输入框归用户）
    LaunchedEffect(ui.loaded) { if (ui.loaded && text == null) text = ui.effective }
    val current = text ?: ui.effective
    val dirty = ui.loaded && current.trim() != ui.effective.trim()
    val leave = { if (dirty) confirmLeave = true else onBack() }
    BackHandler(enabled = dirty) { confirmLeave = true }

    Column(Modifier.fillMaxSize()) {
        TgTopBar(route = "系统提示词", onBack = leave)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Text(
                "问答时每次都会先把这段话发给模型，用来规定它怎么回答。保存后，之后发出的每条消息都会用它，已有的对话也一样。" +
                    "整理知识卡片和学情报告有各自的固定格式，不受这里影响。",
                style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
                modifier = Modifier.padding(top = 4.dp).enterUp(true, 0),
            )
            Row(Modifier.padding(top = 14.dp).enterUp(true, 30), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(if (ui.custom != null) c.accent else c.textTertiary, CircleShape))
                Text(
                    if (ui.custom != null) "正在使用自定义提示词" else "正在使用默认提示词",
                    style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            TgTextField(
                value = current,
                onValueChange = { text = it },
                maxLength = ChatPrompts.MAX_LENGTH,
                singleLine = false,
                minLines = 12,
                placeholder = "留空就使用默认提示词",
                hint = "${current.length} / ${ChatPrompts.MAX_LENGTH}",
                modifier = Modifier.padding(top = 12.dp).enterUp(true, 60),
            )
            Text(
                "公式能正常显示，是因为要求模型用 $…$ 写公式；改写时建议保留这一条。清空后保存，等于恢复默认。",
                style = MaterialTheme.typography.bodySmall, color = c.textTertiary,
                modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp).enterUp(true, 80),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp).enterUp(true, 100),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TgPillButton(
                    "恢复默认",
                    onClick = { confirmReset = true },
                    enabled = ui.custom != null || current.trim() != ChatPrompts.DEFAULT,
                    style = PillStyle.SECONDARY,
                    modifier = Modifier.weight(1f),
                )
                TgPillButton(
                    "保存",
                    onClick = {
                        vm.save(current) {
                            text = ChatPrompts.effective(ChatPrompts.normalize(current))
                            toast?.show(if (ChatPrompts.normalize(current) == null) "已恢复默认提示词" else "已保存，之后的问答会使用它")
                        }
                    },
                    enabled = dirty,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (confirmReset) {
        TgConfirmDialog(
            title = "恢复默认提示词？",
            message = "输入框里的内容会换成内置的那段，之后的问答都用它。",
            confirmLabel = "恢复默认",
            onConfirm = {
                confirmReset = false
                vm.restoreDefault {
                    text = ChatPrompts.DEFAULT
                    toast?.show("已恢复默认提示词")
                }
            },
            onDismiss = { confirmReset = false },
        )
    }
    if (confirmLeave) {
        TgConfirmDialog(
            title = "放弃修改？",
            message = "改过的提示词还没有保存。",
            confirmLabel = "放弃",
            onConfirm = {
                confirmLeave = false
                onBack()
            },
            onDismiss = { confirmLeave = false },
        )
    }
}
