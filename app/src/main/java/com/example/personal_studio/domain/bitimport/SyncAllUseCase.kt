package com.example.personal_studio.domain.bitimport

import com.example.personal_studio.data.local.datastore.ImportCredentialPrefs
import com.example.personal_studio.data.local.datastore.SavedCredentials
import com.example.personal_studio.data.network.bit.BitTrace
import com.example.personal_studio.data.network.bit.NetworkMode
import com.example.personal_studio.data.network.bit.SchoolProblem
import com.example.personal_studio.domain.bitddl.SyncAssignmentsUseCase
import com.example.personal_studio.domain.bitddl.model.DdlSyncRequest
import com.example.personal_studio.domain.bitddl.model.DdlSyncStep
import com.example.personal_studio.domain.bitexam.SyncExamsUseCase
import com.example.personal_studio.domain.bitexam.model.ExamSyncRequest
import com.example.personal_studio.domain.bitexam.model.ExamSyncStep
import com.example.personal_studio.domain.bitgrades.SyncGradesUseCase
import com.example.personal_studio.domain.bitgrades.model.GradesSyncRequest
import com.example.personal_studio.domain.bitgrades.model.SyncGradesStep
import com.example.personal_studio.domain.bitimport.model.ImportCredentials
import com.example.personal_studio.domain.bitimport.model.ImportRequest
import com.example.personal_studio.domain.bitimport.model.ImportStep
import com.example.personal_studio.domain.bitimport.model.SyncAllProgress
import com.example.personal_studio.domain.bitimport.model.SyncFailure
import com.example.personal_studio.domain.bitimport.model.SyncSource
import com.example.personal_studio.domain.bitimport.model.SyncSourceState
import com.example.personal_studio.domain.bitimport.model.SyncSourceStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/**
 * 一键批量同步编排:串行(共享单例 BitApiClient,必须串行)收集四个现成 `*Auto` 自包含 Flow
 * (各自 open→登录→拉取→落库→close + 网络回退),逐源发 [SyncAllProgress] 快照。
 * 单源失败/异常仅标 FAILED 并给出 [SyncFailure] 原因,不中断其余。无凭据则发 noCredentials 终态。
 * [run] 的 `only` 只跑指定来源(“只重试失败项”),其余来源沿用 `previous` 中的结果。
 */
