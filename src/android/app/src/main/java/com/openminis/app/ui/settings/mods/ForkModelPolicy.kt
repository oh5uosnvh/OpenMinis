package com.openminis.app.ui.settings.mods

import android.content.Context

/**
 * [FORK] Per-instance "the user manages this model list by hand" flag.
 *
 * ## The leak this plugs
 *
 * The fork removed the automatic full-catalog fetch when a provider is created,
 * and added the pick-what-you-want 获取模型 sheet instead. That alone is not
 * enough: `ProviderRepository.refreshAllModelsIfNeeded` runs once per calendar
 * day from `MinisApp.onCreate` and calls `refreshModels` → `replaceEntries`,
 * which REPLACES the instance's list with everything the endpoint returns.
 *
 * So without this flag, a user who adds a relay fronting 400 models, curates 6
 * of them, and reopens the app the next day finds all 400 back. That reads as
 * "the app randomly re-added everything" — the worst failure mode, because it
 * happens hours later with no visible cause.
 *
 * Upstream already has a skip for exactly this class of problem
 * (`autoRefreshModels` bails when the instance owns custom entries, "so we never
 * overwrite hand-edited entries"). This extends the same idea to instances the
 * fork created, including ones the user has not curated yet.
 *
 * ## Why SharedPreferences and not a ProviderInstance field
 *
 * A field would have to be threaded through `ProviderConfig`, the Room entity,
 * `ProviderConfigMapping`, a schema version bump and a migration — a large,
 * permanently-conflicting diff across the files upstream edits most, for one
 * boolean. A separate prefs file is invisible to upstream, needs no migration,
 * and degrades correctly: an instance with no entry here (restored from a
 * backup, imported from iOS, or created by an older build) behaves exactly as
 * upstream does.
 */
object ForkModelPolicy {

    private const val PREFS = "fork_model_policy"
    private const val KEY_PREFIX = "manual_"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * True when this instance's model list is user-curated and must not be
     * replaced by a background refresh.
     */
    fun isManual(context: Context, instanceId: String): Boolean =
        prefs(context).getBoolean(KEY_PREFIX + instanceId, false)

    /** Called when the fork creates a provider through AddProviderScreen. */
    fun markManual(context: Context, instanceId: String) {
        prefs(context).edit().putBoolean(KEY_PREFIX + instanceId, true).apply()
    }

    /** Opt back into upstream's automatic daily refresh for this instance. */
    fun clearManual(context: Context, instanceId: String) {
        prefs(context).edit().remove(KEY_PREFIX + instanceId).apply()
    }
}
