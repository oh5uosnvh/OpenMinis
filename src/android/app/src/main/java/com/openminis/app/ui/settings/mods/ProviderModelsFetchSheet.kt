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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.MinisButton

/**
 * [FORK] The 获取模型 sheet — browse the provider catalog and add or drop
 * individual models. Grouped by model family with brand logos, following
 * kelivo's `_showModelPicker` (provider_detail_page.dart:3341).
 *
 * Layout, top to bottom:
 *   handle
 *   title + count + close ✕
 *   search field, with add-all/remove-all and 反选 as trailing icons (kelivo puts
 *     them inside the field rather than on their own row — it keeps the toolbar
 *     to one line and the icons next to the text they act on)
 *   family sections: chevron + name + count + a ＋/－ for the whole group
 *   rows: brand avatar + name + id + capability chips + ＋/－
 *   完成
 *
 * ## One button per row, and it writes immediately
 *
 * Earlier revisions had a tick on the left, a ⚡ probe on the right and a
 * confirm button at the bottom. Three controls for what is one decision, and the
 * tick meant nothing until the confirm was pressed — a model could look chosen
 * and not be added.
 *
 * kelivo's answer is a single ＋ that adds the model there and then, and becomes
 * － once it is in. The icon IS the state, so there is nothing to reconcile: no
 * separate selection to track, no pending set that can disagree with what is
 * stored, and no confirm step to forget. Removal is the same button, which also
 * makes the sheet usable for pruning a list you already have — the old version
 * could only ever add.
 *
 * Because every tap is a write, "已添加" as a label is gone too: a row with － is
 * added, by definition.
 *
 * ## No 测活 here
 *
 * Probing lives in the 模型 tab, where the models are already yours. This sheet
 * is a catalog browser — the user reads it as "add models", and a bolt on every
 * row invites spending quota on rows nobody asked about. The tab's two-step
 * 测活 covers the real need (check what I actually adopted) without that.
 *
 * ## No drag, no shudder
 *
 * Built on [ForkBottomSheet] (a Dialog), not ModalBottomSheet: swiping a row
 * cannot move the container because there is no draggable offset to move. See
 * [ForkBottomSheetState] for the full reasoning.
 */
