package com.example.personal_studio.domain.bitddl

import com.example.personal_studio.data.network.bit.SchoolPageException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.example.personal_studio.data.local.datastore.DdlSyncPrefs
import com.example.personal_studio.data.network.bit.BitApiClient
import com.example.personal_studio.data.network.bit.NetworkMode
import com.example.personal_studio.data.network.bit.autoNetworkFallback
import com.example.personal_studio.domain.bitddl.model.BackgroundDdlResult
import com.example.personal_studio.domain.bitddl.model.DdlSource
import com.example.personal_studio.domain.bitddl.model.DdlSyncError
import com.example.personal_studio.domain.bitddl.model.DdlSyncOutcome
import com.example.personal_studio.domain.bitddl.model.DdlSyncRequest
import com.example.personal_studio.domain.bitddl.model.DdlSyncStep
import com.example.personal_studio.domain.bitddl.model.LexueUrlResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CancellationException
import java.io.IOException
import javax.inject.Inject

/**
 * 作业 DDL 同步编排,两个平台在同一次会话里依次拉取,结果互不影响:
 *   - 乐学:策略 Y,优先用持久化 .ics URL 免登录直拉;无/失效则登录派生。
 *   - 新乐学:畅课作业接口,Cookie 失效时走统一认证(同一会话里通常不必再交密码)。
 *   - sync(req): Flow<DdlSyncStep>      前台手动刷新(自管 open/close,按平台落库 + 设基线)。
 *   - syncForBackground(req)            后台静默(假设 apiClient 已 open,仅返回各平台的 fetch 结果)。
 */
