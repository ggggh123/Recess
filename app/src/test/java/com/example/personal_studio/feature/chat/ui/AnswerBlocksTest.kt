package com.example.personal_studio.feature.chat.ui

import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.example.personal_studio.feature.chat.ui.AnswerBlock.Code
import com.example.personal_studio.feature.chat.ui.AnswerBlock.Heading
import com.example.personal_studio.feature.chat.ui.AnswerBlock.Item
import com.example.personal_studio.feature.chat.ui.AnswerBlock.Paragraph
import com.example.personal_studio.feature.chat.ui.AnswerBlock.Quote
import com.example.personal_studio.feature.chat.ui.AnswerBlock.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerBlocksTest {

    @Test fun `paragraphs keep single line breaks and split on blank lines`() {
        assertEquals(
            listOf(Paragraph("第一行\n第二行"), Paragraph("下一段")),
            parseAnswerBlocks("第一行\n第二行\n\n下一段"),
        )
    }

    @Test fun `headings lists quotes and rules`() {
        val md = """
            ## 思路
            - 先求导
            - 再代入
              1. 细节
            > 注意定义域
            > 以及端点
            ---
            结论
        """.trimIndent()
        assertEquals(
            listOf(
                Heading(2, "思路"),
                Item("•", 0, "先求导"),
                Item("•", 0, "再代入"),
                Item("1.", 1, "细节"),
                Quote("注意定义域\n以及端点"),
                Rule,
                Paragraph("结论"),
            ),
            parseAnswerBlocks(md),
        )
    }

    @Test fun `text right under an item continues it`() {
        assertEquals(listOf(Item("•", 0, "第一点\n补充说明")), parseAnswerBlocks("- 第一点\n补充说明"))
        assertEquals(listOf(Item("•", 0, "第一点"), Paragraph("新段落")), parseAnswerBlocks("- 第一点\n\n新段落"))
    }

    @Test fun `an unfinished code fence runs to the end while streaming`() {
        assertEquals(
            listOf(Paragraph("代码："), Code("val x = 1\nval y")),
            parseAnswerBlocks("代码：\n```kotlin\nval x = 1\nval y"),
        )
        assertEquals(
            listOf(Code("# 不是标题"), Paragraph("之后")),
            parseAnswerBlocks("```\n# 不是标题\n```\n之后"),
        )
    }

    @Test fun `half-received text parses without losing characters`() {
        val md = "设 \$f(x)=x^2\$，则 **导数为 \$2x"
        val blocks = parseAnswerBlocks(md)
        assertEquals(listOf(Paragraph(md)), blocks)
        val shown = buildAnnotatedString { appendInline((blocks[0] as Paragraph).text) }
        assertEquals(md, shown.text) // the unclosed ** stays as typed
    }

    @Test fun `emphasis is applied outside formulas and code only`() {
        val s = buildAnnotatedString { appendInline("**重点** 与 *斜体*，公式 \$a*b*c\$ 和 `x*y*z`") }
        assertEquals("重点 与 斜体，公式 \$a*b*c\$ 和 x*y*z", s.text)
        assertTrue(s.spanStyles.any { it.item.fontWeight == FontWeight.SemiBold && s.text.substring(it.start, it.end) == "重点" })
        assertTrue(s.spanStyles.any { it.item.fontStyle == FontStyle.Italic && s.text.substring(it.start, it.end) == "斜体" })
        assertTrue(s.spanStyles.none { it.item.fontStyle == FontStyle.Italic && "b" in s.text.substring(it.start, it.end) })
    }

    @Test fun `links show their text`() {
        assertEquals("见 课本 第三章", buildAnnotatedString { appendInline("见 [课本](https://example.com) 第三章") }.text)
    }
}
