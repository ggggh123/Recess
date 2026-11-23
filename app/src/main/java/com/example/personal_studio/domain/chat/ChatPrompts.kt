package com.example.personal_studio.domain.chat

/**
 * 问答的系统提示词：每次请求开头发给模型、规定回答方式的那段话。用户可以在“设置 → AI 模型 →
 * 系统提示词”里改写；没改过（或清空）就用 [DEFAULT]。整理知识卡片和学情报告各有固定格式的提示词，
 * 输出要被程序读取，不跟着这里变。
 */
object ChatPrompts {
    const val MAX_LENGTH = 4000

    const val DEFAULT = """你是“课间”App 里的学习助手。
回答规则：
1. 若用户附了图片，优先基于图片内容作答。
2. 数学公式用 LaTeX 包裹：行内 ${'$'}...${'$'}，块级 ${'$'}${'$'}...${'$'}${'$'}。
3. 中文为主，准确、简洁、可追问。不要寒暄、不要前置声明。
4. 若需要更多信息，直接问。
5. 结尾不要加感叹号、表情或“祝你学习愉快”之类的客套。"""

    /** 这次请求实际使用的提示词：自定义的（去掉首尾空白后非空），否则默认。 */
    fun effective(custom: String?): String = normalize(custom) ?: DEFAULT

    /** 要保存的值：空白或与默认相同都存成 null（表示用默认，以后默认更新时会跟着更新）。 */
    fun normalize(custom: String?): String? =
        custom?.replace("\r\n", "\n")?.trim()?.take(MAX_LENGTH)?.takeIf { it.isNotEmpty() && it != DEFAULT }
}
