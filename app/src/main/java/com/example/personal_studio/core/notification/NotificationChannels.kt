package com.example.personal_studio.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.ContextCompat

object NotificationChannels {
    const val REMINDERS_ID = "timeline_reminders"
    const val OVERDUE_ID = "timeline_overdue"
    const val GRADES_ID = "grade_updates"
    const val DDL_ID = "assignment_ddl"
    const val FEEDBACK_ID = "feedback_replies"
    const val ANNOUNCE_ID = "announcements"

    fun ensureCreated(context: Context) {
        val nm = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(REMINDERS_ID, "提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "课程 / DDL / 自定义事件的提前提醒"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(OVERDUE_ID, "已过期", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "DDL 已过期但未标记完成"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(GRADES_ID, "成绩更新", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "后台查到新成绩 / 自动查询状态变更"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(DDL_ID, "作业提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "后台查到新作业 DDL / 自动查询状态变更"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(FEEDBACK_ID, "反馈回复", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "开发者回复了你的反馈、你反馈的问题已修复"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(ANNOUNCE_ID, "公告", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "开发者发布的通知，比如学校系统改版、服务维护"
            }
        )
    }
}
