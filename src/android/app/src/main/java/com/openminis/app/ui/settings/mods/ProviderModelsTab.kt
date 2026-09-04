package com.openminis.app.ui.settings.mods

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.settings.SettingsSection
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * [FORK] The "模型" tab — kelivo-style model management.
 *
 * ## Layout
 *
 * Actions live in a BOTTOM bar ([ProviderModelsActionBar], rendered by the tab
 * host above the 配置/模型 strip), not above the list: the list is the content,
 * the actions are chrome, and putting them on top pushed the first model row a
 * third of the way down the screen.
 *
 * The bar has two modes, mirroring kelivo:
 *
 *   normal    获取 · 添加 · 删除        (删除 = clear the whole list, confirmed)
 *   测活选择   [全选/取消全选] · 测活    (entered from the top-right bolt)
 *
 * 测活 is a two-step action on purpose. Probing is a real network cost per
 * model — running it implicitly over everything visible, as the first cut did,
 * spends the user's quota on rows they never asked about. Now the top-right
 * button only opens selection; nothing is sent until 测活 is pressed with a
 * chosen set.
 *
 * ## Other differences from the upstream model section this replaces
 *
 *  - No one-tap "refresh everything". Upstream's `Refresh model list` calls
 *    `refreshModels`, which REPLACES the entry list with the endpoint's whole
 *    catalog. 获取 opens a browser instead ([ProviderModelsFetchSheet]), where
 *    each row has a ＋/－ that writes immediately.
 *  - Swipe a row left to reveal a single square Remove button; tapping it
 *    removes immediately with no confirmation. Several rows can be held open at
 *    once and removed one after another — see [ForkSwipeOpenState] for why that
 *    requires hoisted state.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProviderModelsTabContent(
    instanceId: String,
    providerRepository: ProviderRepository,
    onModelEntryClick: (String) -> Unit,
    // Hoisted so the host can render the action bar in its bottom slot while the
    // list stays inside the scrolling content.
    controller: ProviderModelsController,
) {
    val config by providerRepository.config.collectAsState()
    val instance = config.instances.find { it.id == instanceId } ?: return

    var menuEntryId by remember { mutableStateOf<String?>(null) }

    val allEntries = providerRepository.entriesFor(instanceId)
    val q = controller.searchQuery.trim().lowercase()
    val entries = if (q.isEmpty()) {
        allEntries
    } else {
        allEntries.filter {
            it.model.displayName.lowercase().contains(q) || it.model.id.lowercase().contains(q)
        }
    }
    // Publish the visible set for the action bar (rendered in the scaffold's
    // bottomBar slot, a different subtree) via SideEffect rather than a bare
    // assignment: writing snapshot state DURING composition that another subtree
    // reads is exactly the pattern that can loop. SideEffect runs only after a
    // successful composition, which is the documented way to hand a composition
    // result to state outside this subtree.
    androidx.compose.runtime.SideEffect {
        controller.visibleEntries = entries
    }

    // ── Search ──────────────────────────────────────────────────────────
    // Only once the list is big enough to need it; a 3-model provider showing a
    // search box is noise.
    if (allEntries.size >= 6) {
        OutlinedTextField(
            value = controller.searchQuery,
            onValueChange = { controller.searchQuery = it },
            placeholder = { Text(stringResource(R.string.fork_models_search_placeholder)) },
            singleLine = true,
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp),
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                if (controller.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { controller.searchQuery = "" }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.fork_models_clear_search),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            },
        )
    }

    // ── List ────────────────────────────────────────────────────────────
    val header = if (q.isEmpty()) {
        stringResource(R.string.fork_models_count, allEntries.size)
    } else {
        stringResource(R.string.fork_models_count_filtered, entries.size, allEntries.size)
    }

    if (allEntries.isEmpty()) {
        SettingsSection(header = header) {
            Text(
                stringResource(R.string.fork_models_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    } else if (entries.isEmpty()) {
        SettingsSection(header = header) {
            Text(
                stringResource(R.string.fork_models_empty_search, controller.searchQuery.trim()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    } else {
        SettingsSection(header = header) {
            entries.forEachIndexed { idx, entry ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (controller.testSelectionMode) {
                        // No swipe-to-remove while selecting: the row's job right
                        // now is to be ticked, and a horizontal drag that deletes
                        // it mid-selection is the kind of surprise that loses data.
                        ModelRowBody(
                            entry = entry,
                            providerType = instance.providerType,
                            health = controller.health[entry.id],
                            selectionMark = entry.id in controller.testSelection,
                            onClick = { controller.toggleTestSelection(entry.id) },
                            onLongClick = {},
                        )
                    } else {
                        ForkSwipeToRemoveRow(
                            id = entry.id,
                            openState = controller.swipeState,
                            actionIcon = Icons.Filled.Delete,
                            actionLabel = stringResource(R.string.common_delete),
                            // Straight through, no dialog — that is the requested
                            // behaviour and the reason the swipe exists. removeEntry
                            // also strips the entry from any model groups and the
                            // agent-loop pin, so nothing is left dangling.
                            onRemove = {
                                AppLogger.info(
                                    "ForkModels",
                                    "swipe-remove ${entry.id} (${entry.model.displayName})",
                                )
                                providerRepository.removeEntry(entry.id)
                                controller.health.remove(entry.id)
                            },
                        ) {
                            ModelRowBody(
                                entry = entry,
                                providerType = instance.providerType,
                                health = controller.health[entry.id],
                                selectionMark = null,
                                onClick = {
                                    // A tap while ANY row is open means "put them
                                    // back", not "navigate" — otherwise the open
                                    // rows are unreachable without a second swipe.
                                    if (controller.swipeState.hasOpen) {
                                        controller.swipeState.closeAll()
                                    } else {
                                        onModelEntryClick(entry.id)
                                    }
                                },
                                onLongClick = { menuEntryId = entry.id },
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = menuEntryId == entry.id,
                        onDismissRequest = { menuEntryId = null },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (entry.isHidden) R.string.provider_detail_show_model
                                        else R.string.provider_detail_hide_model,
                                    ),
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (entry.isHidden) Icons.Filled.Visibility
                                    else Icons.Filled.VisibilityOff,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuEntryId = null
                                providerRepository.updateEntry(entry.copy(isHidden = !entry.isHidden))
                            },
                        )
                    }
                }
                if (idx != entries.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(24.dp))

    if (controller.showFetchSheet) {
        ProviderModelsFetchSheet(
            instance = instance,
            providerRepository = providerRepository,
            onDismiss = { controller.showFetchSheet = false },
        )
    }

    if (controller.showDeleteAllDialog) {
        MinisAlertDialog(
            onDismissRequest = { controller.showDeleteAllDialog = false },
            title = stringResource(R.string.fork_models_delete_all_title),
            text = stringResource(R.string.fork_models_delete_all_message, allEntries.size),
            confirmText = stringResource(R.string.common_delete),
            isDestructive = true,
            onConfirm = {
                // Removes ALL entries of this instance, filtered set or not: the
                // button says 删除 (全部), and deleting "what happens to be
                // visible" would be a trap for anyone who left a search active.
                // One batched write — a 400-model provider would otherwise mean
                // 400 saveConfig round-trips (DB + JSON mirror + an emission that
                // recomposes every reader) for a single logical action.
                val removed = providerRepository.removeEntries(allEntries.map { it.id })
                controller.health.clear()
                controller.swipeState.closeAll()
                controller.searchQuery = ""
                controller.showDeleteAllDialog = false
                AppLogger.info("ForkModels", "delete-all: $removed entries of $instanceId")
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelRowBody(
    entry: ModelEntry,
    providerType: com.openminis.app.data.model.ProviderType,
    health: ForkModelHealth.Status?,
    /** Non-null puts a selection tick in front of the row (测活 selection mode). */
    selectionMark: Boolean?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Hidden entries dim, matching upstream's treatment; alpha is visual
            // only so the row stays tappable to un-hide from its detail screen.
            .then(if (entry.isHidden) Modifier.alpha(0.45f) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectionMark != null) {
                Icon(
                    if (selectionMark) Icons.Default.CheckCircle
                    else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selectionMark) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            // Brand logo, with the 测活 result as a ring around it so the row
            // gains no extra glyph.
            ForkBrandAvatar(
                modelId = entry.model.id,
                displayName = entry.model.displayName,
                providerName = providerType.displayName,
                size = 28.dp,
                ringColor = when (health) {
                    is ForkModelHealth.Status.Alive -> Color(0xFF34C759)
                    is ForkModelHealth.Status.Dead -> MaterialTheme.colorScheme.error
                    else -> null
                },
            )
            Spacer(Modifier.width(10.dp))
            Text(
                entry.model.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            HealthBadge(health)
            if (entry.isHidden) {
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.fork_models_hidden_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selectionMark == null) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            entry.model.id,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = if (selectionMark != null) 68.dp else 38.dp),
        )
        Row(modifier = Modifier.padding(start = if (selectionMark != null) 68.dp else 38.dp)) {
            ForkModelCapabilityRow(entry.model)
        }
    }
}

@Composable
private fun HealthBadge(health: ForkModelHealth.Status?) {
    when (health) {
        is ForkModelHealth.Status.Running ->
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        is ForkModelHealth.Status.Alive -> Text(
            "%.1fs".format(health.latencyMs / 1000.0),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF34C759),
        )
        is ForkModelHealth.Status.Dead -> Text(
            stringResource(R.string.fork_health_dead),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        else -> Unit
    }
}

/**
 * [FORK] Top-bar action that enters 测活 selection mode (and leaves it).
 *
 * Placed in the scaffold's `actions` slot rather than the bottom bar because the
 * bottom bar is where the *modes* live: 获取/添加/删除 are the default set, and
 * the selection controls replace them. A trigger that lives inside the thing it
 * replaces would have to disappear on its own press.
 */
@Composable
fun ProviderModelsTopAction(controller: ProviderModelsController) {
    IconButton(
        onClick = {
            if (controller.testSelectionMode) {
                controller.exitTestSelection()
            } else {
                controller.swipeState.closeAll()
                controller.testSelectionMode = true
            }
        },
    ) {
        Icon(
            if (controller.testSelectionMode) Icons.Default.Close else Icons.Default.Bolt,
            contentDescription = stringResource(
                if (controller.testSelectionMode) R.string.fork_health_exit_selection
                else R.string.fork_health_check,
            ),
            tint = if (controller.testSelectionMode) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}

/**
 * [FORK] Bottom action bar for the 模型 tab.
 *
 * Two modes — see [ProviderModelsTabContent]'s docs for why 测活 is two-step.
 */
@Composable
fun ProviderModelsActionBar(
    controller: ProviderModelsController,
    providerRepository: ProviderRepository,
    onAddCustomModel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (controller.testSelectionMode) {
        val visibleIds = controller.visibleEntries.map { it.id }
        val allSelected = visibleIds.isNotEmpty() && visibleIds.all { it in controller.testSelection }
        val running = controller.healthJob?.isActive == true

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MinisOutlinedButton(
                onClick = {
                    controller.testSelection = if (allSelected) {
                        controller.testSelection - visibleIds.toSet()
                    } else {
                        controller.testSelection + visibleIds
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = visibleIds.isNotEmpty(),
            ) {
                Icon(
                    if (allSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(
                        if (allSelected) R.string.fork_health_clear_selection
                        else R.string.fork_health_select_all,
                    ),
                )
            }
            MinisOutlinedButton(
                onClick = {
                    if (running) {
                        controller.healthJob?.cancel()
                        controller.healthJob = null
                        return@MinisOutlinedButton
                    }
                    val targets = controller.visibleEntries
                        .filter { it.id in controller.testSelection }
                    if (targets.isEmpty()) return@MinisOutlinedButton
                    targets.forEach { controller.health[it.id] = ForkModelHealth.Status.Pending }
                    controller.healthJob = scope.launch {
                        try {
                            ForkModelHealth.probeAll(
                                entries = targets,
                                providerRepository = providerRepository,
                                context = context,
                            ) { entryId, status ->
                                controller.health[entryId] = status
                            }
                        } finally {
                            controller.healthJob = null
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = running || controller.testSelection.isNotEmpty(),
            ) {
                Icon(
                    if (running) Icons.Default.Stop else Icons.Default.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        running -> stringResource(R.string.fork_health_stop)
                        controller.testSelection.isEmpty() -> stringResource(R.string.fork_health_check)
                        else -> stringResource(
                            R.string.fork_health_check_n,
                            controller.testSelection.size,
                        )
                    },
                )
            }
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MinisOutlinedButton(
            onClick = { controller.showFetchSheet = true },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.fork_models_fetch_short))
        }
        MinisOutlinedButton(
            onClick = onAddCustomModel,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.fork_models_add_short))
        }
        MinisOutlinedButton(
            onClick = { controller.showDeleteAllDialog = true },
            modifier = Modifier.weight(1f),
            enabled = controller.visibleEntries.isNotEmpty(),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.common_delete))
        }
    }
}

/**
 * [FORK] State shared between the 模型 tab's list, its top-bar action and its
 * bottom action bar.
 *
 * All three are rendered into different scaffold slots (actions / content /
 * bottomBar), so none of them can own state the others need — hence a single
 * holder created by the host. It also outlives a tab switch, so a completed 测活
 * run's results are still there when the user comes back from 配置.
 */
@androidx.compose.runtime.Stable
class ProviderModelsController(
    /** Hoisted swipe-open set — see [ForkSwipeOpenState] for why it can't live in the rows. */
    val swipeState: ForkSwipeOpenState,
) {
    var searchQuery by mutableStateOf("")
    var showFetchSheet by mutableStateOf(false)
    var showDeleteAllDialog by mutableStateOf(false)

    /** Entries currently shown (post-search). */
    var visibleEntries by mutableStateOf<List<ModelEntry>>(emptyList())

    /** entryId → probe status. */
    val health = mutableStateMapOf<String, ForkModelHealth.Status>()

    var healthJob: Job? by mutableStateOf(null)

    /** True while the user is picking which models to probe. */
    var testSelectionMode by mutableStateOf(false)

    /** entryIds picked for the next probe run. */
    var testSelection by mutableStateOf<Set<String>>(emptySet())

    fun toggleTestSelection(id: String) {
        testSelection = if (id in testSelection) testSelection - id else testSelection + id
    }

    fun exitTestSelection() {
        healthJob?.cancel()
        healthJob = null
        testSelectionMode = false
        testSelection = emptySet()
    }
}

@Composable
fun rememberProviderModelsController(): ProviderModelsController {
    val swipe = rememberForkSwipeOpenState()
    return remember(swipe) { ProviderModelsController(swipe) }
}
