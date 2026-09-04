package com.openminis.app.ui.settings.mods

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.anthropic.AnthropicModelsApi
import com.openminis.app.provider.gemini.GeminiModelsApi
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.openrouter.OpenRouterModelsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [FORK] Read-only catalog probe for the "获取模型" flow.
 *
 * ## Why this exists rather than calling ProviderRepository.refreshModels
 *
 * `refreshModels` is a WRITE: it fetches and then calls `replaceEntries`,
 * which swaps the instance's whole entry list for whatever the endpoint
 * returned. That is the upstream "一键加载全部模型" behaviour this fork is
 * replacing — a relay that fronts 400 models leaves the picker unusable, and
 * the user never got a say.
 *
 * So the fetch is split from the commit:
 *
 *   1. [fetchCatalog] — pure read. Hits the same per-provider ModelsApi
 *      objects `refreshModels` uses (identical auth, base-URL and UA handling,
 *      so a relay that works there works here), returns the list, writes
 *      nothing.
 *   2. The user taps ＋ / － per model (or per family) in
 *      ProviderModelsFetchSheet.
 *   3. [addModels] / [removeModels] — commit ONLY those, batched, via
 *      `ProviderRepository.addEntries` / `removeEntries`. Adding is additive and
 *      dedupes on `baseModel.id`; removal cascade-cleans group membership and
 *      the agent-loop pin. Existing entries, their overrides and hidden flags
 *      are untouched.
 *
 * The upstream refresh path is left completely intact — this is additive. If
 * an upstream update changes how models are fetched, the only thing that can
 * break here is a signature in [fetchCatalog]'s `when`, which fails at COMPILE
 * time rather than silently drifting. See docs/FORK.md §3.
 */
object ProviderCatalogFetcher {

    private const val TAG = "ForkCatalogFetcher"

    sealed class FetchResult {
        data class Success(val models: List<LLMModel>) : FetchResult()
        data class Failure(val message: String) : FetchResult()
    }

    /**
     * Fetch the provider's model catalog WITHOUT touching persisted state.
     *
     * `forceRefresh = true` and `context = null` on every call: the disk cache
     * exists to keep app start off the network, but a user who explicitly
     * pressed 获取模型 wants the live list — serving them a 7-day-old cache is
     * how "my new model doesn't show up" bugs happen.
     */
    suspend fun fetchCatalog(
        instance: ProviderInstance,
        providerRepository: ProviderRepository,
    ): FetchResult = withContext(Dispatchers.IO) {
        // Same credential resolution as refreshModels: usableApiKey substitutes
        // "" for the legitimately keyless case (local relay + custom base URL),
        // and returns null when a key is genuinely required.
        val apiKey = providerRepository.usableApiKey(instance)
        val baseURL = instance.effectiveBaseURL

        // Codex OAuth cannot call /v1/models — upstream ships a static list for
        // exactly this path, so reuse it instead of failing.
        if (instance.providerType == ProviderType.openAI &&
            instance.credentialType == ProviderCredential.oauth &&
            instance.customBaseURL.isNullOrBlank()
        ) {
            val models = OpenAIModelsApi.fetchModelsOAuth()
            return@withContext if (models.isEmpty()) {
                FetchResult.Failure("Codex OAuth catalog unavailable")
            } else {
                FetchResult.Success(models)
            }
        }

        if (apiKey == null) {
            return@withContext FetchResult.Failure(
                "No credential for this provider. Add an API key (or sign in) first.",
            )
        }

        val models = try {
            when (instance.providerType) {
                ProviderType.anthropic -> AnthropicModelsApi.fetchModels(
                    apiKey,
                    baseURL,
                    isOAuth = instance.credentialType == ProviderCredential.oauth,
                    forceRefresh = true,
                    customUserAgent = instance.customUserAgent,
                )
                ProviderType.gemini -> GeminiModelsApi.fetchModels(
                    apiKey,
                    isOAuth = instance.credentialType == ProviderCredential.oauth,
                    forceRefresh = true,
                )
                ProviderType.openAI, ProviderType.openAIResponses ->
                    OpenAIModelsApi.fetchModels(
                        apiKey,
                        baseURL,
                        forceRefresh = true,
                        customUserAgent = instance.customUserAgent,
                    )
                ProviderType.openRouter -> OpenRouterModelsApi.fetchModels(
                    apiKey,
                    forceRefresh = true,
                )
                // xAI / Kimi speak the OpenAI shape. The `?:` default is
                // load-bearing, not cosmetic: an instance added through the
                // OAuth button carries no customBaseURL, so effectiveBaseURL is
                // null and OpenAIModelsApi would resolve api.openai.com —
                // sending an xAI token to OpenAI and listing GPT models under a
                // Grok provider. Same trap upstream documents in refreshModels.
                ProviderType.xAI -> OpenAIModelsApi.fetchModels(
                    apiKey,
                    baseURL ?: "https://api.x.ai/v1",
                    forceRefresh = true,
                    customUserAgent = instance.customUserAgent,
                ).ifEmpty { com.openminis.app.provider.xai.XAIModelsApi.fetchModelsOAuth() }
                ProviderType.kimiCode -> OpenAIModelsApi.fetchModels(
                    apiKey,
                    baseURL ?: "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1",
                    forceRefresh = true,
                    customUserAgent = instance.customUserAgent,
                )
                ProviderType.antigravity, ProviderType.unsupported -> emptyList()
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "fetchCatalog failed for ${instance.id}: ${e.message}")
            return@withContext FetchResult.Failure(e.message ?: "network error")
        }

        if (models.isNotEmpty()) {
            AppLogger.info(TAG, "fetchCatalog: ${models.size} models for ${instance.label}")
            return@withContext FetchResult.Success(models)
        }

        // Fall back to the models.dev registry by base URL, exactly as
        // refreshModels does — it covers many OpenAI-/Anthropic-compatible
        // gateways by hostname and costs nothing when there's no match.
        val fallback = ModelsDevApi.fetchModels(
            baseURL ?: when (instance.providerType) {
                ProviderType.anthropic -> "https://api.anthropic.com/v1"
                ProviderType.gemini -> "https://generativelanguage.googleapis.com"
                ProviderType.openRouter -> "https://openrouter.ai/api/v1"
                ProviderType.xAI -> "https://api.x.ai/v1"
                else -> "https://api.openai.com/v1"
            },
        )
        if (fallback.isNotEmpty()) {
            AppLogger.info(TAG, "fetchCatalog: models.dev fallback ${fallback.size} for ${instance.label}")
            FetchResult.Success(fallback)
        } else {
            FetchResult.Failure("The endpoint returned no models.")
        }
    }