class SyncAssignmentsUseCase @Inject constructor(
    private val apiClient: BitApiClient,
    private val parser: LexueIcalParser,
    private val generateUrl: GenerateLexueIcalUrlUseCase,
    private val prefs: DdlSyncPrefs,
    private val replacer: ReplaceImportedDdlUseCase,
    private val fetchEclass: FetchEclassAssignmentsUseCase,
    private val detector: DetectNewDdlUseCase,
) {
    fun sync(req: DdlSyncRequest): Flow<DdlSyncStep> = flow {
        apiClient.acquireSession()
        try {
            apiClient.open(req.networkMode)
            emit(DdlSyncStep.FetchingCalendar)
            val lexue = fetchEvents(req)
            emit(DdlSyncStep.FetchingEclass)
            val eclass = guarded { fetchEclass(req) }
            val outcome = DdlSyncOutcome(mapOf(DdlSource.LEXUE to lexue, DdlSource.ECLASS to eclass))
            if (outcome.succeeded.isEmpty()) {
                emit(DdlSyncStep.Failed(outcome.primaryError))
            } else {
                store(outcome)
                // A manual refresh shows everything, so it also becomes the baseline for notifications.
                val diff = detector.invoke(outcome)
                prefs.setLastSeenUids(diff.fullUids)
                prefs.setBaselinedSources(diff.baselined.map { it.name }.toSet())
                prefs.setLastSyncAt(System.currentTimeMillis())
                emit(
                    DdlSyncStep.Done(
                        total = outcome.events.size,
                        newCount = 0,
                        counts = outcome.bySource.mapNotNull { (s, r) -> (r as? BackgroundDdlResult.Ok)?.let { s to it.events.size } }.toMap(),
                        failed = outcome.failures,
                    ),
                )
            }
        } finally {
            try { apiClient.close() } finally { apiClient.releaseSession() }
        }
    }

    /** Writes each platform that synced; a failed one keeps what it had. */
    suspend fun store(outcome: DdlSyncOutcome) {
        outcome.bySource.forEach { (source, r) -> if (r is BackgroundDdlResult.Ok) replacer.invoke(source, r.events) }
    }

    /** Auto 模式:先按 [req].networkMode 试,连接级失败(NetworkFail)自动换另一网络模式重试一次,
     *  成功后回告生效模式。其余失败不回退。只有乐学连不上(新乐学成功)也算连接级失败:
     *  乐学校内直连、校外走 WebVPN,线路选错时只有它失败。 */
    fun syncAuto(req: DdlSyncRequest, onModeSucceeded: (NetworkMode) -> Unit): Flow<DdlSyncStep> =
        autoNetworkFallback(
            first = req.networkMode,
            isConnFail = { it.unreachable() },
            isDone = { it is DdlSyncStep.Done && !it.unreachable() },
            switchingStep = { DdlSyncStep.SwitchingMode(it) },
            onModeSucceeded = onModeSucceeded,
        ) { mode -> sync(req.copy(networkMode = mode)) }

    /** 后台:假设 apiClient 已 open。 */
    suspend fun syncForBackground(req: DdlSyncRequest): DdlSyncOutcome = DdlSyncOutcome(
        mapOf(DdlSource.LEXUE to fetchEvents(req), DdlSource.ECLASS to guarded { fetchEclass(req) }),
    )

    /** Anything unexpected from one platform stays that platform's (retryable) failure. */
    private suspend fun guarded(block: suspend () -> BackgroundDdlResult): BackgroundDdlResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        BackgroundDdlResult.Transient
    }

    private suspend fun fetchEvents(req: DdlSyncRequest): BackgroundDdlResult {
        var url = prefs.snapshot().icalUrl
        if (url == null) {
            when (val r = generateUrl.invoke(req)) {
                is LexueUrlResult.Failed -> return r.toBackgroundFail()
                is LexueUrlResult.Ok -> { url = r.url; prefs.setIcalUrl(url) }
            }
        }
        return try {
            val body = fetchIcs(url!!)
            if (looksLikeCalendar(body)) {
                BackgroundDdlResult.Ok(parser.parse(body))
            } else {
                when (val r = generateUrl.invoke(req)) {
                    is LexueUrlResult.Failed -> r.toBackgroundFail()
                    is LexueUrlResult.Ok -> {
                        prefs.setIcalUrl(r.url)
                        val body2 = fetchIcs(r.url)
                        if (looksLikeCalendar(body2)) BackgroundDdlResult.Ok(parser.parse(body2))
                        else BackgroundDdlResult.Stop(DdlSyncError.ParseFail("非日历响应"))
                    }
                }
            }
        } catch (io: IOException) {
            BackgroundDdlResult.Transient
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            BackgroundDdlResult.Transient
        }
    }

    /**
     * The subscription is stored as 乐学's own address; off campus it is read through WebVPN.
     * A WebVPN that is not signed in answers like any non-calendar page: the caller signs in
     * (which signs WebVPN in too) and derives the address again.
     */
    private suspend fun fetchIcs(url: String): String {
        val routed = url.toHttpUrlOrNull()?.let { apiClient.routed(it).toString() } ?: url
        return try {
            val resp = apiClient.lexue.getIcs(routed)
            (resp.body() ?: resp.errorBody())?.string().orEmpty()
        } catch (e: SchoolPageException) {
            ""
        }
    }

    private fun looksLikeCalendar(body: String): Boolean = body.contains("BEGIN:VCALENDAR")

    /** 派生 URL(含登录)失败:连接级(NetworkFail)归为 Transient(可换网络/重试),其余(密码错/评教等)Stop。 */
    private fun LexueUrlResult.Failed.toBackgroundFail(): BackgroundDdlResult =
        if (error is DdlSyncError.NetworkFail) BackgroundDdlResult.Transient
        else BackgroundDdlResult.Stop(error)
}

/** Nothing could be reached, or 乐学 alone could not (see [DdlSyncOutcome.lexueUnreachable]). */
internal fun DdlSyncStep.unreachable(): Boolean = when (this) {
    is DdlSyncStep.Failed -> error is DdlSyncError.NetworkFail
    is DdlSyncStep.Done -> failed[DdlSource.LEXUE] is DdlSyncError.NetworkFail
    else -> false
}
