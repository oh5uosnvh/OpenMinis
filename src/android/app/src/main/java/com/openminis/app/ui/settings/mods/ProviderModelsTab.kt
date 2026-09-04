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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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

/**
 * [FORK] The "模型" tab of the provider screen — kelive-style.
 *
 * Differences from upstream's model section, which this replaces:
 *
 *  - No one-tap "refresh everything". Upstream's `Refresh model list` row calls
 *    `refreshModels`, which REPLACES the instance's entry list with whatever the
 *    endpoint returns. Point a provider at a relay fronting 400 models and the
 *    picker becomes unusable, with no way to opt out. Here 获取模型 opens a
 *    sheet, you tick what you want, and only those are added
 *    ([ProviderCatalogFetcher]).
 *  - Search, because a list you curate is still a list you have to find things
 *    in.
 *  - Per-row hide / delete stays available (long-press), same semantics as
 *    upstream: built-in entries can be hidden but not deleted, since the repo
 *    re-creates them from `ProviderType.builtInModels` on the next refresh.
 *
 * Rendered as the CONTENT of the shared tabbed scaffold, so the top bar and the
 * bottom 配置/模型 bar are owned by [ProviderDetailTabbedScreen].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProviderModelsTabContent(
    instanceId: String,
    providerRepository: ProviderRepository,
    onModelEntryClick: (String) -> Unit,
    onAddCustomModel: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val instance = config.instances.find { it.id == instanceId } ?: return

    var searchQuery by remember { mutableStateOf("") }
    var showFetchSheet by remember { mutableStateOf(false) }
    var menuEntryId by remember { mutableStateOf<String?>(null) }
    var entryToDelete by remember { mutableStateOf<ModelEntry?>(null) }

    val allEntries = providerRepository.entriesFor(instanceId)
    val q = searchQuery.trim().lowercase()
    val entries = if (q.isEmpty()) {
        allEntries
    } else {
        allEntries.filter {
            it.model.displayName.lowercase().contains(q) || it.model.id.lowercase().contains(q)
        }
    }

    // ── Actions ─────────────────────────────────────────────────────────
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MinisOutlinedButton(
            onClick = { showFetchSheet = true },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.fork_models_fetch))
        }
        MinisOutlinedButton(
            onClick = onAddCustomModel,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.fork_models_add_custom))
        }
    }

    // ── Search ──────────────────────────────────────────────────────────
    // Only once the list is big enough to need it; a 3-model provider showing a
    // search box is noise.
    if (allEntries.size >= 6) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
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
                stringResource(R.string.fork_models_empty_search, searchQuery.trim()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    } else {
        SettingsSection(header = header) {
            entries.forEachIndexed { idx, entry ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Hidden entries dim, matching upstream's treatment;
                            // alpha is visual only so the row stays tappable to
                            // un-hide from the detail screen.
                            .then(if (entry.isHidden) Modifier.alpha(0.45f) else Modifier)
                            .combinedClickable(
                                onClick = { onModelEntryClick(entry.id) },
                                onLongClick = { menuEntryId = entry.id },
                            )
                            .heightIn(min = 72.dp)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ForkProviderDot(instance.providerType)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                entry.model.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            if (entry.isHidden) {
                                Text(
                                    stringResource(R.string.fork_models_hidden_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Text(
                            entry.model.id,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ForkModelCapabilityRow(entry.model)
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
                                AppLogger.info(
                                    "ForkModels",
                                    "entry ${entry.id} isHidden ${entry.isHidden} -> ${!entry.isHidden}",
                                )
                            },
                        )
                        // Only custom entries can be deleted — the repo
                        // re-creates built-ins from ProviderType.builtInModels
                        // on the next refresh, so "delete" on one would silently
                        // undo itself. Hiding is the supported way out.
                        if (entry.isCustom) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.common_delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuEntryId = null
                                    entryToDelete = entry
                                },
                            )
                        }
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

    if (showFetchSheet) {
        ProviderModelsFetchSheet(
            instance = instance,
            providerRepository = providerRepository,
            onDismiss = { showFetchSheet = false },
        )
    }

    entryToDelete?.let { e ->
        MinisAlertDialog(
            onDismissRequest = { entryToDelete = null },
            title = stringResource(R.string.provider_detail_delete_model),
            text = stringResource(R.string.provider_detail_delete_model_confirm, e.model.displayName),
            confirmText = stringResource(R.string.common_delete),
            isDestructive = true,
            onConfirm = {
                providerRepository.removeEntry(e.id)
                entryToDelete = null
            },
        )
    }
}
