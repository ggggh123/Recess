package com.example.personal_studio.data.deep

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun event(seq: Long, type: String, data: String = "{}", turn: String = "t"): AgentEvent =
    AgentEvent(seq, type, JsonObject(Json.parseToJsonElement(data).jsonObject + ("turn" to JsonPrimitive(turn))))

internal fun running(id: Long = 1, user: Long = 2) =
    DeepSession(id, "remote", phase = Phase.RUNNING, round = Round(user, 1000, "model", "t"))

class DeepReducerTest {
    private var row = running()
    private fun accept(type: String, json: String = "{}") {
        row = row.reduce(event(row.lastSeq + 1, type, json), 3000)
    }
    private val round get() = row.round!!

    @Test fun segmentsKeepArrivalOrderAndEndCorrectsOnlyItsOwnPart() {
        accept("text.delta", """{"messageID":"m","ordinal":3,"text":"旧"}""")
        accept("text.delta", """{"messageID":"n","ordinal":3,"text":"另"}""")
        accept("text.delta", """{"messageID":"m","ordinal":0,"text":"末"}""")
        accept("text.delta", """{"messageID":"n","ordinal":3,"text":"一段"}""")
        accept("text.end", """{"messageID":"m","ordinal":3,"text":"校正"}""")
        assertEquals("校正\n\n另一段\n\n末", round.text)
        val restored = DeepConverters().run { round(roundJson(row.round)) }
        assertEquals(round, restored)
    }

    @Test fun commandChunksFollowTheirShellAndStayBounded() {
        accept("tool.start", """{"call":"a","name":"shell"}""")
        assertTrue(round.steps.single().running)
        accept("tool.call", """{"call":"a","name":"shell","input":{"command":"python -V"}}""")
        accept("command.start", """{"commandID":"1"}""")
        accept("tool.start", """{"call":"b","name":"shell"}""")
        accept("command.output", """{"commandID":"1","text":"out"}""")
        accept("command.output", """{"commandID":"1","text":"err"}""")
        assertEquals("outerr", round.steps.first().output)
        accept("command.output", """{"commandID":"1","text":"${"x".repeat(OUTPUT_LIMIT)}"}""")
        val step = round.steps.first()
        assertEquals(OUTPUT_LIMIT, step.output!!.length)
        assertEquals("运行 Python", step.title)
        assertEquals("python -V", step.detail)
        assertEquals(DeepStepKind.RUN, step.kind)
        assertNull(round.steps.last().output)
        accept("command.end", """{"commandID":"1"}""")
        accept("tool.output", """{"call":"a","name":"shell","text":"最终输出"}""")
        assertFalse(round.steps.first().running)
        accept("tool.end", """{"call":"a","success":false}""")
        assertEquals(false, round.steps.first().success)
        assertEquals(step.output, round.steps.first().output)
        accept("tool.output", """{"call":"b","name":"shell","text":"最终输出"}""")
        assertEquals("最终输出", round.steps.last().output)
    }

    @Test fun toolInputsBecomeChineseTitlesAndDetails() {
        fun tool(name: String, input: String, title: String, kind: DeepStepKind) {
            accept("tool.call", """{"call":"$name","name":"$name","input":$input}""")
            assertEquals(title, round.steps.last().title)
            assertEquals(kind, round.steps.last().kind)
        }
        tool("websearch", """{"query":"极限"}""", "搜索“极限”", DeepStepKind.SEARCH)
        tool("webfetch", """{"url":"https://example.org/page"}""", "读网页 · example.org", DeepStepKind.FETCH)
        tool("read", """{"path":"dir/x.py"}""", "读取 x.py", DeepStepKind.READ)
        tool("write", """{"path":"dir/x.py","content":"x=1"}""", "写入 x.py", DeepStepKind.WRITE)
        assertEquals("dir/x.py\nx=1", round.steps.last().detail)
        tool("edit", """{"path":"dir/x.py"}""", "修改 x.py", DeepStepKind.WRITE)
        tool("patch", """{"patchText":"*** Update File: a/x.py"}""", "修改 x.py", DeepStepKind.WRITE)
        tool("grep", """{"pattern":"lambda","path":"src"}""", "搜索“lambda”", DeepStepKind.SEARCH)
        tool("shell", """{"command":"ls"}""", "运行命令", DeepStepKind.RUN)
        accept("permission.denied")
        assertEquals(false, round.steps.last().success)
        assertEquals("权限请求已拒绝", round.steps.last().title)
    }

    @Test fun retriesClearAndQuestionPreservesFieldSchema() {
        accept("retry", """{"attempt":2}""")
        assertEquals("连接中断，正在重试（第 2 次）", round.note)
        accept("question", """{"formID":"f","fields":[{"key":"a","type":"string","title":"标题","description":"说明",
            "options":[{"value":"v","label":"标签","description":"选项说明"}],"required":true,"custom":true},
            {"key":"b","type":"multiselect","options":[]},{"key":"c","type":"string"}]}""")
        assertNull(round.note)
        val q = round.live(false).question!!
        assertEquals("f", q.formId)
        assertEquals(DeepQuestionField("a", "标题", "说明", listOf(DeepQuestionOption("v","标签","选项说明")),
            multiple = false, allowCustom = true, required = true), q.fields.first())
        assertTrue(q.fields[1].multiple)
        assertTrue(q.fields.last().options.isEmpty())
        assertEquals("等待你回答", row.activity)
        accept("notice", """{"message":"输出已截断"}""")
        assertEquals("输出已截断", round.note)
    }

    @Test fun outcomesHandleEmptyAndPartialAnswers() {
        for (outcome in listOf("completed", "aborted", "failed")) {
            for (body in listOf("", "回答")) {
                row = running()
                accept("text.end", """{"messageID":"m","ordinal":0,"text":"$body"}""")
                if (outcome == "failed") accept("error", """{"message":"额度已用完"}""")
                accept("turn.end", """{"status":"$outcome"}""")
                assertEquals(Phase.FINISHING, row.phase)
                assertEquals(3000L, round.endedAt)
                val expected = when {
                    outcome == "completed" -> body
                    body.isEmpty() -> null
                    outcome == "aborted" -> "回答\n\n（已停止）"
                    else -> "回答\n\n（未完成：额度已用完）"
                }
                assertEquals(expected, round.answerText())
                assertEquals(if (outcome == "failed") "额度已用完" else null, round.error)
            }
        }
    }

    @Test fun duplicateOldAndForeignEventsCannotChangeTheTurn() {
        val first = event(1, "text.delta", """{"messageID":"m","ordinal":0,"text":"答"}""")
        row = row.reduce(first, 20)
        assertSame(row, row.reduce(first, 30))
        row = row.reduce(event(2, "turn.end", """{"status":"failed"}""", "old"), 40)
        assertEquals(2L, row.lastSeq)
        assertSame(row, row.reduce(first, 50))
        assertEquals("答", round.text)
        assertEquals(Phase.RUNNING, row.phase)
    }
}
