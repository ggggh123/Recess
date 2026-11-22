package com.example.personal_studio.core.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.personal_studio.MainActivity
import com.example.personal_studio.R
import com.example.personal_studio.data.local.datastore.LocalFeedback
import javax.inject.Inject
import javax.inject.Singleton

/** 反馈有了回复、反馈的问题修好了。点开进“问题反馈”页。 */
@Singleton
class FeedbackNotifier @Inject constructor() {

    fun reply(context: Context, f: LocalFeedback) {
        val reply = f.reply ?: return
        post(
            context,
            id = NID_BASE + ((f.serverId ?: 0L) % 100_000).toInt(),
            title = "开发者回复了你的反馈",
            text = reply,
            big = "你：${f.body.lineSequence().first().take(60)}\n\n$reply",
        )
    }

    fun fixed(context: Context, f: LocalFeedback) {
        val issue = f.issue ?: return
        post(
            context,
            id = NID_BASE + 100_000 + (issue.id % 100_000).toInt(),
            title = "你反馈的问题已修复",
            text = issue.title + (issue.fixedIn?.let { " · $it 版本" } ?: ""),
            big = issue.title + (issue.fixedIn?.let { "\n在 $it 版本修复，更新后就好了。" } ?: ""),
        )
    }

    fun cancelAll(context: Context) {
        val nm = context.getSystemService(android.app.NotificationManager::class.java) ?: return
        runCatching { nm.activeNotifications.filter { it.id in NID_BASE until NID_BASE + 200_000 }.forEach { nm.cancel(it.id) } }
    }

    private fun post(context: Context, id: Int, title: String, text: String, big: String) {
        NotificationChannels.ensureCreated(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("personalstudio://settings/feedback")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, NotificationChannels.FEEDBACK_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // 没有通知权限：回复仍会在下次打开 App 时弹窗显示
        }
    }

    private companion object {
        const val NID_BASE = 7_000_000
    }
}
