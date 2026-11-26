package com.example.personal_studio.core.di

import android.content.Context
import com.example.personal_studio.BuildConfig
import androidx.room.Room
import com.example.personal_studio.data.deep.*
import com.example.personal_studio.data.remote.kejian.KejianApi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DeepModeModule {
    @Binds abstract fun deepMode(impl: DeepModeImpl): DeepMode

    companion object {
        @Provides @Singleton
        fun database(@ApplicationContext context: Context): DeepDatabase =
            Room.databaseBuilder(context, DeepDatabase::class.java, "deep-mode.db").build()

        @Provides fun dao(database: DeepDatabase): DeepDao = database.deepDao()

        @Provides @Singleton @DeepApplicationScope
        fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Provides @Singleton
        fun gateway(api: KejianApi, prefs: DeepPrefs): DeepGateway = DeepGateway(
            "${BuildConfig.DEEP_GATEWAY.ifEmpty { KejianApi.BASE }}/api/v1/agent/".toHttpUrl(), prefs::deviceKey,
            api.client.newBuilder().callTimeout(60, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS).writeTimeout(45, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .addInterceptor(::retryOnceIfSafe)
                .build(),
        )
    }
}

/**
 * 空闲的保持连接可能已被服务器关掉（本机 Node 5 秒、Nginx 75 秒），OkHttp 复用到它时请求还没送达就失败。
 * 客户端关了自动重试（新建会话不能重复发），这里只对重发也安全的请求再试一次：查询、删除，
 * 以及带幂等编号的发消息（clientMessageId）和中止。已经拿到响应的请求不会重发。
 */
internal fun retryOnceIfSafe(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
    val request = chain.request()
    return try {
        chain.proceed(request)
    } catch (e: java.io.IOException) {
        val path = request.url.encodedPath
        val safe = request.method == "GET" || request.method == "DELETE" ||
            (request.method == "POST" && (path.endsWith("/messages") || path.endsWith("/abort")))
        if (!safe || chain.call().isCanceled()) throw e
        chain.proceed(request)
    }
}
