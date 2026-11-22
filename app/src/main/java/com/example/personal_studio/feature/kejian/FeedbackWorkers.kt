package com.example.personal_studio.feature.kejian

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** 有网时把没发出去的反馈发出去；发不出就按指数退避重试。 */
@HiltWorker
class FeedbackSendWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repo: KejianRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        if (runCatching { repo.sendPending() }.getOrDefault(false)) Result.success() else Result.retry()
}

/** 每几个小时问一次服务器：反馈有没有回复、问题有没有修好。没有待回复的反馈时自己取消。 */
@HiltWorker
class FeedbackReplyWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repo: KejianRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        runCatching { repo.sendPending() }
        runCatching { repo.pollReplies(notify = true) }
        if (!repo.hasOpenFeedback()) FeedbackScheduler.cancelReplyPolling(applicationContext)
        return Result.success()
    }
}

object FeedbackScheduler {
    private const val SEND = "kejian-feedback-send"
    private const val POLL = "kejian-feedback-replies"
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun enqueueSend(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            SEND,
            // 正在跑的那次不打断（打断可能导致重复提交），新的接在后面
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<FeedbackSendWorker>()
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )
    }

    fun ensureReplyPolling(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            POLL,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FeedbackReplyWorker>(4, TimeUnit.HOURS)
                .setConstraints(network)
                .setInitialDelay(20, TimeUnit.MINUTES)
                .build(),
        )
    }

    fun cancelReplyPolling(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(POLL)
    }
}
