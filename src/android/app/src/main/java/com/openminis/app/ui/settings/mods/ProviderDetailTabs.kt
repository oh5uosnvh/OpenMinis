package com.openminis.app.ui.settings.mods

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.model.normalizeModalities

/**
 * [FORK] The two fixed tabs at the bottom of the provider screen: 配置 / 模型.
 *
 * Modelled on kelive's provider page. The point of it being a PERSISTENT bar
 * rather than a segmented control at the top: on a page this long (label,
 * credential, endpoint, API format, Azure, image endpoint, status, voice,
 * thinking rules) the switcher has to stay reachable after the user has
 * scrolled — a top-anchored control scrolls away exactly when it is wanted.
 *
 * Lives in the fork's own file so upstream never touches it. The only upstream
 * seam it needs is `SettingsScaffold(bottomBar = …)`, one defaulted parameter.
 */
enum class ProviderDetailTab {
    CONFIG,
    MODELS,
}

@Composable
fun ProviderDetailBottomTabs(
    selected: ProviderDetailTab,
    onSelect: (ProviderDetailTab) -> Unit,
    /**
     * When false, render ONLY the tab row — no Surface, no hairline. The 模型
     * tab nests this inside a taller footer (action bar + tabs) that supplies
     * its own surface and divider; drawing them twice produced a double rule and
     * stacked tonal elevation.
     */
    chrome: Boolean = true,
) {
    if (!chrome) {
        TabRow(selected = selected, onSelect = onSelect)
        return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        // Hairline above the bar so it reads as chrome separated from the
        // scrolling content, not as another card in the list.
        tonalElevation = 3.dp,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
            TabRow(selected = selected, onSelect = onSelect)
        }
    }
}

@Composable
private fun TabRow(
    selected: ProviderDetailTab,
    onSelect: (ProviderDetailTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabItem(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.Settings,
            label = stringResource(R.string.fork_tab_config),
            selected = selected == ProviderDetailTab.CONFIG,
            onClick = { onSelect(ProviderDetailTab.CONFIG) },
        )
        TabItem(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.Layers,
            label = stringResource(R.string.fork_tab_models),
            selected = selected == ProviderDetailTab.MODELS,
            onClick = { onSelect(ProviderDetailTab.MODELS) },
        )
    }
}

@Composable
private fun TabItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
        )
    }
}

/**
 * [FORK] Brand avatar for a model row — a circular tinted disc holding the
 * vendor logo, or the model's initial letter when no logo matches.
 *
 * Port of kelivo's `_BrandAvatar` (provider_detail_page.dart): same circle,
 * same primary-tinted background, same initial-letter fallback, same
 * monochrome-logo tinting for dark surfaces.
 */
@Composable
fun ForkBrandAvatar(
    modelId: String?,
    displayName: String? = null,
    providerName: String? = null,
    size: Dp = 28.dp,
    /**
     * Optional status ring colour — the 模型 tab uses it to show 测活 results
     * without adding a second glyph to an already busy row.
     */
    ringColor: Color? = null,
) {
    val res = ForkBrandIcons.forModel(modelId, displayName, providerName)
    val bg = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    Box(
        modifier = Modifier
            .size(size)
            .background(bg, CircleShape)
            .then(
                if (ringColor != null) {
                    Modifier.border(1.5.dp, ringColor, CircleShape)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (res != null) {
            Icon(
                painter = painterResource(res),
                contentDescription = null,
                // A full-colour logo must be drawn as-is; a single-colour
                // silhouette has to be tinted or it vanishes on a dark surface.
                tint = if (ForkBrandIcons.isMonochrome(res)) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    Color.Unspecified
                },
                modifier = Modifier.size(size * 0.62f),
            )
        } else {
            Text(
                text = (modelId ?: displayName ?: "?").trim().take(1).uppercase(),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}

/**
 * [FORK] Small pill used on model rows for capability tags (chat / think /
 * img / audio …). Same visual recipe as upstream's `ModalityBadge` so the two
 * lists don't read as different apps, but declared here to keep the fork's
 * files self-contained.
 */
@Composable
fun ForkCapabilityChip(text: String, emphasized: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        softWrap = false,
        color = if (emphasized) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .background(
                if (emphasized) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                },
                RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * [FORK] Capability tags for one model, in a stable order.
 * `chat` is unconditional — every entry in this list is a chat-capable model —
 * followed by reasoning, then the non-text modalities.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ForkModelCapabilityRow(model: com.openminis.app.data.model.LLMModel) {
    val chat = stringResource(R.string.fork_chip_chat)
    val think = stringResource(R.string.fork_chip_reasoning)
    val ins = model.inputModalities.normalizeModalities().orEmpty()
    val outs = model.outputModalities.normalizeModalities().orEmpty()
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ForkCapabilityChip(chat)
        if (model.supportsReasoning == true) ForkCapabilityChip(think, emphasized = true)
        if ("image" in ins) ForkCapabilityChip("img")
        if ("pdf" in ins) ForkCapabilityChip("pdf")
        if ("audio" in ins) ForkCapabilityChip("audio")
        if ("video" in ins) ForkCapabilityChip("video")
        if ("image" in outs) ForkCapabilityChip("img-out", emphasized = true)
        if ("audio" in outs) ForkCapabilityChip("audio-out", emphasized = true)
        if ("video" in outs) ForkCapabilityChip("video-out", emphasized = true)
    }
}

/**
 * [FORK] Provider accent dot. Kept for the places that want a minimal marker
 * rather than a full brand avatar.
 */
@Composable
fun ForkProviderDot(providerType: com.openminis.app.data.model.ProviderType?) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(
                com.openminis.app.ui.components.providerDotColor(providerType),
                CircleShape,
            ),
    )
}