class SyncAllUseCase @Inject constructor(
    private val credPrefs: ImportCredentialPrefs,
    private val resolveMode: ResolveNetworkModeUseCase,
    private val importCourses: ImportCoursesUseCase,
    private val syncAssignments: SyncAssignmentsUseCase,
    private val syncExams: SyncExamsUseCase,
    private val syncGrades: SyncGradesUseCase,
) {
    fun run(
        only: Set<SyncSource> = SyncSource.values().toSet(),
        previous: Map<SyncSource, SyncSourceState> = emptyMap(),
    ): Flow<SyncAllProgress> = flow {
        val creds = credPrefs.observeAll().value
        if (creds == null) {
            emit(SyncAllProgress(pendingAll(), done = true, noCredentials = true))
            return@flow
        }
        // 教学中心 is public; 成绩 and 乐学 need campus or WebVPN, so only they follow the probe.
        val publicMode = resolveMode(creds.lastMode, SchoolRoute.PUBLIC)
        val mode = resolveMode(creds.lastMode, SchoolRoute.CAMPUS)
        BitTrace.log("sync: 教学中心 $publicMode，成绩/乐学 $mode")
        val onMode: (NetworkMode) -> Unit = { m -> credPrefs.save(creds.username, creds.password, m) }

        val states = LinkedHashMap<SyncSource, SyncSourceState>().apply {
            SyncSource.values().forEach { src ->
                val kept = previous[src]?.takeIf { src !in only }
                put(src, kept ?: SyncSourceState(SyncSourceStatus.PENDING))
            }
        }
        emit(SyncAllProgress(states.toMap(), done = false))

        val steps: List<Pair<SyncSource, suspend () -> SyncSourceState>> = listOf(
            SyncSource.COURSES to { collectCourses(creds, publicMode, onMode) },
            SyncSource.DDL to { collectDdl(creds, mode, onMode) },
            SyncSource.EXAMS to { collectExams(creds, publicMode, onMode) },
            SyncSource.GRADES to { collectGrades(creds, mode, onMode) },
        )
        for ((src, exec) in steps) {
            if (src !in only) continue
            states[src] = SyncSourceState(SyncSourceStatus.RUNNING)
            emit(SyncAllProgress(states.toMap(), done = false))
            BitTrace.log("sync: ▶ $src")
            states[src] = runCatching { exec() }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                BitTrace.log("sync: $src threw ${SyncFailures.describe(e)}")
                fail(SyncFailure.OTHER)
            }
            BitTrace.log("sync: ■ $src ${states[src]?.status} ${states[src]?.failure ?: ""}")
            emit(SyncAllProgress(states.toMap(), done = false))
        }
        emit(SyncAllProgress(states.toMap(), done = true))
    }

    private fun pendingAll(): Map<SyncSource, SyncSourceState> =
        SyncSource.values().associateWith { SyncSourceState(SyncSourceStatus.PENDING) }

    private fun trace(error: Any) = BitTrace.log("sync: failed with ${SyncFailures.describe(error)}")

    private fun ok(detail: String) = SyncSourceState(SyncSourceStatus.OK, detail)
    private fun fail(reason: SyncFailure) = SyncSourceState(SyncSourceStatus.FAILED, failure = reason)
    private val failed = fail(SyncFailure.OTHER)

    private suspend fun collectCourses(
        creds: SavedCredentials, mode: NetworkMode, onMode: (NetworkMode) -> Unit,
    ): SyncSourceState {
        var state = failed
        importCourses.importAuto(
            ImportRequest(ImportCredentials(creds.username, creds.password), mode, rememberPwd = true),
            channelFor = { Channel<Boolean>(capacity = 1).apply { trySend(true) } },
            onModeSucceeded = onMode,
        ).collect { step ->
            when (step) {
                is ImportStep.Done -> state = ok("${step.result.successCount} 节")
                is ImportStep.Failed -> { trace(step.err); state = fail(SyncFailures.of(step.err)) }
                is ImportStep.SwitchingMode -> BitTrace.log("sync: switching to ${step.to}")
                else -> {}
            }
        }
        return state
    }

    private suspend fun collectDdl(
        creds: SavedCredentials, mode: NetworkMode, onMode: (NetworkMode) -> Unit,
    ): SyncSourceState {
        var state = failed
        syncAssignments.syncAuto(
            DdlSyncRequest(creds.username, creds.password, mode, rememberPwd = true), onModeSucceeded = onMode,
        ).collect { step ->
            when (step) {
                is DdlSyncStep.Done -> state = ok(
                    "${step.total} 条" + step.failed.keys.joinToString("、") { it.label }.let { if (it.isEmpty()) "" else " · ${it}未同步" },
                )
                is DdlSyncStep.Failed -> { trace(step.error); state = fail(SyncFailures.of(step.error)) }
                is DdlSyncStep.SwitchingMode -> BitTrace.log("sync: switching to ${step.to}")
                else -> {}
            }
        }
        return state
    }

    private suspend fun collectExams(
        creds: SavedCredentials, mode: NetworkMode, onMode: (NetworkMode) -> Unit,
    ): SyncSourceState {
        var state = failed
        syncExams.syncAuto(
            ExamSyncRequest(creds.username, creds.password, mode, rememberPwd = true), onModeSucceeded = onMode,
        ).collect { step ->
            when (step) {
                is ExamSyncStep.Done -> state = ok("${step.total} 场")
                is ExamSyncStep.Failed -> { trace(step.error); state = fail(SyncFailures.of(step.error)) }
                is ExamSyncStep.SwitchingMode -> BitTrace.log("sync: switching to ${step.to}")
                else -> {}
            }
        }
        return state
    }

    private suspend fun collectGrades(
        creds: SavedCredentials, mode: NetworkMode, onMode: (NetworkMode) -> Unit,
    ): SyncSourceState {
        var state = failed
        syncGrades.syncAuto(
            GradesSyncRequest(creds.username, creds.password, mode, rememberPwd = true), onModeSucceeded = onMode,
        ).collect { step ->
            when (step) {
                is SyncGradesStep.Done -> state = ok("${step.courseCount} 门")
                is SyncGradesStep.Failed -> { trace(step.err); state = fail(SyncFailures.of(step.err)) }
                is SyncGradesStep.SwitchingMode -> BitTrace.log("sync: switching to ${step.to}")
                else -> {}
            }
        }
        return state
    }
}

