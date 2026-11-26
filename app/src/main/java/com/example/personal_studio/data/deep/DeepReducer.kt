package com.example.personal_studio.data.deep

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

internal data class AgentEvent(val seq: Long, val type: String, val data: JsonObject)

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.field(key: String): String = string(key) ?: throw IOException("服务器响应缺少字段 $key")
private fun JsonObject.flag(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull == true

@Serializable
internal data class TextSegment(val message: String, val ordinal: String, val text: String)

@Serializable
internal data class Round(
    val userId: Long,
    val startedAt: Long,
    val model: String?,
    val turn: String? = null,
    val segments: List<TextSegment> = emptyList(),
    @Transient val steps: List<DeepStep> = emptyList(),
    val commands: Map<String, String> = emptyMap(),
    val question: JsonObject? = null,
    val note: String? = null,
    val error: String? = null,
    val outcome: String? = null,
    val endedAt: Long? = null,
    val savedAnswerId: Long? = null,
) {
    val text get() = segments.joinToString("\n\n") { it.text }

    fun answerText(): String? = when (outcome) {
        "completed" -> text
        "aborted" -> text.takeIf { it.isNotBlank() }?.plus("\n\n（已停止）")
        "failed" -> text.takeIf { it.isNotBlank() }?.plus("\n\n（未完成：$error）")
        else -> null
    }

    fun live(finished: Boolean) = DeepLive(
        userId, startedAt, if (finished) answerText() ?: text else text, steps, note,
        question?.toQuestion(), finished, savedAnswerId, error,
    )

    fun activity(): String {
        if (question != null) return "等待你回答"
        val step = steps.lastOrNull { it.running } ?: return "正在回答"
        return when (step.kind) {
            DeepStepKind.SEARCH -> "正在搜索"
            DeepStepKind.FETCH -> "正在读网页"
            DeepStepKind.RUN -> "正在${step.title}"
            DeepStepKind.READ -> "正在读取文件"
            DeepStepKind.WRITE -> "正在修改文件"
            DeepStepKind.OTHER -> "正在使用工具"
        }
    }
}

internal fun JsonObject.toQuestion() = DeepQuestion(
    field("formID"),
    (get("fields") as? JsonArray ?: throw IOException("服务器问题缺少字段")).map { item ->
        val f = item as? JsonObject ?: throw IOException("服务器问题字段格式有误")
        DeepQuestionField(
            f.field("key"), f.string("title"), f.string("description"),
            (f["options"] as? JsonArray).orEmpty().map {
                val o = it as? JsonObject ?: throw IOException("服务器问题选项格式有误")
                DeepQuestionOption(o.field("value"), o.field("label"), o.string("description"))
            }, f.string("type") == "multiselect", f.flag("custom"), f.flag("required"),
        )
    },
)

internal const val OUTPUT_LIMIT = 16 * 1024

private fun stepFor(call: String, name: String, input: JsonObject): DeepStep {
    val path = input.string("path")
    val file = path?.substringAfterLast('/')?.substringAfterLast('\\')
    val query = input.string("query")
    val command = input.string("command")
    val url = input.string("url")
    val patch = input.string("patchText")
    val kind = when (name) {
        "websearch", "glob", "grep" -> DeepStepKind.SEARCH
        "webfetch" -> DeepStepKind.FETCH
        "shell" -> DeepStepKind.RUN
        "read" -> DeepStepKind.READ
        "write", "edit", "patch" -> DeepStepKind.WRITE
        else -> DeepStepKind.OTHER
    }
    val title = when (name) {
        "websearch" -> query?.let { "搜索“$it”" } ?: "搜索"
        "webfetch" -> {
            val host = try { url?.let { URI(it).host } } catch (_: URISyntaxException) { null }
            host?.let { "读网页 · $it" } ?: "读网页"
        }
        "shell" -> if (command?.contains("python", ignoreCase = true) == true) "运行 Python" else "运行命令"
        "write" -> file?.let { "写入 $it" } ?: "写入文件"
        "read" -> file?.let { "读取 $it" } ?: "读取文件"
        "edit" -> file?.let { "修改 $it" } ?: "修改文件"
        "patch" -> patch?.lineSequence()?.firstOrNull { it.matches(Regex("""\*\*\* (Update|Add|Delete) File: .+""")) }
            ?.substringAfter(": ")?.substringAfterLast('/')?.let { "修改 $it" } ?: "修改文件"
        "glob", "grep" -> input.string("pattern")?.let { "搜索“$it”" } ?: "搜索文件"
        else -> "使用工具 · $name"
    }
    val detail = when (name) {
        "shell" -> command
        "webfetch" -> url
        // 搜索词已经在标题里（搜索“…”），不再重复成一个代码块。
        "websearch" -> null
        "write" -> listOfNotNull(path, input.string("content")).joinToString("\n").ifEmpty { null }
        "patch" -> patch
        "glob", "grep" -> listOfNotNull(path, input.string("pattern")).joinToString("\n").ifEmpty { null }
        else -> path
    }
    return DeepStep(call, kind, title, detail, null, true, null)
}

private fun Round.updateStep(id: String?, update: (DeepStep) -> DeepStep) =
    copy(steps = steps.map { if (it.id == id) update(it) else it })

private fun Round.shell() = steps.lastOrNull { it.running && it.kind == DeepStepKind.RUN }?.id

private fun Round.applyEvent(event: AgentEvent, now: Long): Round {
    val d = event.data
    val call = d.string("call")
    return when (event.type) {
        "text.delta", "text.end" -> {
            val message = d.field("messageID")
            val ordinal = d.field("ordinal")
            val index = segments.indexOfFirst { it.message == message && it.ordinal == ordinal }
            val part = d.field("text")
            if (index < 0) copy(segments = segments + TextSegment(message, ordinal, part))
            else {
                val previous = segments[index]
                val text = if (event.type == "text.end") part else previous.text + part
                copy(segments = segments.toMutableList().apply { set(index, previous.copy(text = text)) })
            }
        }
        "tool.start", "tool.call" -> {
            val step = stepFor(d.field("call"), d.field("name"), d["input"] as? JsonObject ?: JsonObject(emptyMap()))
            if (steps.none { it.id == step.id }) copy(steps = steps + step)
            else updateStep(call) { it.copy(title = step.title, detail = step.detail, kind = step.kind) }
        }
        "command.start" -> shell()?.let { copy(commands = commands + (d.field("commandID") to it)) } ?: this
        "command.output" -> updateStep(commands[d.field("commandID")] ?: shell()) { step ->
            step.copy(output = (step.output.orEmpty() + d.field("text")).take(OUTPUT_LIMIT))
        }
        "command.end" -> copy(commands = commands - d.field("commandID"))
        "tool.output" -> updateStep(call) { step ->
            val live = step.output.orEmpty()
            val final = d.field("text")
            val output = when {
                live.isEmpty() || live in final -> final
                final in live -> live
                else -> "$live\n$final"
            }
            step.copy(output = output.take(OUTPUT_LIMIT), running = false)
        }
        "tool.end" -> updateStep(call) {
            it.copy(running = false, success = d.flag("success"), output = it.output ?: d.string("message"))
        }
        "permission.denied" -> copy(steps = steps + DeepStep(
            "denied-${event.seq}", DeepStepKind.OTHER, "权限请求已拒绝", d.string("message"), null, false, false,
        ))
        "retry" -> copy(note = "连接中断，正在重试（第 ${d.field("attempt")} 次）")
        "notice" -> copy(note = d.string("message"))
        "question" -> { d.toQuestion(); copy(question = d) }
        "error" -> copy(error = d.field("message"))
        "turn.end" -> {
            val status = d.field("status")
            if (status !in setOf("completed", "failed", "aborted")) throw IOException("未知的轮次结束状态")
            copy(outcome = status, endedAt = now, question = null, commands = emptyMap(),
                steps = steps.map { if (it.running) it.copy(running = false) else it },
                error = if (status == "failed") error ?: "本轮执行失败" else null)
        }
        else -> this
    }
}

/** 游标和快照一同写入 Room。外轮事件只前移游标，终态之后的事件留给下轮续读。 */
internal fun DeepSession.reduce(event: AgentEvent, now: Long): DeepSession {
    if (phase != Phase.RUNNING || event.seq <= lastSeq) return this
    val current = checkNotNull(round)
    val row = copy(lastSeq = event.seq)
    val turn = event.data.string("turn") ?: return row
    if (current.turn != null && current.turn != turn) return row
    if (current.turn == null && event.type != "turn.start") return row
    val userId = if (current.turn == null) {
        event.data.string("clientMessageId")?.removePrefix("chat-")?.toLongOrNull() ?: current.userId
    } else current.userId
    val next = current.copy(userId = userId, turn = turn, note = null).applyEvent(event, now)
    return row.copy(
        round = next,
        phase = if (next.outcome == null) Phase.RUNNING else Phase.FINISHING,
        activity = if (next.outcome == null) next.activity() else null,
    )
}
