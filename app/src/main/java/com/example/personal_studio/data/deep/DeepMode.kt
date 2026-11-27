package com.example.personal_studio.data.deep

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 深度模式：问答里的一种对话，题目交给课间服务器上的 Agent（opencode + 断网沙箱）处理。
 *
 * 界面只依赖这个接口。消息照常存在主数据库的问答表里（会话列表、知识库、标题生成都复用），
 * 深度对话特有的数据（服务器会话 ID、事件序号、过程步骤）存在单独的数据库里：主数据库用的是
 * 破坏性迁移，加表会清空用户数据。
 *
 * 服务端接口见官网仓库 agent/SPEC-2.md 与 README（/api/v1/agent/...）。
 */
interface DeepMode {

    /** 服务器上的深度模式能不能用。进入问答页时调 [refreshStatus] 刷新。 */
    val status: StateFlow<DeepStatus>

    suspend fun refreshStatus()

    /** 首次使用说明是否已经确认过（确认前不建深度对话）。 */
    val privacyAccepted: Flow<Boolean>

    suspend fun acceptPrivacy()

    /** 上次选的模型和推理等级；没选过为 null（用服务器的默认值）。 */
    val choice: Flow<DeepChoice?>

    suspend fun setChoice(choice: DeepChoice)

    /** 所有深度对话（按问答会话 ID），供列表页打标签、显示“进行中”。 */
    val conversations: StateFlow<Map<Long, DeepConversation>>

    /**
     * 新建一个本地的深度对话，返回问答会话 ID。此时不联系服务器：第一条消息发出时才建服务器会话，
     * 这样用户没发消息就退出时（问答会清掉空会话）不会在服务器上留下空会话。
     */
    suspend fun createConversation(): Long

    /** 这个会话正在进行（或刚结束、回答还没交接给界面）的一轮；空闲时为 null。 */
    fun live(chatSessionId: Long): StateFlow<DeepLive?>

    /** 已完成回答的过程步骤，按 AI 消息 ID。 */
    fun steps(chatSessionId: Long): Flow<Map<Long, List<DeepStep>>>

    /**
     * 发一条消息：先把用户消息存进问答表，再（必要时先建服务器会话）发给服务器，然后开始接收事件。
     * 图片是问答里已有的本地图片路径，发送前转成 JPEG（长边不超过 2048 像素），每条最多 [MAX_IMAGES] 张。
     */
    suspend fun send(chatSessionId: Long, text: String, imagePaths: List<String>, choice: DeepChoice? = null): DeepSendResult

    /** 中止当前一轮（服务器上正在跑的命令一并结束）。结果以 [DeepLive.finished] 体现。 */
    suspend fun stop(chatSessionId: Long)

    /** 回答 Agent 的提问。[answer] 的键是 [DeepQuestionField.key]，值是字符串或字符串列表。 */
    suspend fun answer(chatSessionId: Long, formId: String, answer: Map<String, List<String>>): Boolean

    /**
     * 对话页打开或回到前台时调用：有正在进行的一轮就（重新）连上事件流，从上次的序号续传。
     * 重复调用没有副作用。
     */
    fun resume(chatSessionId: Long)

    /** 界面已经把 [DeepLive.savedAnswerId] 交接完，可以清掉这一轮的 live 状态。 */
    fun acknowledge(chatSessionId: Long)

    /**
     * 对账：问答会话已被删除的深度对话，删掉服务器上的会话和本地记录；删除失败的下次再试。
     * App 启动、问答列表删除对话之后调用。
     */
    suspend fun reconcile()

    /** 设置里的“删除我在服务器上的全部深度对话”。本地记录保留，变为只读（已过期）。返回删除的数量。 */
    suspend fun deleteAllRemote(): Int

    companion object {
        const val MAX_IMAGES = 4
    }
}

sealed interface DeepStatus {
    data object Unknown : DeepStatus
    /**
     * [model] 是服务器的默认模型 ID，例如 gpt-6.2-sol；[models] 是开放给用户选的模型（默认的排第一），
     * [defaultEffort] 是默认推理等级。旧服务器不给清单时两者为空，界面不显示选择。
     */
    data class Available(
        val model: String?,
        val models: List<DeepModelOption> = emptyList(),
        val defaultEffort: String? = null,
    ) : DeepStatus
    /** [message] 是可以直接显示的中文说明（后台关闭、主进程未登录、连不上等）。 */
    data class Unavailable(val reason: String, val message: String) : DeepStatus
}