/** Maps each source's error type onto the reasons the sync panel distinguishes. */
object SyncFailures {
    /** A log-safe description: the error's own text, with any echoed response body cut off. */
    fun describe(error: Any): String {
        val text = when (error) {
            is Throwable -> "${error.javaClass.simpleName}: ${error.message.orEmpty()}" +
                (error.cause?.let { " ← ${it.javaClass.simpleName}: ${it.message.orEmpty()}" } ?: "")
            else -> error.toString()
        }
        return text.substringBefore("JSON input").replace(Regex("""[?][^\s，,)]*"""), "?…").take(300)
    }

    fun of(p: SchoolProblem): SyncFailure = when (p) {
        SchoolProblem.WEBVPN_SIGN_IN -> SyncFailure.GATEWAY
        SchoolProblem.SESSION_EXPIRED -> SyncFailure.SESSION
        SchoolProblem.SERVER_BUSY -> SyncFailure.BUSY
        SchoolProblem.UNEXPECTED_PAGE -> SyncFailure.OTHER
    }

    fun of(e: com.example.personal_studio.domain.bitimport.model.ImportError): SyncFailure = when (e) {
        com.example.personal_studio.domain.bitimport.model.ImportError.WrongCredentials -> SyncFailure.PASSWORD
        com.example.personal_studio.domain.bitimport.model.ImportError.AccountLocked -> SyncFailure.LOCKED
        com.example.personal_studio.domain.bitimport.model.ImportError.CaptchaRequired -> SyncFailure.VERIFY
        is com.example.personal_studio.domain.bitimport.model.ImportError.NetworkFail -> SyncFailure.NETWORK
        com.example.personal_studio.domain.bitimport.model.ImportError.EmptySchedule -> SyncFailure.EMPTY
        is com.example.personal_studio.domain.bitimport.model.ImportError.School -> of(e.problem)
        else -> SyncFailure.OTHER
    }

    fun of(e: com.example.personal_studio.domain.bitddl.model.DdlSyncError): SyncFailure = when (e) {
        com.example.personal_studio.domain.bitddl.model.DdlSyncError.WrongCredentials -> SyncFailure.PASSWORD
        com.example.personal_studio.domain.bitddl.model.DdlSyncError.AccountLocked -> SyncFailure.LOCKED
        com.example.personal_studio.domain.bitddl.model.DdlSyncError.CaptchaRequired -> SyncFailure.VERIFY
        com.example.personal_studio.domain.bitddl.model.DdlSyncError.NeedReview -> SyncFailure.REVIEW
        is com.example.personal_studio.domain.bitddl.model.DdlSyncError.School -> of(e.problem)
        is com.example.personal_studio.domain.bitddl.model.DdlSyncError.NetworkFail -> SyncFailure.NETWORK
        else -> SyncFailure.OTHER
    }

    fun of(e: com.example.personal_studio.domain.bitexam.model.ExamSyncError): SyncFailure = when (e) {
        com.example.personal_studio.domain.bitexam.model.ExamSyncError.WrongCredentials -> SyncFailure.PASSWORD
        com.example.personal_studio.domain.bitexam.model.ExamSyncError.AccountLocked -> SyncFailure.LOCKED
        com.example.personal_studio.domain.bitexam.model.ExamSyncError.CaptchaRequired -> SyncFailure.VERIFY
        is com.example.personal_studio.domain.bitexam.model.ExamSyncError.School -> of(e.problem)
        is com.example.personal_studio.domain.bitexam.model.ExamSyncError.NetworkFail -> SyncFailure.NETWORK
        else -> SyncFailure.OTHER
    }

    fun of(e: com.example.personal_studio.domain.bitgrades.model.GradesSyncError): SyncFailure = when (e) {
        com.example.personal_studio.domain.bitgrades.model.GradesSyncError.WrongCredentials -> SyncFailure.PASSWORD
        com.example.personal_studio.domain.bitgrades.model.GradesSyncError.AccountLocked -> SyncFailure.LOCKED
        com.example.personal_studio.domain.bitgrades.model.GradesSyncError.CaptchaRequired -> SyncFailure.VERIFY
        com.example.personal_studio.domain.bitgrades.model.GradesSyncError.NeedReview -> SyncFailure.REVIEW
        com.example.personal_studio.domain.bitgrades.model.GradesSyncError.EmptyGrades -> SyncFailure.EMPTY
        is com.example.personal_studio.domain.bitgrades.model.GradesSyncError.School -> of(e.problem)
        is com.example.personal_studio.domain.bitgrades.model.GradesSyncError.NetworkFail -> SyncFailure.NETWORK
        else -> SyncFailure.OTHER
    }
}
