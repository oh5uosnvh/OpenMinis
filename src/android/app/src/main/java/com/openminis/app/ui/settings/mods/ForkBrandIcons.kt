package com.openminis.app.ui.settings.mods

import androidx.annotation.DrawableRes
import com.openminis.app.R

/**
 * [FORK] Brand icon resolver: model id (or provider name) → a vendor logo.
 *
 * Ported from kelivo's `BrandAssets` (lib/utils/brand_assets.dart) — same idea,
 * same ordered-regex approach, same fallback. The ARTWORK is not taken from
 * kelivo: kelivo is AGPL-3.0 and this fork is GPL-3.0, so its assets are not
 * ours to copy. The icons are generated from **lobehub/lobe-icons (MIT)**, which
 * is where that icon set originates, by `scripts/fork/fetch-brand-icons.sh`.
 *
 * ## Why match on the id and not the provider
 *
 * A relay serves models from many vendors under one provider entry: an OpenAI-
 * compatible gateway happily lists `claude-opus-5` next to `gemini-3-pro`. So
 * the icon has to come from the MODEL, with the provider name only as a
 * fallback for ids too opaque to classify (`model-3`, `default`).
 *
 * ## Rule order is load-bearing
 *
 * First match wins, so specific patterns must precede general ones:
 * `codex` before `gpt`, `gemma` before `gemini`'s g-family neighbours,
 * `moonshot`/`kimi` before a bare `k`. Adding a vendor means inserting the rule
 * at the right position, not appending it.
 *
 * Returns null when nothing matches; callers then draw the model's initial
 * letter, which is what kelivo does and degrades far better than a generic
 * placeholder glyph.
 */
object ForkBrandIcons {

    /**
     * True when the drawable is a single-colour silhouette that must be tinted
     * to the current theme's onSurface, rather than a full-colour logo drawn
     * as-is. A black OpenAI mark on a dark surface is invisible otherwise.
     */
    private val monochrome = setOf(
        R.drawable.fork_brand_openai,
        R.drawable.fork_brand_anthropic,
        R.drawable.fork_brand_xai,
        R.drawable.fork_brand_grok,
        R.drawable.fork_brand_ollama,
        R.drawable.fork_brand_openrouter,
        R.drawable.fork_brand_codex,
        R.drawable.fork_brand_moonshot,
        R.drawable.fork_brand_stepfun,
        R.drawable.fork_brand_flux,
        R.drawable.fork_brand_midjourney,
    )

    fun isMonochrome(@DrawableRes res: Int): Boolean = res in monochrome

    private data class Rule(val pattern: Regex, @DrawableRes val res: Int)

    private val rules: List<Rule> = listOf(
        // ── OpenAI family (codex before the generic gpt rule) ────────────
        Rule(Regex("codex"), R.drawable.fork_brand_codex),
        Rule(Regex("sora"), R.drawable.fork_brand_sora),
        Rule(Regex("openai|gpt|chatgpt|dall-?e|whisper|o[134](?![a-z])"), R.drawable.fork_brand_openai),

        // ── Anthropic ────────────────────────────────────────────────────
        Rule(Regex("claude"), R.drawable.fork_brand_claude),
        Rule(Regex("anthropic"), R.drawable.fork_brand_anthropic),

        // ── Google (gemma before gemini so it keeps its own mark) ─────────
        Rule(Regex("gemma"), R.drawable.fork_brand_gemma),
        Rule(Regex("gemini|imagen|veo"), R.drawable.fork_brand_gemini),
        Rule(Regex("google|palm|bard"), R.drawable.fork_brand_google),

        // ── xAI ──────────────────────────────────────────────────────────
        Rule(Regex("grok"), R.drawable.fork_brand_grok),
        Rule(Regex("xai"), R.drawable.fork_brand_xai),

        // ── China mainland vendors ───────────────────────────────────────
        Rule(Regex("deepseek"), R.drawable.fork_brand_deepseek),
        Rule(Regex("qwen|qwq|qvq|tongyi|通义"), R.drawable.fork_brand_qwen),
        Rule(Regex("dashscope|aliyun|阿里|百炼|bailian"), R.drawable.fork_brand_alibabacloud),
        Rule(Regex("kimi|moonshot|月之暗面"), R.drawable.fork_brand_kimi),
        Rule(Regex("glm|chatglm|zhipu|智谱"), R.drawable.fork_brand_zhipu),
        Rule(Regex("doubao|豆包"), R.drawable.fork_brand_doubao),
        Rule(Regex("volc|ark|火山|bytedance|字节"), R.drawable.fork_brand_bytedance),
        Rule(Regex("hunyuan|混元|tencent|腾讯"), R.drawable.fork_brand_hunyuan),
        Rule(Regex("ernie|wenxin|文心|baidu|百度"), R.drawable.fork_brand_wenxin),
        Rule(Regex("minimax|abab"), R.drawable.fork_brand_minimax),
        Rule(Regex("step-|stepfun|阶跃"), R.drawable.fork_brand_stepfun),
        Rule(Regex("spark|generalv|讯飞|星火"), R.drawable.fork_brand_spark),
        Rule(Regex("baichuan|百川"), R.drawable.fork_brand_baichuan),
        Rule(Regex("yi-|零一万物|01-ai|01ai"), R.drawable.fork_brand_yi),
        Rule(Regex("internlm|书生"), R.drawable.fork_brand_internlm),

        // ── Open weights / others ────────────────────────────────────────
        Rule(Regex("llama|(?<!o)meta(?!so)"), R.drawable.fork_brand_meta),
        Rule(Regex("mistral|mixtral|magistral|devstral|codestral"), R.drawable.fork_brand_mistral),
        Rule(Regex("command-|cohere|aya"), R.drawable.fork_brand_cohere),
        Rule(Regex("perplexity|sonar"), R.drawable.fork_brand_perplexity),
        Rule(Regex("nemotron|nvidia"), R.drawable.fork_brand_nvidia),
        Rule(Regex("phi-|microsoft|azure"), R.drawable.fork_brand_microsoft),
        Rule(Regex("flux"), R.drawable.fork_brand_flux),
        Rule(Regex("stable-?diffusion|sdxl|sd3|stability"), R.drawable.fork_brand_stability),
        Rule(Regex("midjourney|niji"), R.drawable.fork_brand_midjourney),
        Rule(Regex("ollama"), R.drawable.fork_brand_ollama),
        Rule(Regex("openrouter"), R.drawable.fork_brand_openrouter),
    )

    /** Resolve an icon for a model id (preferred) or provider label. */
    @DrawableRes
    fun forName(name: String?): Int? {
        if (name.isNullOrBlank()) return null
        val key = name.trim().lowercase()
        for (rule in rules) {
            if (rule.pattern.containsMatchIn(key)) return rule.res
        }
        return null
    }

    /**
     * Model icon with the provider label as a fallback — the resolution order
     * kelivo uses (`assetForName(modelId) ?? assetForName(providerKey)`).
     */
    @DrawableRes
    fun forModel(modelId: String?, displayName: String? = null, providerName: String? = null): Int? =
        forName(modelId) ?: forName(displayName) ?: forName(providerName)
}