    /**
     * Commit picks. Additive: [ProviderRepository.addEntries] skips any model
     * whose id already exists on this instance, so re-adding is a no-op rather
     * than a duplicate, and nothing already present is removed or reset.
     *
     * One batched write rather than N: the sheet's add-all can cover a 400-model
     * catalog, and 400 separate `addEntry` calls means 400 saveConfig round-trips
     * (DB + JSON mirror + a StateFlow emission that recomposes every reader).
     *
     * ## Why `isCustom = true` on models that came from the provider's catalog
     *
     * It looks wrong — these are not hand-typed models — but it is precisely
     * what protects the curation the user just performed, using upstream's own
     * mechanism rather than new state:
     *
     *  - `ProviderRepository.autoRefreshModels` SKIPS any instance that owns at
     *    least one custom entry ("so we never overwrite hand-edited entries").
     *    Without the flag, the daily `refreshAllModelsIfNeeded` sweep would call
     *    `refreshModels` → `replaceEntries` and swap the user's 5 picked models
     *    for the endpoint's full 400 — silently undoing the whole point of this
     *    feature, roughly 24 hours later. That failure mode is the worst kind:
     *    it looks like the app "randomly re-added everything".
     *  - `replaceEntries` also carries custom entries forward explicitly
     *    (`remainingCustom`), so even a refresh triggered from somewhere else
     *    cannot drop them.
     *
     * The cost is that models.dev metadata for these entries is frozen at fetch
     * time instead of being re-enriched daily. Acceptable: `fetchCatalog` already
     * ran the models.dev enrichment (it goes through the same ModelsApi objects),
     * and the user can re-fetch at any time. Losing curation is a real bug;
     * slightly stale context-window metadata is not.
     *
     * Returns how many entries were actually new.
     */
    fun addModels(
        instanceId: String,
        models: List<LLMModel>,
        providerRepository: ProviderRepository,
    ): Int {
        if (models.isEmpty()) return 0
        val existing = providerRepository.entriesFor(instanceId)
            .map { it.baseModel.id }
            .toSet()
        val fresh = models
            .filter { it.id !in existing }
            // A catalog can list the same id twice; dedupe before the write so
            // two rows can never appear for one model.
            .distinctBy { it.id }
            .map { model ->
                com.openminis.app.data.model.ModelEntry(
                    providerInstanceId = instanceId,
                    baseModel = model,
                    isCustom = true,
                )
            }
        if (fresh.isEmpty()) return 0
        val added = providerRepository.addEntries(fresh)
        AppLogger.info(TAG, "addModels: $added new of ${models.size} for $instanceId")
        return added
    }

    /**
     * Drop models from this instance by MODEL id (not entry id — the fetch sheet
     * only knows the catalog's ids).
     *
     * Batched for the same reason as [addModels], and it goes through
     * [ProviderRepository.removeEntries] so group membership and the agent-loop
     * pin are cascade-cleaned exactly as a single `removeEntry` would.
     *
     * Returns how many entries were actually removed.
     */
    fun removeModels(
        instanceId: String,
        modelIds: Collection<String>,
        providerRepository: ProviderRepository,
    ): Int {
        if (modelIds.isEmpty()) return 0
        val ids = modelIds.toSet()
        val entryIds = providerRepository.entriesFor(instanceId)
            .filter { it.baseModel.id in ids }
            .map { it.id }
        if (entryIds.isEmpty()) return 0
        val removed = providerRepository.removeEntries(entryIds)
        AppLogger.info(TAG, "removeModels: $removed of ${modelIds.size} for $instanceId")
        return removed
    }
}
