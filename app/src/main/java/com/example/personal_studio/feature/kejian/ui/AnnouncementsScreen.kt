package com.example.personal_studio.feature.kejian.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.personal_studio.data.remote.kejian.Announcement
import com.example.personal_studio.feature.timeline.today.enterUp
import com.example.personal_studio.ui.components.TgCard
import com.example.personal_studio.ui.components.TgGroupLabel
import com.example.personal_studio.ui.components.TgSettingRow
import com.example.personal_studio.ui.components.TgSwitch
import com.example.personal_studio.ui.components.TgTopBar
import com.example.personal_studio.ui.theme.Tg

/**
 * 公告：“接收公告”开关，下面是现在仍在显示期内的公告。打开这一页就算都看过了，
 * 不会再弹窗或发通知。
 */
@Composable
fun AnnouncementsScreen(onBack: () -> Unit, vm: AnnouncementsViewModel = hiltViewModel()) {
    val c = Tg.colors
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.onOpen() }

    Column(Modifier.fillMaxSize()) {
        TgTopBar(route = "公告", onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
                .navigationBarsPadding(),
        ) {
            TgCard(Modifier.padding(top = 4.dp).enterUp(true, 0)) {
                TgSettingRow(
                    "接收公告", icon = Icons.Outlined.Campaign, first = true,
                    summary = "开发者发布的通知，比如学校系统改版、服务维护。打开课间时和后台每隔几小时取一次，只发送版本号",
                    onClick = { vm.setEnabled(!enabled) },
                    trailing = { TgSwitch(enabled, onCheckedChange = { vm.setEnabled(it) }) },
                )
            }

            TgGroupLabel("现在的公告", Modifier.enterUp(true, 60))
            when {
                !enabled -> Hint("已关闭接收公告。打开后会显示开发者发布的通知。")
                items.isEmpty() -> Hint(if (refreshing) "正在获取…" else "现在没有公告。")
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.enterUp(true, 60)) {
                    items.forEach { AnnouncementCard(it) }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = Tg.colors.textSecondary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun AnnouncementCard(a: Announcement) {
    val c = Tg.colors
    val uri = LocalUriHandler.current
    TgCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (a.important) {
                    Text(
                        "重要", style = MaterialTheme.typography.labelMedium, color = c.onAccent,
                        modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(8.dp)).background(c.accent)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Text(announcementDate(a.publishedAt), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            }
            Text(a.title, style = MaterialTheme.typography.titleMedium, color = c.text)
            if (a.body.isNotBlank()) Text(a.body, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            a.link?.let { link ->
                Text(
                    "查看详情", style = MaterialTheme.typography.bodyMedium, color = c.accent,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { runCatching { uri.openUri(link) } }
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}
