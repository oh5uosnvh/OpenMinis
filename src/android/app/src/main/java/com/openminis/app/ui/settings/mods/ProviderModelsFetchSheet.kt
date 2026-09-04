package com.openminis.app.ui.settings.mods

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.MinisButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * [FORK] The 获取模型 sheet — fetch the provider catalog, tick what you want,
 * confirm. Grouped by model family with brand logos, following kelivo's
 * `_showModelFetchSheet` layout (provider_detail_page.dart:3360).
 *
 * Layout, top to bottom:
 *   handle
 *   title + count + close ✕
 *   search field, with 全选/取消全选 and 反选 as trailing icons (kelivo puts them
 *     inside the field rather than on their own row — it keeps the toolbar to
 *     one line and the icons next to the text they act on)
 *   family sections: chevron + name + count + a select-all for the whole group
 *   rows: tick + brand avatar + name + id + capability chips + a ⚡ that probes
 *     THAT model on its own, ✓ 已添加 when already present
 *   添加 (n) — the only write
 *
 * ## Per-row ⚡ rather than a batch button
 *
 * The bar-level 测活 belongs to the 模型 tab, where the models are already yours.
 * Here the useful question is narrower — "is THIS id real before I adopt it?" —
 * and a relay catalog is exactly where you want to ask it one model at a time
 * instead of spending a request on all 400. The bolt is per row, fires
 * immediately, and shows the result in place.
 *
 * ## No drag, no shudder
 *
 * Built on [ForkBottomSheet] (a Dialog), not ModalBottomSheet: swiping a row
 * cannot move the container because there is no draggable offset to move. See
 * [ForkBottomSheetState] for the full reasoning.
 *
 * Models already on the instance render as 已添加 and are non-selectable —
 * pre-ticking them would make the confirm button's count a lie about what is
 * about to change.
 */
