package com.example.personal_studio.feature.kejian

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.personal_studio.BuildConfig
import com.example.personal_studio.data.remote.kejian.KejianApi
import com.example.personal_studio.data.remote.kejian.LatestRelease
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Downloading(val read: Long, val total: Long, val source: String) : UpdateState
    data object Verifying : UpdateState
    data class Ready(val versionName: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * App 内更新：下载（国内镜像优先，断了从断点续传，镜像不通换 GitHub）→ 核对 SHA-256 →
 * 核对安装包的包名、版本号和签名证书与当前课间一致 → 交给系统安装器。
 * 下载在自己的作用域里跑，离开更新页也不会停；进程被杀后下次从 .part 文件续传。
 */
@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    api: KejianApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null
    private var readyFile: File? = null

    // 下载可能要好几分钟：去掉整次请求的超时，只保留读超时
    private val http = api.client.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val dir get() = File(context.cacheDir, "updates")

    fun start(release: LatestRelease) {
        if (job?.isActive == true) return
        job = scope.launch { run(release) }
    }

    fun cancel() {
        job?.cancel()
        _state.value = UpdateState.Idle
    }

    /** 当前版本已经不旧了（更新装好了，或者服务器上没有更新的版本），清掉下载的安装包。 */
    fun cleanup(latest: LatestRelease?) {
        if (job?.isActive == true) return
        if (latest == null || latest.versionCode <= BuildConfig.VERSION_CODE) dir.deleteRecursively()
    }

    private suspend fun run(rel: LatestRelease) {
        try {
            dir.mkdirs()
            dir.listFiles()?.filterNot { it.name.startsWith(rel.apk.name) }?.forEach { it.delete() }
            val file = File(dir, rel.apk.name)
            val part = File(dir, rel.apk.name + ".part")
            val have = file.exists() && file.length() == rel.apk.size && sha256(file).equals(rel.apk.sha256, ignoreCase = true)
            if (!have) {
                file.delete()
                download(rel, part)
                _state.value = UpdateState.Verifying
                if (!sha256(part).equals(rel.apk.sha256, ignoreCase = true)) {
                    part.delete()
                    return fail("下载的文件和官方发布的不一致（SHA-256 校验没通过），已删除，请重试。")
                }
                if (!part.renameTo(file)) return fail("保存安装包失败，请重试。")
            }
            _state.value = UpdateState.Verifying
            verifyApk(file, rel)?.let {
                file.delete()
                return fail(it)
            }
            readyFile = file
            _state.value = UpdateState.Ready(rel.versionName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(if (e is IOException) "下载中断了，网络恢复后点“继续下载”，会从断点接着下。" else "更新出错：${e.message ?: "未知错误"}")
        }
    }

    private fun fail(message: String) {
        _state.value = UpdateState.Failed(message)
    }

    private suspend fun download(rel: LatestRelease, part: File) {
        var last: IOException? = null
        for (url in rel.apk.urls) {
            val source = if (url.startsWith(KejianApi.BASE)) "国内镜像" else "GitHub"
            try {
                downloadFrom(url, part, rel.apk.size, source)
                return
            } catch (e: IOException) {
                last = e // 换下一个地址接着下：同一个文件，已经下好的部分可以续用
            }
        }
        throw last ?: IOException("没有可用的下载地址")
    }

    private suspend fun downloadFrom(url: String, part: File, total: Long, source: String) {
        var have = if (part.exists()) part.length() else 0L
        if (have > total) {
            part.delete()
            have = 0
        }
        if (have == total) return
        val req = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        http.newCall(req).execute().use { res ->
            val append = res.code == 206
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            if (!append) have = 0 // 服务器不支持续传：从头来
            val body = res.body ?: throw IOException("empty body")
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var read = have
                    var lastEmit = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val now = System.nanoTime()
                        if (now - lastEmit > 100_000_000L) {
                            _state.value = UpdateState.Downloading(read, total, source)
                            lastEmit = now
                        }
                    }
                    _state.value = UpdateState.Downloading(read, total, source)
                }
            }
        }
        if (part.length() < total) throw IOException("incomplete")
    }

    /** 包名、版本号、签名证书都和当前课间对得上才放行；返回 null 表示没问题。 */
    private fun verifyApk(file: File, rel: LatestRelease): String? {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return "安装包无法解析，已删除，请重试。"
        if (info.packageName != context.packageName) return "这个安装包不是课间，已拒绝安装。"
        if (info.longVersionCode <= BuildConfig.VERSION_CODE) return "安装包的版本不比当前新，不需要安装。"
        val apkCerts = certs(info.signingInfo)
        val mine = certs(pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo)
        if (rel.apk.cert != null && apkCerts.none { KejianLogic.sameFingerprint(it, rel.apk.cert) }) {
            return "安装包的签名和官方公布的不一致，已拒绝安装。"
        }
        if (apkCerts.none { a -> mine.any { KejianLogic.sameFingerprint(a, it) } }) {
            return if (BuildConfig.DEBUG) "这是调试版，签名和正式版不同，不能直接覆盖安装。请先卸载调试版再装正式版。"
            else "安装包的签名和你手机上的课间不一致，已拒绝安装。"
        }
        return null
    }

    private fun certs(s: SigningInfo?): List<String> {
        s ?: return emptyList()
        val arr = if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        return arr.orEmpty().map { KejianLogic.certFingerprint(it.toByteArray()) }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 是否已经允许课间“安装未知应用”。没有的话先去系统设置里打开。 */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    // 不能加 FLAG_ACTIVITY_NEW_TASK：那样系统会立刻返回“已取消”，用户还没来得及打开开关
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** 交给系统安装器。安装完成后系统会结束旧进程，课间重新打开就是新版本。 */
    fun install(): Boolean {
        val file = readyFile?.takeIf { it.exists() } ?: return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
