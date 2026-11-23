package com.example.personal_studio.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.personal_studio.ui.components.BlinkingCursor
import com.example.personal_studio.ui.theme.Tg

/**
 * A streamed answer while it is still arriving, drawn natively so it can grow every frame.
 * Sizes and spacing follow `assets/katex/render.html` (15 px text, line height 1.7, 10 px
 * between blocks, 14 / 6 px around headings), so the finished answer moving into the formula
 * view barely shifts. Formulas stay as written until then.
 */
@Composable
internal fun AnswerStreamText(text: String, showCursor: Boolean, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseAnswerBlocks(text) }
    Column(modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        blocks.forEachIndexed { i, block ->
            val gap = if (i == 0) 0.dp else gapBetween(blocks[i - 1], block)
            if (gap > 0.dp) Spacer(Modifier.height(gap))
            AnswerBlockView(block, cursor = showCursor && i == blocks.lastIndex && block.carriesCursor)
        }
        if (showCursor && (blocks.isEmpty() || !blocks.last().carriesCursor)) {
            Box(Modifier.height(26.dp)) { BlinkingCursor(Modifier.padding(top = 10.dp)) }
        }
    }
}

/** One block of a streamed answer. */
internal sealed interface AnswerBlock {
    data class Paragraph(val text: String) : AnswerBlock
    data class Heading(val level: Int, val text: String) : AnswerBlock
    data class Item(val marker: String, val depth: Int, val text: String) : AnswerBlock
    data class Quote(val text: String) : AnswerBlock
    data class Code(val text: String) : AnswerBlock
    data object Rule : AnswerBlock
}

private val AnswerBlock.carriesCursor: Boolean
    get() = this !is AnswerBlock.Code && this !is AnswerBlock.Rule

private val HEADING = Regex("""^(#{1,6})[ \t]+(.*?)(?:[ \t]+#+)?[ \t]*$""")
private val RULE = Regex("""^ {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$""")
private val ITEM = Regex("""^( *)([-*+]|\d{1,9}[.)])[ \t]+(.*)$""")
private val FENCE = Regex("""^[ \t]*(`{3,}|~{3,})""")

/**
 * Splits a (possibly unfinished) Markdown answer into blocks the way `MarkdownHtml.toHtml`
 * reads them: a single newline stays a line break inside a paragraph, an unclosed code fence
 * runs to the end, tables stay as text lines.
 */
internal fun parseAnswerBlocks(markdown: String): List<AnswerBlock> {
    val out = mutableListOf<AnswerBlock>()
    val paragraph = mutableListOf<String>()
    fun flush() {
        if (paragraph.isNotEmpty()) out += AnswerBlock.Paragraph(paragraph.joinToString("\n") { it.trim() })
        paragraph.clear()
    }
    var fence: String? = null
    val code = StringBuilder()
    var previousBlank = true
    for (raw in markdown.replace("\r\n", "\n").split('\n')) {
        val line = raw.replace("\t", "    ")
        if (fence != null) {
            if (line.trim().startsWith(fence)) {
                out += AnswerBlock.Code(code.toString())
                code.clear()
                fence = null
            } else {
                if (code.isNotEmpty()) code.append('\n')
                code.append(line)
            }
            continue
        }
        val trimmed = line.trim()
        val fenceOpen = FENCE.find(line)
        val heading = HEADING.matchEntire(trimmed)
        val item = ITEM.matchEntire(line)
        val last = out.lastOrNull()
        when {
            trimmed.isEmpty() -> flush()
            fenceOpen != null -> { flush(); fence = fenceOpen.groupValues[1] }
            heading != null -> { flush(); out += AnswerBlock.Heading(heading.groupValues[1].length, heading.groupValues[2]) }
            RULE.matches(line) -> { flush(); out += AnswerBlock.Rule }
            trimmed.startsWith(">") -> {
                flush()
                val quoted = trimmed.removePrefix(">").removePrefix(" ")
                if (!previousBlank && last is AnswerBlock.Quote) out[out.lastIndex] = AnswerBlock.Quote(last.text + "\n" + quoted)
                else out += AnswerBlock.Quote(quoted)
            }
            item != null -> {
                flush()
                val token = item.groupValues[2]
                val marker = if (token[0].isDigit()) token.dropLast(1) + "." else "•"
                out += AnswerBlock.Item(marker, (item.groupValues[1].length / 2).coerceAtMost(3), item.groupValues[3])
            }
            // Text right under an item, with no blank line between, continues that item.
            !previousBlank && paragraph.isEmpty() && last is AnswerBlock.Item ->
                out[out.lastIndex] = last.copy(text = last.text + "\n" + trimmed)
            else -> paragraph += line
        }
        previousBlank = trimmed.isEmpty()
    }
    if (fence != null) out += AnswerBlock.Code(code.toString())
    flush()
    return out
}

