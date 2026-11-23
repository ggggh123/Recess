package com.example.personal_studio.core.di

import com.example.personal_studio.BuildConfig
import com.example.personal_studio.data.network.bit.BitCookieJar
import com.example.personal_studio.data.network.bit.BitBrowserCookieInterceptor
import com.example.personal_studio.data.network.bit.BitBrowserHeaders
import com.example.personal_studio.data.network.bit.BitTrace
import com.example.personal_studio.data.network.bit.SchoolPageGuard
import com.example.personal_studio.data.network.bit.origin
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BitNetworkModule {

    @Provides
    @Singleton
    @Named("bit")
    fun provideBitOkHttp(cookieJar: BitCookieJar, browserCookies: BitBrowserCookieInterceptor): OkHttpClient {
        BitTrace.sink = { Log.i("BitTrace", it) }
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .header("User-Agent", BitBrowserHeaders.USER_AGENT)
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
                    .apply {
                        if (original.method == "POST") header("Origin", original.url.origin())
                        if ("/jwapp/" in original.url.encodedPath && "index.do" !in original.url.encodedPath) {
                            header("X-Requested-With", "XMLHttpRequest")
                            header("Accept", "application/json, text/javascript, */*; q=0.01")
                        }
                    }
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(BitTrace.finals)
            .addInterceptor(SchoolPageGuard())
            .addNetworkInterceptor(browserCookies)
            .addNetworkInterceptor(BitTrace.hops)
        if (BuildConfig.DEBUG) {
            builder.addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(request).also {
                    // No query strings (CAS tickets / calendar tokens), headers, or bodies.
                    Log.d("BitAuth", "${request.method} ${request.url.host}${request.url.encodedPath} → ${it.code}")
                }
            }
        }
        return builder.build()
    }
}
