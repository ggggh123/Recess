package com.example.personal_studio.feature.kejian.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.personal_studio.feature.kejian.KejianLogic
import com.example.personal_studio.ui.components.PillStyle
import com.example.personal_studio.ui.components.TgDialog
import com.example.personal_studio.ui.components.TgPillButton
import com.example.personal_studio.ui.theme.Tg

/**
 * 打开 App 时的两个弹窗，一次最多一个：先是开发者的新回复，再是新版本。
 * 只在主页面（底部有标签栏的那几页）显示，登录、导入这类流程里不打扰。
 */
@Composable
fun KejianPrompts(
    enabled: Boolean,
    onOpenFeedback: () -> Unit,
    onOpenUpdate: () -> Unit,
    vm: KejianPromptsViewModel = hiltViewModel(),
) {
    val reply by vm.reply.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    if (!enabled) return
    val c = Tg.colors

    val r = reply
    val u = update
    if (r != null) {
        TgDialog(onDismiss = { vm.seen() }, title = "开发者回复了你的反馈") {
            Text(
                "你：${r.body}", style = MaterialTheme.typography.bodySmall, color = c.textTertiary,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Column(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.accentSoft)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
            ) {
                Text(r.reply.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = c.text)
            }
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TgPillButton("知道了", onClick = { vm.seen() }, style = PillStyle.SECONDARY, modifier = Modifier.weight(1f))
                TgPillButton("查看全部", onClick = { vm.seen(); onOpenFeedback() }, modifier = Modifier.weight(1f))
            }
        }
    } else if (u != null) {
        TgDialog(onDismiss = { vm.later(u.versionCode) }, title = "课间 ${u.versionName} 可以更新了") {
            Text(
                listOfNotNull(u.releaseDate, KejianLogic.sizeText(u.apk.size)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
            )
            if (u.notes.isNotEmpty()) {
                Column(Modifier.padding(top = 12.dp).heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    u.notes.forEach { NoteLine(it) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TgPillButton("以后再说", onClick = { vm.later(u.versionCode) }, style = PillStyle.SECONDARY, modifier = Modifier.weight(1f))
                TgPillButton("更新", onClick = { vm.update(u); onOpenUpdate() }, modifier = Modifier.weight(1f))
            }
        }
    }
}
