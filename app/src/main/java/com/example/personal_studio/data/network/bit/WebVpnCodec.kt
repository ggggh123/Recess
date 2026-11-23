package com.example.personal_studio.data.network.bit

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The school's WebVPN (wengine) writes the target host into the path: `/<scheme>/<iv><host>/…`,
 * where `<iv>` is the hex of the fixed key and `<host>` the hex of the host name encrypted with
 * AES-CFB under that key. Encoding is how [BitUrlsConfig]'s prefixes were made; decoding tells
 * which school system a gateway URL stands for.
 */
object WebVpnCodec {
    private val KEY = "wrdvpnisthebest!".toByteArray(Charsets.US_ASCII)
    val IV_HEX: String = KEY.toHex()

    fun encodeHost(host: String): String = IV_HEX + cipher(Cipher.ENCRYPT_MODE, host.toByteArray(Charsets.US_ASCII)).toHex()

    /** The host behind one path segment, or null when the segment is not an encoded host. */
    fun decodeHost(segment: String): String? {
        if (!segment.startsWith(IV_HEX)) return null
        val hex = segment.substring(IV_HEX.length)
        if (hex.isEmpty() || hex.length % 2 != 0 || hex.any { it !in HEX_DIGITS }) return null
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val host = cipher(Cipher.DECRYPT_MODE, bytes).toString(Charsets.US_ASCII)
        return host.takeIf { h -> h.isNotEmpty() && h.all { it.isLetterOrDigit() || it == '.' || it == '-' } }
    }

    /** The URL a gateway URL stands for (`https://gateway/https/<hex>/p?q` → `https://host/p?q`); null otherwise. */
    fun original(url: HttpUrl, gatewayHost: String): HttpUrl? {
        if (url.host != gatewayHost) return null
        val segments = url.pathSegments
        if (segments.size < 2) return null
        val scheme = segments[0].substringBefore('-').takeIf { it == "http" || it == "https" } ?: return null
        val port = segments[0].substringAfter('-', "").toIntOrNull()
        val host = decodeHost(segments[1]) ?: return null
        val rest = url.encodedPath.split('/').drop(3).joinToString("/")
        val base = "$scheme://$host${port?.let { ":$it" } ?: ""}/$rest".toHttpUrlOrNull() ?: return null
        return base.newBuilder().encodedQuery(url.encodedQuery).encodedFragment(url.encodedFragment).build()
    }

    private fun cipher(mode: Int, input: ByteArray): ByteArray =
        Cipher.getInstance("AES/CFB/NoPadding").run {
            init(mode, SecretKeySpec(KEY, "AES"), IvParameterSpec(KEY))
            doFinal(input)
        }

    private const val HEX_DIGITS = "0123456789abcdef"

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