@Composable
fun ProviderModelsFetchSheet(
    instance: ProviderInstance,
    providerRepository: ProviderRepository,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberForkBottomSheetState()

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var catalog by remember { mutableStateOf<List<LLMModel>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var reloadTick by remember { mutableStateOf(0) }
    var collapsedFamilies by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Collected, not read once: every ＋/－ is a write, and the icons have to
    // flip on the very next frame. ProviderConfig.equals compares `revision`,
    // which saveConfig bumps on every mutation, so each write does emit.
    val config by providerRepository.config.collectAsState()
    val addedIds = remember(config, instance.id) {
        config.modelEntries
            .filter { it.providerInstanceId == instance.id }
            .map { it.baseModel.id }
            .toSet()
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

    fun add(models: List<LLMModel>) {
        ProviderCatalogFetcher.addModels(instance.id, models, providerRepository)
    }

    fun remove(models: List<LLMModel>) {
        ProviderCatalogFetcher.removeModels(
            instance.id,
            models.map { it.id },
            providerRepository,
        )
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
    val allAdded = filtered.isNotEmpty() && filtered.all { it.id in addedIds }

    ForkBottomSheet(state = sheetState, onDismiss = onDismiss) {
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
                        // Both numbers, because the second is the one the user is
                        // actually managing and it changes under their finger.
                        stringResource(
                            R.string.fork_fetch_found_added,
                            catalog.size,
                            catalog.count { it.id in addedIds },
                        )
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

        // ── Search + bulk actions (kelivo puts them in the field) ─────────
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
                        // Acts on the FILTERED set, so it reads as "everything I
                        // can currently see" rather than a hidden global sweep.
                        IconButton(
                            onClick = { if (allAdded) remove(filtered) else add(filtered) },
                            enabled = filtered.isNotEmpty(),
                        ) {
                            Icon(
                                if (allAdded) Icons.Default.PlaylistRemove
                                else Icons.Default.PlaylistAdd,
                                contentDescription = stringResource(
                                    if (allAdded) R.string.fork_fetch_remove_all
                                    else R.string.fork_fetch_add_all,
                                ),
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 反选 — kelivo's Repeat icon. Useful after a search:
                        // "swap what I have for what I don't".
                        IconButton(
                            onClick = {
                                val (present, absent) = filtered.partition { it.id in addedIds }
                                if (present.isNotEmpty()) remove(present)
                                if (absent.isNotEmpty()) add(absent)
                            },
                            enabled = filtered.isNotEmpty(),
                        ) {
                            Icon(
                                Icons.Default.SwapHoriz,
                                contentDescription = stringResource(R.string.fork_fetch_invert),
                                modifier = Modifier.size(22.dp),
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
                        val familyAllAdded = models.all { it.id in addedIds }

                        item(key = "family-${family.key}") {
                            FamilyHeader(
                                title = family.displayName,
                                count = models.size,
                                collapsed = collapsed,
                                allAdded = familyAllAdded,
                                onToggleCollapse = {
                                    collapsedFamilies = if (collapsed) {
                                        collapsedFamilies - family.key
                                    } else {
                                        collapsedFamilies + family.key
                                    }
                                },
                                // Per-family ＋/－: on a relay with 20 families,
                                // "give me every Claude model" is the common
                                // intent and the field-level control is too blunt.
                                onToggleAll = {
                                    if (familyAllAdded) remove(models) else add(models)
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
                                    added = model.id in addedIds,
                                    onToggle = {
                                        if (model.id in addedIds) {
                                            remove(listOf(model))
                                        } else {
                                            add(listOf(model))
                                        }
                                    },
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

        // ── Dismiss ─────────────────────────────────────────────────────
        // Not a confirm — every change is already saved. It exists because the
        // ✕ is a long reach at the top of a full-height sheet.
        if (!isLoading && errorMessage == null && catalog.isNotEmpty()) {
            MinisButton(
                onClick = { sheetState.close() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .navigationBarsPadding(),
            ) {
                Text(stringResource(R.string.fork_fetch_done))
            }
        } else {
            Spacer(Modifier.height(12.dp).navigationBarsPadding())
        }
    }
}

/**
 * Collapsible family section header with a ＋/－ for the whole group.
 * kelivo's version: rotating chevron, name, count, then one control that adds or
 * removes the entire family.
 *
 * The whole row toggles collapse; the ＋/－ is an [IconButton], and in Compose a
 * child's pointer input wins over the parent's, so its taps do not also fold the
 * section.
 */
@Composable
private fun FamilyHeader(
    title: String,
    count: Int,
    collapsed: Boolean,
    allAdded: Boolean,
    onToggleCollapse: () -> Unit,
    onToggleAll: () -> Unit,
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
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(22.dp)
                .rotate(rotation),
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
        IconButton(onClick = onToggleAll, modifier = Modifier.size(40.dp)) {
            Icon(
                if (allAdded) Icons.Default.Remove else Icons.Default.Add,
                contentDescription = stringResource(
                    if (allAdded) R.string.fork_fetch_remove_family
                    else R.string.fork_fetch_add_family,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun CatalogRow(
    model: LLMModel,
    providerLabel: String,
    added: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ForkBrandAvatar(
                modelId = model.id,
                displayName = model.displayName,
                providerName = providerLabel,
                size = 30.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    model.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    model.id,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The only control on the row, and the row's entire state readout:
            // ＋ means "not added", － means "added".
            IconButton(onClick = onToggle, modifier = Modifier.size(44.dp)) {
                Icon(
                    if (added) Icons.Default.Remove else Icons.Default.Add,
                    contentDescription = stringResource(
                        if (added) R.string.fork_fetch_remove_model
                        else R.string.fork_fetch_add_model,
                    ),
                    tint = if (added) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Row(modifier = Modifier.padding(start = 42.dp)) {
            ForkModelCapabilityRow(model)
        }
    }
}
