package com.openminis.app.ui.settings.mods

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import com.openminis.app.ui.components.MinisSmallTextButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * [FORK] The 获取模型 sheet — fetch the provider catalog, tick what you want,
 * confirm. Grouped by model family, kelive-style.
 *
 * The whole reason this exists: upstream's model refresh is fetch-and-replace
 * with no选择环节. Here the fetch writes NOTHING (see
 * [ProviderCatalogFetcher.fetchCatalog]); the only write happens when the user
 * presses 添加, and it is additive.
 *
 * Behaviours worth knowing:
 *
 *  - **Sticky sheet.** Closes only via the X button or the device back gesture
 *    ([rememberForkStickySheet]). Scrim taps and swipe-down are vetoed, because
 *    on a long list both fire constantly and take the search text and scroll
 *    position with them.
 *  - **Family sections.** A relay's `/v1/models` is a flat wall of ids;
 *    [ForkModelFamily] groups them (Claude Opus / GPT-5 / Gemini …) so the list
 *    is navigable. Sections collapse.
 *  - **全选 / 取消全选** applies to the CURRENTLY FILTERED, selectable rows —
 *    the ones the user can see. A "select all" that silently also picks up
 *    hidden-by-search rows would make the confirm count a surprise.
 *  - **测活 before adding.** Probes the fetched catalog so the user can add only
 *    the ids the relay actually serves. Runs on the fetched LLMModels, not on
 *    persisted entries, hence the synthetic ModelEntry wrapper below.
 *  - Models already on the instance render as 已添加 and are non-selectable —
 *    pre-ticking them would make the confirm button's count a lie about what is
 *    about to change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderModelsFetchSheet(
    instance: ProviderInstance,
    providerRepository: ProviderRepository,
    onDismiss: () -> Unit,
) {
    val sheet = rememberForkStickySheet(onDismiss)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var catalog by remember { mutableStateOf<List<LLMModel>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var searchQuery by remember { mutableStateOf("") }
    var reloadTick by remember { mutableStateOf(0) }
    var collapsedFamilies by remember { mutableStateOf<Set<String>>(emptySet()) }
    val health = remember { mutableStateMapOf<String, ForkModelHealth.Status>() }
    var healthJob by remember { mutableStateOf<Job?>(null) }

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
    val healthRunning = healthJob?.isActive == true

    ModalBottomSheet(
        onDismissRequest = {
            // Reached only by the back gesture — material3's dialog path calls
            // this directly, bypassing confirmValueChange. Scrim/swipe are
            // already vetoed by the sticky state, so this IS the back button.
            healthJob?.cancel()
            onDismiss()
        },
        sheetState = sheet.state,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
        ) {
            // ── Header ──────────────────────────────────────────────────
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
                IconButton(
                    onClick = {
                        healthJob?.cancel()
                        sheet.close()
                    },
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.fork_fetch_close),
                    )
                }
            }

            // ── Toolbar: search + 全选 + 测活 ────────────────────────────
            if (!isLoading && errorMessage == null && catalog.isNotEmpty()) {
                if (catalog.size >= 6) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.fork_models_search_placeholder)) },
                        singleLine = true,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MinisSmallTextButton(
                        onClick = {
                            selected = if (allSelected) {
                                selected - selectableIds.toSet()
                            } else {
                                selected + selectableIds
                            }
                        },
                        enabled = selectableIds.isNotEmpty(),
                    ) {
                        Text(
                            stringResource(
                                if (allSelected) R.string.fork_fetch_clear_selection
                                else R.string.fork_fetch_select_all,
                            ),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    MinisSmallTextButton(
                        onClick = {
                            if (healthRunning) {
                                healthJob?.cancel()
                                healthJob = null
                                return@MinisSmallTextButton
                            }
                            // Probe what the user can see. A catalog-wide run on
                            // a 400-model relay would take many minutes for rows
                            // they have filtered away anyway.
                            val targets = filtered.map { model ->
                                // ForkModelHealth takes ModelEntry (it needs the
                                // owning instance id to resolve credentials).
                                // These are NOT persisted — a throwaway wrapper
                                // so the probe can run before anything is added.
                                ModelEntry(
                                    providerInstanceId = instance.id,
                                    baseModel = model,
                                    uuid = "probe-${model.id}",
                                )
                            }
                            if (targets.isEmpty()) return@MinisSmallTextButton
                            targets.forEach { health[it.baseModel.id] = ForkModelHealth.Status.Pending }
                            healthJob = scope.launch {
                                try {
                                    ForkModelHealth.probeAll(
                                        entries = targets,
                                        providerRepository = providerRepository,
                                        context = context,
                                    ) { entryId, status ->
                                        // entryId is "probe-<modelId>"; key the
                                        // map by model id so rows can read it.
                                        health[entryId.removePrefix("probe-")] = status
                                    }
                                } finally {
                                    healthJob = null
                                }
                            }
                        },
                    ) {
                        Icon(
                            if (healthRunning) Icons.Default.Stop else Icons.Default.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(
                                if (healthRunning) R.string.fork_health_stop
                                else R.string.fork_health_check,
                            ),
                        )
                    }
                }
            }

            // ── Body ────────────────────────────────────────────────────
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
                                // Composite key: a relay occasionally lists the
                                // same id twice, and a duplicate LazyColumn key
                                // is a hard crash rather than a cosmetic issue.
                                items(models, key = { "${family.key}-${it.id}" }) { model ->
                                    CatalogRow(
                                        model = model,
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

            // ── Confirm ─────────────────────────────────────────────────
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
                        healthJob?.cancel()
                        sheet.close()
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
}

/** Collapsible family section header with its own select-all toggle. */
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
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
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
            IconButton(onClick = onToggleSelectAll, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (allSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (allSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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
    alreadyAdded: Boolean,
    isSelected: Boolean,
    health: ForkModelHealth.Status?,
    onToggle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (alreadyAdded) Modifier else Modifier.clickable(onClick = onToggle))
            .heightIn(min = 68.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                health is ForkModelHealth.Status.Running ->
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                alreadyAdded -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp),
                )
                else -> Icon(
                    if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
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
        }
        Row(modifier = Modifier.padding(start = 30.dp)) {
            ForkModelCapabilityRow(model)
        }
    }
}
