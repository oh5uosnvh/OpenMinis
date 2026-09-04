package com.openminis.app.ui.settings.mods

import android.content.Context
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [FORK] Batch liveness check ("测活") for model entries.
 *
 * ## Relationship to the existing QuickTestSheet
 *
 * Upstream's `QuickTestSheet` already tests ONE model, thoroughly: up to three
 * modality-matched tests (text / image-gen / TTS / transcription) with rendered
 * results. It is the right tool for "why is this model misbehaving" and it is
 * left completely untouched — the per-row Quick Test affordance still opens it.
 *
 * This is the other question: "which of these 40 models does my relay actually
 * serve?" Relays routinely list models they cannot fulfil, and finding out
 * one-at-a-time through a results sheet is not viable at that scale. So this
 * runs ONE cheap text round-trip per model, concurrently, and reports a single
 * bit plus latency.
 *
 * ## Deliberate design choices
 *
 * - **Text only, `maxTokens = 1`.** The question is "does this id answer at
 *   all", not "is the answer good". An image-gen model that returns a 400 for a
 *   chat request is a real signal that it is not usable from the chat path,
 *   which is what the caller is curating.
 * - **Concurrency capped at [MAX_CONCURRENCY].** Firing 200 simultaneous
 *   requests at a relay is indistinguishable from an attack and reliably earns
 *   a 429 for every one of them, which would report the whole catalog as dead.
 * - **Per-model timeout.** A hung socket must not pin the batch; a model that
 *   does not answer within the window IS a failure for this purpose.
 * - **Cancellable.** The caller runs this in a coroutine it can cancel; results
 *   already collected stay on screen.
 */
object ForkModelHealth {

    private const val TAG = "ForkModelHealth"

    /** Simultaneous in-flight probes. Chosen to stay under typical relay rate limits. */
    private const val MAX_CONCURRENCY = 4

    /** Per-model wall clock budget. */
    private const val TIMEOUT_MS = 30_000L

    sealed class Status {
        object Pending : Status()
        object Running : Status()
        data class Alive(val latencyMs: Long) : Status()
        data class Dead(val message: String) : Status()
    }

    /**
     * Probe [entries] and report each result through [onResult] as it lands
     * (rather than returning a map at the end) so a 40-model run fills in
     * progressively instead of showing nothing for a minute.
     *
     * Callers must invoke this from a cancellable scope; cancelling stops
     * further probes but leaves delivered results untouched.
     */
    suspend fun probeAll(
        entries: List<ModelEntry>,
        providerRepository: ProviderRepository,
        context: Context,
        onResult: (entryId: String, status: Status) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val gate = Semaphore(MAX_CONCURRENCY)
        entries.map { entry ->
            async {
                gate.withPermit {
                    onResult(entry.id, Status.Running)
                    val status = probeOne(entry, providerRepository, context)
                    onResult(entry.id, status)
                }
            }
        }.awaitAll()
    }

    /** One model, one minimal text round-trip. */
    suspend fun probeOne(
        entry: ModelEntry,
        providerRepository: ProviderRepository,
        context: Context,
    ): Status = withContext(Dispatchers.IO) {
        val instance = providerRepository.instance(entry.providerInstanceId)
            ?: return@withContext Status.Dead("provider instance missing")

        // usableApiKey, NOT loadApiKey: a keyless local relay (ollama, LM Studio,
        // LiteLLM) legitimately stores nothing, and refusing to probe it here
        // would report "no key" for a provider that needs none — the same
        // pre-flight mistake upstream fixed in refreshModels and QuickTest.
        val apiKey = providerRepository.usableApiKey(instance)
            ?: return@withContext Status.Dead("no credential configured")

        val provider = runCatching {
            ProviderFactory.create(instance, apiKey, entry.model, context)
        }.getOrElse { return@withContext Status.Dead(it.message ?: "provider init failed") }

        val started = System.currentTimeMillis()
        val result = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching {
                provider.sendMessage(
                    messages = listOf(
                        LLMMessage(role = LLMMessage.Role.USER, content = "ping"),
                    ),
                    systemPrompt = null,
                    // 1 token: we only need the request to be ACCEPTED. Some
                    // endpoints refuse 0, and anything larger just burns quota
                    // across a whole catalog for no extra information.
                    maxTokens = 1,
                    temperature = null,
                )
            }
        }

        val elapsed = System.currentTimeMillis() - started
        return@withContext when {
            result == null -> Status.Dead("timeout after ${TIMEOUT_MS / 1000}s")
            result.isSuccess -> {
                AppLogger.info(TAG, "alive ${entry.model.id} in ${elapsed}ms")
                Status.Alive(elapsed)
            }
            else -> {
                val msg = result.exceptionOrNull()?.message?.take(160) ?: "request failed"
                AppLogger.info(TAG, "dead ${entry.model.id}: $msg")
                Status.Dead(msg)
            }
        }
    }
}
