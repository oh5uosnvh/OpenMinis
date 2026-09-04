package com.openminis.app.ui.settings.mods

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisSmallTextButton
import kotlinx.coroutines.launch

/**
 * [FORK] The 获取模型 sheet — fetch the provider catalog, tick what you want,
 * confirm.
 *
 * The whole reason this exists: upstream's model refresh is fetch-and-replace
 * with no选择环节. Here the fetch writes NOTHING (see
 * [ProviderCatalogFetcher.fetchCatalog]); the only write happens when the user
 * presses 添加, and it is additive.
 *
 * Models already on the instance render as "已添加" and are non-selectable — the
 * alternative (silently pre-ticking them) would make the confirm button's count
 * a lie about what is about to change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderModelsFetchSheet(
    instance: ProviderInstance,
    providerRepository: ProviderRepository,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var catalog by remember { mutableStateOf<List<LLMModel>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var searchQuery by remember { mutableStateOf("") }
    var reloadTick by remember { mutableStateOf(0) }

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
    // Only rows the user can actually act on count towards 全选.
    val selectableIds = filtered.filter { it.id !in existingIds }.map { it.id }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
        ) {
            // ── Header ──────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
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
                if (selectableIds.isNotEmpty()) {
                    MinisSmallTextButton(
                        onClick = {
                            selected = if (selectableIds.all { it in selected }) {
                                selected - selectableIds.toSet()
                            } else {
                                selected + selectableIds
                            }
                        },
                    ) {
                        Text(
                            if (selectableIds.all { it in selected }) {
                                stringResource(R.string.fork_fetch_clear_selection)
                            } else {
                                stringResource(R.string.fork_fetch_select_all)
                            },
                        )
                    }
                }
            }

            // ── Search ──────────────────────────────────────────────────
            if (catalog.size >= 6) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.fork_models_search_placeholder)) },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
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
                        items(filtered, key = { it.id }) { model ->
                            val alreadyAdded = model.id in existingIds
                            val isSelected = model.id in selected
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (alreadyAdded) {
                                            Modifier
                                        } else {
                                            Modifier.clickable {
                                                selected = if (isSelected) {
                                                    selected - model.id
                                                } else {
                                                    selected + model.id
                                                }
                                            }
                                        },
                                    )
                                    .heightIn(min = 68.dp)
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (alreadyAdded) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                .copy(alpha = 0.4f),
                                            modifier = Modifier.size(20.dp),
                                        )
                                    } else {
                                        Icon(
                                            if (isSelected) Icons.Default.CheckCircle
                                            else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                                    .copy(alpha = 0.4f)
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
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (alreadyAdded) {
                                        Text(
                                            stringResource(R.string.fork_fetch_already_added),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Row(modifier = Modifier.padding(start = 30.dp)) {
                                    ForkModelCapabilityRow(model)
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 20.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
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
                        // getString on the Context, not a pre-resolved
                        // stringResource: the count is only known here, and
                        // patching a formatted string after the fact would
                        // corrupt any translation whose text contains the same
                        // digits.
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.fork_fetch_added_toast, added),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        scope.launch { sheetState.hide() }
                        onDismiss()
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
