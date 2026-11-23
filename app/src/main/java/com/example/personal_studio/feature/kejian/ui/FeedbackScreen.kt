package com.example.personal_studio.feature.kejian.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.personal_studio.data.local.datastore.LocalFeedback
import com.example.personal_studio.data.remote.kejian.KejianApi
import com.example.personal_studio.feature.kejian.KejianRepository
import com.example.personal_studio.feature.timeline.today.enterUp
import com.example.personal_studio.ui.components.TgCard
import com.example.personal_studio.ui.components.TgConfirmDialog
import com.example.personal_studio.ui.components.TgGroupLabel
import com.example.personal_studio.ui.components.TgPillButton
import com.example.personal_studio.ui.components.TgSegmented
import com.example.personal_studio.ui.components.TgSettingRow
import com.example.personal_studio.ui.components.TgSwitch
import com.example.personal_studio.ui.components.TgTextField
import com.example.personal_studio.ui.components.TgTopBar
import com.example.personal_studio.ui.shell.LocalShellToast
import com.example.personal_studio.ui.theme.Tg
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Kinds = listOf("bug" to "遇到问题", "idea" to "功能建议", "other" to "其他")
private const val MAX = 4000

/**
 * 问题反馈：写一条（先存本机，有网就发），下面是“我的反馈”和开发者的回复。
 * 回复到了会发通知，下次打开 App 也会弹窗；打开这一页就算看过了。
 */
@Composable
fun FeedbackScreen(onBack: () -> Unit, vm: FeedbackViewModel = hiltViewModel()) {
    val c = Tg.colors
    val ctx = LocalContext.current
    val toast = LocalShellToast.current
    val uri = LocalUriHandler.current
    val items by vm.items.collectAsStateWithLifecycle()
    var kind by rememberSaveable { mutableIntStateOf(0) }
    var body by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf("") }
    var withDevice by rememberSaveable { mutableStateOf(true) }
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) { vm.onOpen() }

    Column(Modifier.fillMaxSize()) {
        TgTopBar(route = "问题反馈", onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                "只有开发者能看到你写的内容。回复了会通知你，也会显示在下面。",
                style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp).enterUp(true, 0),
            )
            TgSegmented(
                options = Kinds.map { it.second }, selectedIndex = kind, onSelect = { kind = it },
                modifier = Modifier.fillMaxWidth().enterUp(true, 30), width = androidx.compose.ui.unit.Dp.Unspecified,
            )
            TgTextField(
                value = body,
                onValueChange = { body = it },
                maxLength = MAX,
                label = "发生了什么",
                placeholder = "在哪个页面、做了什么、看到了什么。能复现的话写下步骤。",
                hint = "${body.length} / $MAX",
                singleLine = false,
                minLines = 6,
                modifier = Modifier.padding(top = 16.dp).enterUp(true, 60),
            )
            TgTextField(
                value = contact,
                onValueChange = { contact = it },
                maxLength = 120,
                label = "联系方式（选填）",
                placeholder = "QQ、微信或邮箱，需要追问时用",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.padding(top = 12.dp).enterUp(true, 80),
            )
            TgCard(Modifier.padding(top = 12.dp).enterUp(true, 100)) {
                TgSettingRow(
                    "附带设备信息", icon = Icons.Outlined.PhoneAndroid, first = true,
                    summary = "机型、Android 版本和课间版本，方便定位问题",
                    onClick = { withDevice = !withDevice },
                    trailing = { TgSwitch(withDevice, onCheckedChange = { withDevice = it }) },
                )
            }
            TgPillButton(
                "提交",
                enabled = body.trim().length >= 4,
                onClick = {
                    vm.submit(Kinds[kind].first, body, contact, withDevice) {
                        body = ""
                        toast?.show("已保存，有网时会自动发出")
                    }
                    // 回复到了要靠通知提醒：没有通知权限就顺便请求一下
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp).enterUp(true, 120),
            )

            if (items.isNotEmpty()) {
                TgGroupLabel("我的反馈", Modifier.enterUp(true, 140))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.enterUp(true, 140)) {
                    items.forEach { f -> FeedbackCard(f, onDelete = { confirmDelete = f.localId }) }
                }
            }
            Text(
                "在网页上查看公开的问题板",
                style = MaterialTheme.typography.bodyMedium, color = c.accent,
                modifier = Modifier
                    .padding(top = 22.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { runCatching { uri.openUri(KejianApi.FEEDBACK_URL) } }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
            )
        }
    }

    confirmDelete?.let { id ->
        TgConfirmDialog(
            title = "删除这条反馈？",
            message = "开发者那边的这一份也会一起删除，不能恢复。",
            confirmLabel = "删除",
            onConfirm = {
                confirmDelete = null
                vm.delete(id) { toast?.show(it) }
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun FeedbackCard(f: LocalFeedback, onDelete: () -> Unit) {
    val c = Tg.colors
    TgCard(Modifier.animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (label, active) = statusOf(f)
                Box(Modifier.size(7.dp).background(if (active) c.accent else c.textTertiary, CircleShape))
                Text(
                    "${Kinds.firstOrNull { it.first == f.kind }?.second ?: "反馈"} · ${fmt(f.createdAt)} · $label",
                    style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                Text(
                    "删除", style = MaterialTheme.typography.bodySmall, color = c.textTertiary,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onDelete).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text(f.body, style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
            f.reply?.let { reply ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.accentSoft)
                        .padding(12.dp),
                ) {
                    Text("开发者回复 · ${f.replyAt?.let(::fmt) ?: ""}", style = MaterialTheme.typography.labelMedium, color = c.accent)
                    Text(reply, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.padding(top = 4.dp))
                }
            }
            f.issue?.let { i ->
                val st = when (i.status) {
                    "progress" -> "处理中"
                    "fixed" -> i.fixedIn?.let { "已在 $it 修复" } ?: "已修复"
                    "wontfix" -> "暂不处理"
                    else -> "已收到"
                }
                Text("已整理为公开问题 #${i.id}「${i.title}」· $st", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        }
    }
}

private fun statusOf(f: LocalFeedback): Pair<String, Boolean> = when {
    f.status == KejianRepository.REJECTED -> "没能提交" to false
    !f.sent -> "等待发送" to true
    f.reply != null -> "已回复" to true
    f.status == "closed" -> "已关闭" to false
    f.status == "read" -> "开发者已读" to false
    else -> "已送达" to false
}

private val TimeFmt = DateTimeFormatter.ofPattern("M 月 d 日 HH:mm")
private fun fmt(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.of("Asia/Shanghai")).format(TimeFmt)
