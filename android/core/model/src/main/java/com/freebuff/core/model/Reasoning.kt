package com.freebuff.core.model

/**
 * 思维链(推理)接入策略。
 *
 * 各家「让模型先想再答」的开关字段并不统一,App 端按模型名识别参数族,只送该族认得的字段 ——
 * 对不认识的模型一律不送,避免严格端点因未知字段直接 400。无论是否送参数,
 * 响应里的 `reasoning_content` / `reasoning` / `thinking` 增量都会被解析并展示(很多模型默认就会思考)。
 *
 * 字段依据(事实标准,见 docs/agent-architecture.md 附录):
 * - `reasoning_content`:DeepSeek / Qwen / GLM / Kimi / vLLM / LiteLLM 等的事实标准字段
 * - `reasoning_effort` + `max_completion_tokens`:OpenAI 推理模型(o 系列 / GPT-5 / gpt-oss)
 * - `enable_thinking`:Qwen3 及 vLLM/SGLang 的 chat template 开关
 * - `thinking: {type: enabled, budget_tokens}`:Anthropic 兼容网关与智谱 GLM
 */
enum class ReasoningFlavor {
    /** 模型默认就会思考,无需任何参数(deepseek-reasoner / QwQ / Kimi Thinking)。 */
    DEFAULT_ON,

    /** OpenAI 系:reasoning_effort 调档,并把补全预算调高(思考 token 记在这份预算里)。 */
    EFFORT,

    /** Qwen3 / vLLM 系:enable_thinking 开关。 */
    ENABLE_THINKING,

    /** Anthropic 兼容 / 智谱 GLM:thinking 块(带预算)。 */
    THINKING_BUDGET,

    /** 不认识该模型:不送参数(仅解析展示)。 */
    UNKNOWN,
}

/** 请求侧要写入的思考参数(null = 一个字段都不加)。 */
data class ReasoningPlan(
    val flavor: ReasoningFlavor,
    /** OpenAI 系:补全预算上限(含思考 token),0 表示不写。 */
    val maxCompletionTokens: Int = 0,
)

/** 「深度思考」设置的三态取值(存入 settings 表)。 */
object Reasoning {
    const val MODE_OFF = "off"
    const val MODE_AUTO = "auto"
    const val MODE_ON = "on"

    /** 思考详略档位:中等兼顾速度与深度(用户可后续做成可选)。 */
    const val EFFORT = "medium"

    /** thinking 块的思考预算(Anthropic 兼容 / GLM 接受该字段)。 */
    const val BUDGET_TOKENS = 8192

    /**
     * 推理模型的补全预算:思考 token 与正文共享这份额度,不显式抬高时,
     * 端点默认值(常见 4k)会被思考吃光 —— 表现就是「想完只吐几句话」。
     */
    const val MAX_COMPLETION_TOKENS = 16384

    /** OpenAI 推理模型族(含 gpt-oss / codex 变体)。 */
    private val OPENAI_EFFORT = Regex("(?:^|[/_.\\-])(?:o[0-9]|gpt-[5-9]|gpt-oss|codex)(?:$|[/_.\\-])")

    /** 按模型名判断参数族。 */
    fun flavorOf(modelId: String): ReasoningFlavor {
        val id = modelId.trim().lowercase()
        if (id.isBlank()) return ReasoningFlavor.UNKNOWN
        return when {
            OPENAI_EFFORT.containsMatchIn(id) -> ReasoningFlavor.EFFORT
            id.contains("claude") -> ReasoningFlavor.THINKING_BUDGET
            id.contains("glm") &&
                (id.contains("4.5") || id.contains("4.6") || id.contains("4.7") || id.contains("think")) ->
                ReasoningFlavor.THINKING_BUDGET
            id.contains("qwen") && (id.contains("3") || id.contains("think")) -> ReasoningFlavor.ENABLE_THINKING
            id.contains("deepseek") &&
                (id.contains("reasoner") || id.contains("r1") || id.contains("think")) ->
                ReasoningFlavor.DEFAULT_ON
            id.contains("qwq") -> ReasoningFlavor.DEFAULT_ON
            id.contains("kimi") && id.contains("think") -> ReasoningFlavor.DEFAULT_ON
            else -> ReasoningFlavor.UNKNOWN
        }
    }

    /**
     * 生成请求参数。
     * @param mode [MODE_OFF] 不思考(也不展示);[MODE_AUTO] 按模型识别;[MODE_ON] 强制开启
     *   —— 不认识的模型按最常见的 enable_thinking 试一次(用户显式选择,失败会直接报错,不会静默降级)
     */
    fun plan(mode: String, modelId: String): ReasoningPlan? {
        if (mode == MODE_OFF) return null
        val flavor = flavorOf(modelId)
        val effective = when {
            flavor != ReasoningFlavor.UNKNOWN -> flavor
            mode == MODE_ON -> ReasoningFlavor.ENABLE_THINKING
            else -> return null
        }
        return ReasoningPlan(
            flavor = effective,
            maxCompletionTokens = if (effective == ReasoningFlavor.EFFORT) MAX_COMPLETION_TOKENS else 0,
        )
    }
}
