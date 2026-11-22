package com.example.personal_studio.feature.kejian.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.personal_studio.BuildConfig
import com.example.personal_studio.data.remote.kejian.LatestRelease
import com.example.personal_studio.feature.kejian.KejianLogic
import com.example.personal_studio.feature.kejian.UpdateState
import com.example.personal_studio.feature.timeline.today.enterUp
import com.example.personal_studio.ui.components.PillStyle
import com.example.personal_studio.ui.components.TgCard
import com.example.personal_studio.ui.components.TgGroupLabel
import com.example.personal_studio.ui.components.TgPillButton
import com.example.personal_studio.ui.components.TgProgressTrack
import com.example.personal_studio.ui.components.TgSettingRow
import com.example.personal_studio.ui.components.TgSwitch
import com.example.personal_studio.ui.components.TgTopBar
import com.example.personal_studio.ui.shell.LocalShellToast
import com.example.personal_studio.ui.theme.SerifFamily
import com.example.personal_studio.ui.theme.Tg
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 检查更新：当前版本、新版本的更新说明，以及 App 内下载安装（进度、校验、系统安装器）。
 * 下面是“自动检查更新”开关。
 */
@Composable
fun UpdateScreen(onBack: () -> Unit, vm: UpdateViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val dl by vm.download.collectAsStateWithLifecycle()
    val c = Tg.colors
    val toast = LocalShellToast.current
    // 从系统“安装未知应用”设置页回来后，如果已经允许，直接继续安装
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (vm.canInstall()) vm.install() else toast?.show("没有开启“安装未知应用”，暂时不能安装")
    }
    val install = {
        if (vm.canInstall()) {
            if (!vm.install()) toast?.show("没能打开系统安装器")
        } else {
            permission.launch(vm.permissionIntent())
        }
    }

    Column(Modifier.fillMaxSize()) {
        TgTopBar(route = "检查更新", onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                "课间 ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = SerifFamily, fontWeight = FontWeight.Bold),
                color = c.text,
                modifier = Modifier.padding(top = 8.dp).enterUp(true, 0),
            )
            Text(
                if (ui.newer) "有新版本可以更新" else "当前版本" + if (BuildConfig.DEBUG) " · 调试版" else "",
                style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
                modifier = Modifier.padding(top = 4.dp).enterUp(true, 0),
            )

            TgCard(Modifier.padding(top = 20.dp).enterUp(true, 60)) {
                Column(Modifier.padding(18.dp)) {
                    val latest = ui.latest
                    if (ui.newer && latest != null) NewVersion(latest, dl, onStart = vm::start, onCancel = vm::cancel, onInstall = install)
                    else UpToDate(ui, onCheck = vm::checkNow)
                }
            }

            TgGroupLabel("设置", Modifier.enterUp(true, 100))
            TgCard(Modifier.enterUp(true, 100)) {
                TgSettingRow(
                    "自动检查更新", icon = Icons.Outlined.Autorenew, first = true,
                    summary = "每天第一次打开课间时问一次服务器，有新版本会提醒你",
                    onClick = { vm.setAutoCheck(!ui.autoCheck) },
                    trailing = { TgSwitch(ui.autoCheck, onCheckedChange = { vm.setAutoCheck(it) }) },
                )
            }
            Text(
                "安装包下载完会核对 SHA-256，并确认包名和签名证书与你手机上的课间一致，才会交给系统安装器。" +
                    "国内下载走官网服务器，连不上时改用 GitHub；中途断了会从断点接着下。",
                style = MaterialTheme.typography.bodySmall, color = c.textTertiary,
                modifier = Modifier.padding(top = 14.dp, start = 4.dp, end = 4.dp).enterUp(true, 120),
            )
        }
    }
}

@Composable
private fun NewVersion(latest: LatestRelease, dl: UpdateState, onStart: () -> Unit, onCancel: () -> Unit, onInstall: () -> Unit) {
    val c = Tg.colors
    Text("${latest.versionName}", style = MaterialTheme.typography.titleLarge, color = c.text)
    Text(
        listOfNotNull(latest.releaseDate, KejianLogic.sizeText(latest.apk.size)).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(top = 2.dp),
    )
    if (latest.notes.isNotEmpty()) {
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            latest.notes.forEach { n ->
                NoteLine(n)
            }
        }
    }
    AnimatedContent(
        dl,
        contentKey = { it::class },
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
        label = "dl",
        modifier = Modifier.padding(top = 18.dp),
    ) { dl ->
        Column(Modifier.fillMaxWidth()) {
            when (dl) {
                UpdateState.Idle -> TgPillButton("下载并安装", onClick = onStart, modifier = Modifier.fillMaxWidth())
                is UpdateState.Downloading -> {
                    TgProgressTrack(if (dl.total > 0) dl.read.toFloat() / dl.total else 0f)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${dl.source} · ${KejianLogic.sizeText(dl.read)} / ${KejianLogic.sizeText(dl.total)}", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                        Text("${(dl.read * 100 / dl.total.coerceAtLeast(1))}%", style = MaterialTheme.typography.bodySmall, color = c.text)
                    }
                    TgPillButton("暂停", onClick = onCancel, style = PillStyle.SECONDARY, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                }
                UpdateState.Verifying -> {
                    TgProgressTrack(1f)
                    Text("正在核对安装包…", style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp))
                }
                is UpdateState.Ready -> {
                    Text("已下载并核对通过。", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    TgPillButton("安装 ${dl.versionName}", onClick = onInstall, style = PillStyle.ACCENT, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                }
                is UpdateState.Failed -> {
                    Text(dl.message, style = MaterialTheme.typography.bodySmall, color = c.accent)
                    TgPillButton("继续下载", onClick = onStart, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun UpToDate(ui: UpdateUi, onCheck: () -> Unit) {
    val c = Tg.colors
    Text("已经是最新版本", style = MaterialTheme.typography.titleLarge, color = c.text)
    Text(
        ui.checkError ?: if (ui.lastCheckAt > 0) "上次检查：${fmt(ui.lastCheckAt)}" else "还没有检查过",
        style = MaterialTheme.typography.bodySmall,
        color = if (ui.checkError != null) c.accent else c.textSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
    TgPillButton(
        if (ui.checking) "正在检查…" else "检查更新",
        onClick = onCheck, enabled = !ui.checking, style = PillStyle.SECONDARY,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    )
}

/** 更新说明的一行：前面一个强调色的小圆点，和文字第一行居中对齐。 */
@Composable
internal fun NoteLine(text: String) {
    val c = Tg.colors
    Row {
        Box(Modifier.padding(top = 9.dp, end = 10.dp).size(5.dp).background(c.accent, CircleShape))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.text)
    }
}

private val TimeFmt = DateTimeFormatter.ofPattern("M 月 d 日 HH:mm")
private fun fmt(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.of("Asia/Shanghai")).format(TimeFmt)