/** Vertical space between two blocks, after CSS margin collapsing in render.html. */
private fun gapBetween(before: AnswerBlock, block: AnswerBlock): Dp = when {
    block is AnswerBlock.Heading || block is AnswerBlock.Rule || before is AnswerBlock.Rule -> 14.dp
    before is AnswerBlock.Heading -> 6.dp
    before is AnswerBlock.Item && block is AnswerBlock.Item -> 2.dp
    else -> 10.dp
}

private val BODY = TextStyle(fontSize = 15.sp, lineHeight = 25.5.sp)
private const val CURSOR_ID = "cursor"

@Composable
private fun AnswerBlockView(block: AnswerBlock, cursor: Boolean) {
    val c = Tg.colors
    val cursorContent = remember {
        mapOf(
            CURSOR_ID to InlineTextContent(Placeholder(18.sp, 14.sp, PlaceholderVerticalAlign.TextCenter)) { BlinkingCursor() },
        )
    }
    fun styled(text: String): AnnotatedString = buildAnnotatedString {
        appendInline(text, codeBackground = c.surfaceRaised, linkColor = c.accent, mutedColor = c.textSecondary)
        if (cursor) appendInlineContent(CURSOR_ID, "▍")
    }
    when (block) {
        is AnswerBlock.Paragraph -> Text(styled(block.text), style = BODY, color = c.text, inlineContent = cursorContent)
        is AnswerBlock.Heading -> {
            val size = when (block.level) { 1 -> 18.sp; 2 -> 17.sp; else -> 15.sp }
            Text(
                styled(block.text),
                style = TextStyle(fontSize = size, lineHeight = size * 1.4f, fontWeight = FontWeight.SemiBold),
                color = c.text,
                inlineContent = cursorContent,
            )
        }
        is AnswerBlock.Item -> Row(Modifier.padding(start = (block.depth * 21).dp)) {
            Text(block.marker, style = BODY, color = c.text, modifier = Modifier.widthIn(min = 21.dp).padding(end = 4.dp))
            Text(styled(block.text), style = BODY, color = c.text, inlineContent = cursorContent)
        }
        is AnswerBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(c.line))
            Spacer(Modifier.width(12.dp))
            Text(styled(block.text), style = BODY, color = c.textSecondary, inlineContent = cursorContent)
        }
        is AnswerBlock.Code -> {
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, c.line, shape)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    block.text,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.5.sp),
                    color = c.text,
                    softWrap = false,
                )
            }
        }
        AnswerBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
}

private val INLINE_CODE = Regex("""`([^`\n]+)`""")
private val MATH = Regex("""\$\$[\s\S]*?\$\$|\\\[[\s\S]*?\\\]|\$[^$\n]+?\$|\\\([\s\S]*?\\\)""")
private val BOLD = Regex("""\*\*(.+?)\*\*""")
private val ITALIC = Regex("""(?<![*\w])\*(?![\s*])(.+?)(?<![\s*])\*(?![*\w])""")
private val STRIKE = Regex("""~~(.+?)~~""")
private val LINK = Regex("""\[([^\]\n]+)]\(([^)\s]+)\)""")

/**
 * Inline Markdown as `MarkdownHtml` renders it: code and formulas are taken literally first
 * (no emphasis inside `$a*b$`), then links, bold, italic and strikethrough. Unclosed markers
 * stay as typed, which is what a half-received answer needs.
 */
internal fun AnnotatedString.Builder.appendInline(
    text: String,
    codeBackground: Color = Color.Unspecified,
    linkColor: Color = Color.Unspecified,
    mutedColor: Color = Color.Unspecified,
) {
    var i = 0
    while (i < text.length) {
        val code = INLINE_CODE.find(text, i)
        val math = MATH.find(text, i)
        val next = listOfNotNull(code, math).minByOrNull { it.range.first }
        val end = next?.range?.first ?: text.length
        appendEmphasis(text.substring(i, end), linkColor, mutedColor)
        if (next == null) break
        if (next === code) {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, background = codeBackground)) {
                append(next.groupValues[1])
            }
        } else {
            append(next.value)
        }
        i = next.range.last + 1
    }
}

private fun AnnotatedString.Builder.appendEmphasis(text: String, linkColor: Color, mutedColor: Color) {
    var i = 0
    while (i < text.length) {
        val found = listOf(LINK, BOLD, ITALIC, STRIKE).mapNotNull { r -> r.find(text, i)?.let { r to it } }
            .minByOrNull { it.second.range.first }
        if (found == null) { append(text.substring(i)); return }
        val (rule, m) = found
        append(text.substring(i, m.range.first))
        val inner = m.groupValues[1]
        when (rule) {
            LINK -> withStyle(SpanStyle(color = linkColor)) { append(inner) }
            BOLD -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendEmphasis(inner, linkColor, mutedColor) }
            ITALIC -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendEmphasis(inner, linkColor, mutedColor) }
            else -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = mutedColor)) {
                appendEmphasis(inner, linkColor, mutedColor)
            }
        }
        i = m.range.last + 1
    }
}
