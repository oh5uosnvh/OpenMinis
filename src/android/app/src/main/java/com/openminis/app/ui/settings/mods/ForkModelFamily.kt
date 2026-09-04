package com.openminis.app.ui.settings.mods

import com.openminis.app.data.model.LLMModel

/**
 * [FORK] Groups a flat model catalog into vendor / family sections, the way
 * kelive's 获取模型 screen does (Claude Opus, GPT, Gemini …).
 *
 * ## Why classify by id rather than trusting the API
 *
 * A relay's `/v1/models` says nothing useful about grouping: `provider` is
 * whatever the gateway feels like reporting (very often just "Custom"), and the
 * only reliable signal is the model id itself. So the family is derived from the
 * id, with the ordered rule table below.
 *
 * The rules are ORDERED and first-match-wins, which matters for real ids:
 * `claude-opus-5` must be tested before a generic `claude` rule, and
 * `gpt-image-2` before `gpt-`, or an image generator lands in the chat-model
 * section. Adding a rule means putting it in the right position, not appending.
 *
 * Anything unmatched falls into [OTHER], which sorts last — deliberately a
 * visible bucket rather than silently hiding a model the user asked for.
 */
object ForkModelFamily {

    /** One family bucket. [order] drives section order; lower shows first. */
    data class Family(val key: String, val displayName: String, val order: Int)

    private val OTHER = Family("other", "其他", 9_000)

    /**
     * id-substring → family. Checked in declaration order, so put the more
     * specific pattern first.
     *
     * Substrings are matched against the LOWERCASED id, and also against the
     * lowercased display name as a fallback — some relays serve opaque ids
     * (`model-3`) but a readable name.
     */
    private data class Rule(val match: List<String>, val family: Family)

    private val rules: List<Rule> = listOf(
        // ── Anthropic ────────────────────────────────────────────────────
        Rule(listOf("claude-opus", "opus-4", "opus-5"), Family("claude-opus", "Claude Opus", 100)),
        Rule(listOf("claude-sonnet", "sonnet-4", "sonnet-5"), Family("claude-sonnet", "Claude Sonnet", 101)),
        Rule(listOf("claude-haiku", "haiku-4", "haiku-5"), Family("claude-haiku", "Claude Haiku", 102)),
        Rule(listOf("claude", "anthropic"), Family("claude", "Claude", 103)),

        // ── OpenAI ───────────────────────────────────────────────────────
        // Image / audio generators first: they are gpt-* too, but grouping them
        // with the chat models makes the chat section lie about its contents.
        Rule(listOf("gpt-image", "dall-e", "dalle"), Family("openai-image", "OpenAI 图像", 200)),
        Rule(listOf("whisper", "gpt-4o-transcribe", "-transcribe"), Family("openai-audio-in", "OpenAI 语音识别", 201)),
        Rule(listOf("tts-1", "gpt-4o-mini-tts", "-tts"), Family("openai-audio-out", "OpenAI 语音合成", 202)),
        Rule(listOf("codex"), Family("codex", "Codex", 203)),
        Rule(listOf("gpt-5", "gpt5"), Family("gpt-5", "GPT-5", 204)),
        Rule(listOf("gpt-4", "gpt4"), Family("gpt-4", "GPT-4", 205)),
        Rule(listOf("gpt-3", "gpt3"), Family("gpt-3", "GPT-3", 206)),
        Rule(listOf("o1", "o3", "o4-"), Family("openai-o", "OpenAI o 系列", 207)),
        Rule(listOf("gpt-", "chatgpt"), Family("gpt", "GPT", 208)),

        // ── Google ───────────────────────────────────────────────────────
        Rule(listOf("gemini-3", "gemini-2.5", "gemini-2"), Family("gemini", "Gemini", 300)),
        Rule(listOf("gemini", "imagen", "veo"), Family("google", "Google", 301)),
        Rule(listOf("gemma"), Family("gemma", "Gemma", 302)),

        // ── xAI ──────────────────────────────────────────────────────────
        Rule(listOf("grok"), Family("grok", "Grok", 400)),

        // ── China mainland vendors ───────────────────────────────────────
        Rule(listOf("deepseek"), Family("deepseek", "DeepSeek", 500)),
        Rule(listOf("qwen", "qwq", "tongyi"), Family("qwen", "Qwen 通义", 501)),
        Rule(listOf("kimi", "moonshot"), Family("kimi", "Kimi", 502)),
        Rule(listOf("glm", "chatglm", "zhipu"), Family("glm", "GLM 智谱", 503)),
        Rule(listOf("doubao", "ep-", "seed-"), Family("doubao", "豆包", 504)),
        Rule(listOf("hunyuan"), Family("hunyuan", "混元", 505)),
        Rule(listOf("ernie", "wenxin"), Family("ernie", "文心", 506)),
        Rule(listOf("minimax", "abab"), Family("minimax", "MiniMax", 507)),
        Rule(listOf("step-"), Family("step", "阶跃 Step", 508)),
        Rule(listOf("spark", "generalv"), Family("spark", "讯飞星火", 509)),
        Rule(listOf("yi-"), Family("yi", "零一万物 Yi", 510)),
        Rule(listOf("baichuan"), Family("baichuan", "百川", 511)),
        Rule(listOf("mimo"), Family("mimo", "MiMo", 512)),

        // ── Open weights / others ────────────────────────────────────────
        Rule(listOf("llama"), Family("llama", "Llama", 600)),
        Rule(listOf("mistral", "mixtral", "magistral", "devstral"), Family("mistral", "Mistral", 601)),
        Rule(listOf("command-r", "command-a", "cohere"), Family("cohere", "Cohere", 602)),
        Rule(listOf("phi-"), Family("phi", "Phi", 603)),
        Rule(listOf("nemotron", "nvidia"), Family("nvidia", "NVIDIA", 604)),
        Rule(listOf("perplexity", "sonar"), Family("perplexity", "Perplexity", 605)),
        Rule(listOf("embed", "bge-", "text-embedding"), Family("embedding", "向量模型", 700)),
        Rule(listOf("rerank"), Family("rerank", "重排模型", 701)),
        Rule(listOf("flux", "stable-diffusion", "sd3", "midjourney"), Family("image-gen", "图像生成", 702)),
    )

    /** Classify one model. Never returns null — unmatched ids land in 其他. */
    fun of(model: LLMModel): Family {
        val id = model.id.lowercase()
        val name = model.displayName.lowercase()
        for (rule in rules) {
            if (rule.match.any { id.contains(it) || name.contains(it) }) return rule.family
        }
        return OTHER
    }

    /**
     * Group a catalog into ordered sections.
     *
     * Duplicate ids are collapsed first: a relay that aggregates several
     * upstreams sometimes lists the same id more than once, and a duplicate
     * reaching a LazyColumn key is a hard crash, not a cosmetic glitch.
     *
     * Within a section models keep the order the API returned them in — that
     * order is usually meaningful (newest first for most vendors) and
     * re-sorting alphabetically would put `claude-haiku` above `claude-opus`.
     */
    fun group(models: List<LLMModel>): List<Pair<Family, List<LLMModel>>> =
        models.distinctBy { it.id }
            .groupBy { of(it) }
            .toList()
            .sortedWith(compareBy({ it.first.order }, { it.first.displayName }))
}