@Composable
fun ProviderModelsFetchSheet(
    instance: ProviderInstance,
    providerRepository: ProviderRepository,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberForkBottomSheetState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var catalog by remember { mutableStateOf<List<LLMModel>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var searchQuery by remember { mutableStateOf("") }
    var reloadTick by remember { mutableStateOf(0) }
    var collapsedFamilies by remember { mutableStateOf<Set<String>>(emptySet()) }

    // modelId → probe status, for the per-row ⚡.
    val health = remember { mutableStateMapOf<String, ForkModelHealth.Status>() }
    val probeJobs = remember { mutableMapOf<String, Job>() }

    val existingIds = remember(instance.id, catalog) {
        providerRepository.entriesFor(instance.id).map { it.baseModel.id }.toSet()
    }

    LaunchedEffect(instance.id, reloadTick) {
        isLoading = true
        errorMessage = null
        when (val result = ProviderCatalogFetcher.fetchCatalog(instance, providerRepository)) {
            is ProviderCatalogFetcher.FetchResult.Success -> {
                catalog = result.models
                errorMessage = null
            }
            is ProviderCatalogFetcher.FetchResult.Failure -> {
                catalog = emptyList()
                errorMessage = result.message
            }
        }
        isLoading = false
    }

    fun probe(model: LLMModel) {
        // Already running → the bolt acts as cancel for that row.
        probeJobs[model.id]?.let {
            if (it.isActive) {
                it.cancel()
                health.remove(model.id)
                probeJobs.remove(model.id)
                return
            }
        }
        health[model.id] = ForkModelHealth.Status.Running
        probeJobs[model.id] = scope.launch {
            // A throwaway ModelEntry — nothing is persisted. ForkModelHealth needs
            // one only to resolve the owning instance's credentials.
            val entry = ModelEntry(
                providerInstanceId = instance.id,
                baseModel = model,
                uuid = "probe-${model.id}",
            )
            val status = ForkModelHealth.probeOne(entry, providerRepository, context)
            health[model.id] = status
            probeJobs.remove(model.id)
        }
    }

    val q = searchQuery.trim().lowercase()
    val filtered = if (q.isEmpty()) {
        catalog
    } else {
        catalog.filter {
            it.displayName.lowercase().contains(q) || it.id.lowercase().contains(q)
        }
    }
    val sections = remember(filtered) { ForkModelFamily.group(filtered) }
    // Only rows the user can actually act on count towards 全选.
    val selectableIds = filtered.filter { it.id !in existingIds }.map { it.id }
    val allSelected = selectableIds.isNotEmpty() && selectableIds.all { it in selected }

    ForkBottomSheet(
        state = sheetState,
        onDismiss = {
            probeJobs.values.forEach { it.cancel() }
            onDismiss()
        },
    ) {
        ForkSheetHandle()

        // ── Header ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp)
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.fork_fetch_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (isLoading || errorMessage != null) {
                        stringResource(R.string.fork_fetch_subtitle)
                    } else {
                        stringResource(R.string.fork_fetch_found, catalog.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Explicit close — one of only two ways out of this sheet.
            IconButton(onClick = { sheetState.close() }) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.fork_fetch_close),
                )
            }
        }

        // ── Search + selection toggles (kelivo puts them in the field) ────
        if (!isLoading && errorMessage == null && catalog.isNotEmpty()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.fork_models_search_placeholder)) },
                singleLine = true,
                shape = RoundedCornerShape(50),
                colors = OutlinedTextFieldDefaults.colors(
                    // The sheet's container is surfaceContainerLow; without an
                    // explicit fill the field would be invisible against it.
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                selected = if (allSelected) {
                                    selected - selectableIds.toSet()
                                } else {
                                    selected + selectableIds
                                }
                            },
                            enabled = selectableIds.isNotEmpty(),
                        ) {
                            Icon(
                                if (allSelected) Icons.Default.CheckBox
                                else Icons.Default.CheckBoxOutlineBlank,
                                contentDescription = stringResource(
                                    if (allSelected) R.string.fork_fetch_clear_selection
                                    else R.string.fork_fetch_select_all,
                                ),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 反选 — kelivo's Repeat icon. Useful after a search:
                        // "everything except what I already ticked".
                        IconButton(
                            onClick = {
                                val next = selected.toMutableSet()
                                selectableIds.forEach {
                                    if (it in next) next.remove(it) else next.add(it)
                                }
                                selected = next
                            },
                            enabled = selectableIds.isNotEmpty(),
                        ) {
                            Icon(
                                Icons.Default.SwapHoriz,
                                contentDescription = stringResource(R.string.fork_fetch_invert),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        }

        // ── Body ────────────────────────────────────────────────────────
        Box(modifier = Modifier.weight(1f)) {
            when {
                isLoading -> Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Text(
                        stringResource(R.string.fork_fetch_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                errorMessage != null -> Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.fork_fetch_failed, errorMessage!!),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    MinisButton(onClick = { reloadTick++ }) {
                        Text(stringResource(R.string.fork_fetch_retry))
                    }
                }

                catalog.isEmpty() -> Text(
                    stringResource(R.string.fork_fetch_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )

                else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    sections.forEach { (family, models) ->
                        val collapsed = family.key in collapsedFamilies
                        val familySelectable = models
                            .filter { it.id !in existingIds }
                            .map { it.id }
                        val familyAllSelected = familySelectable.isNotEmpty() &&
                            familySelectable.all { it in selected }

                        item(key = "family-${family.key}") {
                            FamilyHeader(
                                title = family.displayName,
                                count = models.size,
                                collapsed = collapsed,
                                allSelected = familyAllSelected,
                                canSelect = familySelectable.isNotEmpty(),
                                onToggleCollapse = {
                                    collapsedFamilies = if (collapsed) {
                                        collapsedFamilies - family.key
                                    } else {
                                        collapsedFamilies + family.key
                                    }
                                },
                                // Per-section select-all: on a relay with 20
                                // families, "I want every Claude model" is the
                                // common intent and the global 全选 is too blunt.
                                onToggleSelectAll = {
                                    selected = if (familyAllSelected) {
                                        selected - familySelectable.toSet()
                                    } else {
                                        selected + familySelectable
                                    }
                                },
                            )
                        }

                        if (!collapsed) {
                            // Composite key: a relay occasionally lists the same
                            // id twice, and a duplicate LazyColumn key is a hard
                            // crash rather than a cosmetic issue.
                            items(models, key = { "${family.key}-${it.id}" }) { model ->
                                CatalogRow(
                                    model = model,
                                    providerLabel = instance.providerType.displayName,
                                    alreadyAdded = model.id in existingIds,
                                    isSelected = model.id in selected,
                                    health = health[model.id],
                                    onToggle = {
                                        selected = if (model.id in selected) {
                                            selected - model.id
                                        } else {
                                            selected + model.id
                                        }
                                    },
                                    onProbe = { probe(model) },
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Confirm ─────────────────────────────────────────────────────
        if (!isLoading && errorMessage == null && catalog.isNotEmpty()) {
            MinisButton(
                onClick = {
                    val picks = catalog.filter { it.id in selected }
                    val added = ProviderCatalogFetcher.addSelected(
                        instance.id,
                        picks,
                        providerRepository,
                    )
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.fork_fetch_added_toast, added),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    sheetState.close()
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .navigationBarsPadding(),
            ) {
                Text(
                    if (selected.isEmpty()) {
                        stringResource(R.string.fork_fetch_add_none)
                    } else {
                        stringResource(R.string.fork_fetch_add_selected, selected.size)
                    },
                )
            }
        } else {
            Spacer(Modifier.height(12.dp).navigationBarsPadding())
        }
    }
}

/**
 * Collapsible family section header with its own select-all toggle.
 * kelivo's version: rotating chevron, name, count, then a control that adds or
 * removes the entire group.
 */
@Composable
private fun FamilyHeader(
    title: String,
    count: Int,
    collapsed: Boolean,
    allSelected: Boolean,
    canSelect: Boolean,
    onToggleCollapse: () -> Unit,
    onToggleSelectAll: () -> Unit,
) {
    // Chevron points right when collapsed, down when open — animated so the
    // section reads as folding rather than swapping glyphs.
    val rotation by animateFloatAsState(
        targetValue = if (collapsed) 0f else 90f,
        label = "familyChevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(12.dp),
            )
            .heightIn(min = 46.dp)
            .clickable(onClick = onToggleCollapse)
            .padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp).rotate(rotation),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (canSelect) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onToggleSelectAll, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (allSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = if (allSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun CatalogRow(
    model: LLMModel,
    providerLabel: String,
    alreadyAdded: Boolean,
    isSelected: Boolean,
    health: ForkModelHealth.Status?,
    onToggle: () -> Unit,
    onProbe: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (alreadyAdded) Modifier else Modifier.clickable(onClick = onToggle))
            .heightIn(min = 68.dp)
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (alreadyAdded || isSelected) Icons.Default.CheckCircle
                else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = when {
                    alreadyAdded -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    isSelected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            ForkBrandAvatar(
                modelId = model.id,
                displayName = model.displayName,
                providerName = providerLabel,
                size = 28.dp,
                ringColor = when (health) {
                    is ForkModelHealth.Status.Alive -> Color(0xFF34C759)
                    is ForkModelHealth.Status.Dead -> MaterialTheme.colorScheme.error
                    else -> null
                },
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    model.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (alreadyAdded) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    model.id,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Result, then the per-row probe button.
            when (health) {
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
                else -> if (alreadyAdded) {
                    Text(
                        stringResource(R.string.fork_fetch_already_added),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Per-row 测活. Its own IconButton so the tap does NOT toggle the
            // row's selection — probing and picking are separate decisions.
            IconButton(onClick = onProbe, modifier = Modifier.size(40.dp)) {
                if (health is ForkModelHealth.Status.Running) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.Bolt,
                        contentDescription = stringResource(R.string.fork_health_check),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Row(modifier = Modifier.padding(start = 68.dp)) {
            ForkModelCapabilityRow(model)
        }
    }
}
