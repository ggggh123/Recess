package com.example.personal_studio.data.deep

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class GatewayError(val status: Int, val code: String?, message: String) : IOException(message) {
    val expired get() = status == 404 && code == "session_not_found"
    val busy get() = status == 409 && code == "turn_running"
}

private fun parseObject(text: String): JsonObject = try {
    Json.parseToJsonElement(text) as? JsonObject ?: throw IOException("服务器响应格式有误")
} catch (e: SerializationException) {
    throw IOException("服务器响应格式有误", e)
}

/** 只交付有完整空行结尾的帧，断开的半帧由上次提交的游标重取。 */
internal fun readEvent(source: BufferedSource): AgentEvent? {
    var id: Long? = null
    var type: String? = null
    val data = mutableListOf<String>()
    while (true) {
        val line = source.readUtf8Line() ?: return null
        if (line.isEmpty()) {
            if (data.isNotEmpty()) {
                return AgentEvent(id ?: throw IOException("事件缺少序号"),
                    type ?: throw IOException("事件缺少类型"), parseObject(data.joinToString("\n")))
            }
            id = null
            type = null
            continue
        }
        val value = line.substringAfter(':', "").removePrefix(" ")
        when (line.substringBefore(':')) {
            "id" -> id = value.toLongOrNull()
            "event" -> type = value
            "data" -> data += value
        }
    }
}

internal class DeepGateway(
    private val base: HttpUrl,
    private val key: suspend () -> String,
    private val client: OkHttpClient,
) {
    private val streaming = client.newBuilder()
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    private suspend fun request(path: String, method: String, body: JsonObject? = null): Request.Builder {
        val builder = Request.Builder().url(checkNotNull(base.resolve(path)))
        if (path != "status") builder.header("Authorization", "Bearer ${key()}")
        if (method != "GET") builder.header("X-Kejian", "1")
        val payload = if (method == "POST") {
            body?.toString().orEmpty().toRequestBody("application/json; charset=utf-8".toMediaType())
        } else null
        return builder.method(method, payload)
    }

    private fun Response.checkStatus() {
        if (isSuccessful) return
        val error = try { parseObject(body?.string().orEmpty()) } catch (_: IOException) { null }
        throw GatewayError(code, error?.string("code"), error?.string("message") ?: "服务器请求失败（HTTP $code）")
    }

    private suspend fun json(path: String, method: String = "GET", body: JsonObject? = null) = withContext(Dispatchers.IO) {
        client.newCall(request(path, method, body).build()).execute().use {
            it.checkStatus()
            parseObject(it.body?.string().orEmpty())
        }
    }

    suspend fun status(): DeepStatus {
        val reply = json("status")
        return if (reply["available"] == JsonPrimitive(true)) DeepStatus.Available(reply.string("model"), modelOptions(reply), reply.string("defaultEffort"))
        else DeepStatus.Unavailable(reply.string("reason") ?: "unavailable", reply.string("message") ?: "深度模式暂不可用")
    }

    suspend fun create() = json("sessions", "POST").field("id")
    suspend fun sessions(): List<String> {
        val items = json("sessions")["sessions"] as? JsonArray ?: throw IOException("服务器缺少会话列表")
        return items.map { (it as? JsonObject)?.field("id") ?: throw IOException("服务器会话编号无效") }
    }

    suspend fun send(id: String, userId: Long, text: String, images: List<String>, model: String? = null, effort: String? = null): String =
        json("sessions/$id/messages", "POST", buildJsonObject {
            put("clientMessageId", "chat-$userId")
            put("text", text)
            model?.let { put("model", it) }
            effort?.let { put("effort", it) }
            putJsonArray("images") {
                images.forEach { add(buildJsonObject { put("mime", "image/jpeg"); put("data", it) }) }
            }
        }).field("turn")

    suspend fun answer(id: String, question: DeepQuestion, answers: Map<String, List<String>>) {
        json("sessions/$id/answer", "POST", buildJsonObject {
            put("formID", question.formId)
            putJsonObject("answer") {
                for (field in question.fields) {
                    val values = answers[field.key] ?: continue
                    if (field.multiple) put(field.key, JsonArray(values.map(::JsonPrimitive)))
                    else values.singleOrNull()?.let { put(field.key, it) }
                }
            }
        })
    }

    suspend fun stop(id: String) { json("sessions/$id/abort", "POST") }
    suspend fun delete(id: String) { json("sessions/$id", "DELETE") }

    fun events(id: String, after: Long) = channelFlow {
        val req = request("sessions/$id/events?after=$after", "GET")
            .header("Accept", "text/event-stream")
            .header("Last-Event-ID", after.toString())
            .build()
        val call = streaming.newCall(req)
        val reader = launch(Dispatchers.IO) {
            try {
                call.execute().use { res ->
                    res.checkStatus()
                    val source = res.body?.source() ?: throw IOException("服务器事件流为空")
                    while (true) send(readEvent(source) ?: break)
                }
                close()
            } catch (e: IOException) {
                close(e)
            }
        }
        awaitClose { call.cancel(); reader.cancel() }
    }
}

/** 服务器给的模型清单；格式不对的项跳过，旧服务器没有这个字段时为空。 */
internal fun modelOptions(reply: JsonObject): List<DeepModelOption> =
    (reply["models"] as? JsonArray).orEmpty().mapNotNull { item ->
        val m = item as? JsonObject ?: return@mapNotNull null
        val id = m.string("id") ?: return@mapNotNull null
        DeepModelOption(
            id = id,
            name = m.string("name") ?: id,
            description = m.string("description").orEmpty(),
            efforts = (m["efforts"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            isDefault = (m["default"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }
