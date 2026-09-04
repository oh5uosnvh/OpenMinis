package com.openminis.app.ui.settings.mods

import androidx.activity.compose.BackHandler
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.settings.ProviderDetailScreen
import com.openminis.app.ui.settings.SettingsScaffold

/**
 * [FORK] Host for the provider screen's two fixed bottom tabs.
 *
 * Replaces `ProviderDetailScreen` as the destination of the `provider/{id}`
 * route (one line in AppNavigation.kt — the entire upstream seam).
 *
 * Structure:
 *
 *   配置 tab → the upstream [ProviderDetailScreen] verbatim, with
 *              `showModelsSection = false` so its model list is suppressed,
 *              plus the bottom bar. Every other section (label, credential,
 *              endpoint, API format, Azure, image endpoint, status, voice,
 *              thinking rules) keeps upstream's behaviour, including future
 *              additions — we do not re-implement that page, we host it.
 *   模型 tab → [ProviderModelsTabContent], the fork's kelive-style list.
 *
 * Why reuse rather than fork the config page: it is 1200 lines that upstream
 * edits nearly every release. Hosting it means an upstream change to any
 * section lands for free; forking it would mean merging those changes by hand
 * forever. See docs/FORK.md §3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderDetailTabbedScreen(
    instanceId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onModelEntryClick: (String) -> Unit = {},
    onAddCustomModel: () -> Unit = {},
    onVoiceServiceClick: (String) -> Unit = {},
) {
    // rememberSaveable so a rotation (or process-death restore) keeps the user
    // on the tab they were reading.
    var tab by rememberSaveable { mutableStateOf(ProviderDetailTab.CONFIG) }

    // On the 模型 tab, system back returns to 配置 first rather than leaving the
    // provider entirely — matches how the two tabs read as one screen.
    BackHandler(enabled = tab == ProviderDetailTab.MODELS) {
        tab = ProviderDetailTab.CONFIG
    }

    val bottomBar: @Composable () -> Unit = {
        ProviderDetailBottomTabs(selected = tab, onSelect = { tab = it })
    }

    when (tab) {
        ProviderDetailTab.CONFIG -> ProviderDetailScreen(
            instanceId = instanceId,
            providerRepository = providerRepository,
            onBack = onBack,
            onModelEntryClick = onModelEntryClick,
            onAddCustomModel = onAddCustomModel,
            onVoiceServiceClick = onVoiceServiceClick,
            showModelsSection = false,
            bottomBar = bottomBar,
        )

        ProviderDetailTab.MODELS -> {
            val config by providerRepository.config.collectAsState()
            val instance = config.instances.find { it.id == instanceId }
            if (instance == null) {
                onBack()
            } else {
                SettingsScaffold(
                    title = instance.label,
                    onBack = onBack,
                    bottomBar = bottomBar,
                ) {
                    ProviderModelsTabContent(
                        instanceId = instanceId,
                        providerRepository = providerRepository,
                        onModelEntryClick = onModelEntryClick,
                        onAddCustomModel = onAddCustomModel,
                    )
                }
            }
        }
    }
}