/**
 * 一个可选的模型：ID（提供方/模型，如 anthropic/claude-opus-5-5）、名字、一句话说明、开放的推理等级
 * （low、medium、high……，可能为空）和这个模型的默认档。都由后台设定。
 */
data class DeepModelOption(
    val id: String,
    val name: String,
    val description: String,
    val efforts: List<String>,
    val isDefault: Boolean,
    val defaultEffort: String? = null,
)

/** 同一个模型：完全相同，或者是旧版本存下的不带提供方的 ID（gpt-6.2-sol 对 openai/gpt-6.2-sol）。 */
fun DeepModelOption.matches(id: String?): Boolean =
    id != null && (this.id == id || (!id.contains('/') && this.id.substringAfter('/') == id))

/** 用户选的模型和推理等级，从下一条消息起生效。 */
data class DeepChoice(val model: String, val effort: String?)

/** 按服务器的清单核对：选过的还开放就用它，否则用默认模型和默认推理等级；没有清单时为 null。 */
fun DeepStatus.Available.effectiveChoice(saved: DeepChoice?): DeepChoice? {
    val default = models.firstOrNull { it.isDefault } ?: models.firstOrNull() ?: return null
    val model = models.firstOrNull { it.matches(saved?.model) } ?: default
    val effort = saved?.effort?.takeIf { it in model.efforts }
        ?: model.defaultEffort?.takeIf { it in model.efforts }
        ?: defaultEffort?.takeIf { it in model.efforts }
        ?: model.efforts.firstOrNull()
    return DeepChoice(model.id, effort)
}

data class DeepConversation(
    val chatSessionId: Long,
    /** 有一轮正在服务器上进行。 */
    val running: Boolean,
    /** 服务器上的会话已过期或被删除，只能看不能再发。 */
    val expired: Boolean,
    /** 正在做的事的一句话，例如“正在运行 Python”；空闲时为 null。 */
    val activity: String?,
)

sealed interface DeepSendResult {
    data class Sent(val userMessageId: Long) : DeepSendResult
    /** 没发出去。[message] 是中文说明；[userMessageId] 非空表示用户消息已存下（可以稍后重发）。 */
    data class Failed(val message: String, val userMessageId: Long?) : DeepSendResult
}

/** 正在进行的一轮。 */
data class DeepLive(
    val userMessageId: Long,
    val startedAt: Long,
    /** 已经收到的回答文字（各段按顺序拼接）。 */
    val text: String,
    val steps: List<DeepStep>,
    /** 临时提示，例如“连接中断，正在重试（第 2 次）”“正在重新连接”。 */
    val note: String?,
    val question: DeepQuestion?,
    /** 这一轮结束了（完成、中止或失败）。 */
    val finished: Boolean,
    /** 结束后回答存进问答表的消息 ID；界面交接完调 [DeepMode.acknowledge]。 */
    val savedAnswerId: Long?,
    /** 失败时的中文说明。 */
    val error: String?,
)

data class DeepStep(
    /** 服务器的工具调用 ID，同一轮内唯一。 */
    val id: String,
    val kind: DeepStepKind,
    /** 一句话标题，例如“搜索“洛必达 适用条件””“读网页 · math.stackexchange.com”“运行 Python”。 */
    val title: String,
    /** 细节：命令、代码、写入的文件内容等；没有则为 null。 */
    val detail: String?,
    /** 输出（截断后的），没有则为 null。 */
    val output: String?,
    val running: Boolean,
    val success: Boolean?,
)

enum class DeepStepKind { SEARCH, FETCH, RUN, WRITE, READ, OTHER }

data class DeepQuestion(
    val formId: String,
    val fields: List<DeepQuestionField>,
)

data class DeepQuestionField(
    val key: String,
    val title: String?,
    val description: String?,
    /** 可选项；为空表示自由填写。 */
    val options: List<DeepQuestionOption>,
    val multiple: Boolean,
    /** 有选项时是否还允许自己填写。 */
    val allowCustom: Boolean,
    val required: Boolean,
)

data class DeepQuestionOption(val value: String, val label: String, val description: String?)
