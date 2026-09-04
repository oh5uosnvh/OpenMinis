package com.openminis.app.ui.settings.mods

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
 *   模型 tab → [ProviderModelsTabContent] (list) + [ProviderModelsActionBar]
 *              (获取模型 / 手动添加 / 测活), the latter pinned above the tab
 *              strip so it stays reachable however far the list is scrolled.
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

    // Created HERE, not inside the tab: the list and the action bar are rendered
    // into different scaffold slots (content vs bottomBar) and must share state.
    // Hoisting it also means a finished 测活 run's results survive a hop to 配置
    // and back.
    val modelsController = rememberProviderModelsController()

    // Back semantics, in priority order:
    //  1. 测活 selection mode → leave it (the top-bar ✕ does the same, but back
    //     is what a user reaches for first).
    //  2. swipe-open rows → put them back (a destructive button is visible; the
    //     user's most likely intent is "undo that reveal", not "leave").
    //  3. on 模型 → return to 配置 first; the two tabs read as one screen.
    //  4. otherwise fall through to the navigation pop.
    BackHandler(
        enabled = tab == ProviderDetailTab.MODELS ||
            modelsController.swipeState.hasOpen ||
            modelsController.testSelectionMode,
    ) {
        when {
            modelsController.testSelectionMode -> modelsController.exitTestSelection()
            modelsController.swipeState.hasOpen -> modelsController.swipeState.closeAll()
            else -> tab = ProviderDetailTab.CONFIG
        }
    }

    val tabStrip: @Composable () -> Unit = {
        ProviderDetailBottomTabs(
            selected = tab,
            onSelect = { next ->
                // Leaving 模型 with rows held open (or mid-selection) would
                // strand that state on return; reset so the tab is always
                // entered clean.
                if (next != tab) {
                    modelsController.swipeState.closeAll()
                    modelsController.exitTestSelection()
                }
                tab = next
            },
        )
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
            bottomBar = tabStrip,
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
                    // 测活 lives in the top bar so the bottom bar is free to be
                    // the MODE surface: 获取/添加/删除 by default, 全选+测活 while
                    // selecting. A trigger inside the thing it replaces would
                    // have to vanish on its own press.
                    actions = { ProviderModelsTopAction(modelsController) },
                    bottomBar = {
                        // Action bar sits directly above the tab strip, sharing
                        // its surface so the two read as one pinned footer.
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            tonalElevation = 3.dp,
                        ) {
                            Column {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(0.5.dp)
                                        .background(
                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                        ),
                                )
                                ProviderModelsActionBar(
                                    controller = modelsController,
                                    providerRepository = providerRepository,
                                    onAddCustomModel = onAddCustomModel,
                                )
                                ProviderDetailBottomTabs(
                                    selected = tab,
                                    onSelect = { next ->
                                        if (next != tab) {
                                            modelsController.swipeState.closeAll()
                                            modelsController.exitTestSelection()
                                        }
                                        tab = next
                                    },
                                    // Footer already draws the surface + rule.
                                    chrome = false,
                                )
                            }
                        }
                    },
                ) {
                    ProviderModelsTabContent(
                        instanceId = instanceId,
                        providerRepository = providerRepository,
                        onModelEntryClick = onModelEntryClick,
                        controller = modelsController,
                    )
                }
            }
        }
    }
}
