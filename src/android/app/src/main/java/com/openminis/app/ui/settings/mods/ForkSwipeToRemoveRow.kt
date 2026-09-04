package com.openminis.app.ui.settings.mods

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * [FORK] Which rows are currently swiped open, hoisted OUT of the rows.
 *
 * ## Why the state cannot live inside the row
 *
 * The requirement is: swipe rows 1, 3 and 5 open, tap 1's Remove, and rows 3
 * and 5 must STAY open so the user can keep tapping. With per-row state that is
 * impossible in a keyed list — removing item 1 makes Compose dispose that
 * composable and re-map keys, and any sibling whose slot index shifted gets its
 * `remember` re-initialised, snapping it shut. That is exactly the behaviour the
 * user described as wrong.
 *
 * Keying the open-set by ENTRY ID in a parent-owned holder side-steps it: ids
 * are stable and independent of position, so removing one entry only drops its
 * own id from the set. Every other id keeps its value across the recomposition,
 * and rows re-read "am I open" from the holder rather than from their own
 * lifecycle.
 *
 * [rememberSaveable] of the id set additionally survives configuration change.
 */
class ForkSwipeOpenState internal constructor(
    private val openIds: androidx.compose.runtime.MutableState<Set<String>>,
) {
    fun isOpen(id: String): Boolean = id in openIds.value

    fun open(id: String) {
        if (id !in openIds.value) openIds.value = openIds.value + id
    }

    fun close(id: String) {
        if (id in openIds.value) openIds.value = openIds.value - id
    }

    /** Drop an id entirely — call after the row's item is deleted. */
    fun forget(id: String) = close(id)

    fun closeAll() {
        if (openIds.value.isNotEmpty()) openIds.value = emptySet()
    }

    val hasOpen: Boolean get() = openIds.value.isNotEmpty()
}

@Composable
fun rememberForkSwipeOpenState(): ForkSwipeOpenState {
    val ids = rememberSaveable(
        saver = androidx.compose.runtime.saveable.Saver<androidx.compose.runtime.MutableState<Set<String>>, List<String>>(
            save = { it.value.toList() },
            restore = { mutableStateOf(it.toSet()) },
        ),
    ) { mutableStateOf(emptySet<String>()) }
    return remember { ForkSwipeOpenState(ids) }
}

/**
 * [FORK] Swipe-left-to-reveal a single square action button, kelive-style.
 *
 * Differences from upstream's [com.openminis.app.ui.components.SwipeRowActions],
 * which is why this is a separate component rather than a parameter on it:
 *
 *  - **Open state is hoisted** into [openState] (see its docs). Upstream's
 *    version keeps an `AnchoredDraggableState` per row, so it cannot express
 *    "stay open while a sibling is deleted".
 *  - **The action does NOT auto-close the row.** Upstream animates back to
 *    resting before invoking the callback, because its actions navigate away or
 *    open dialogs. Here the action deletes the row outright, so closing it first
 *    is both pointless and visibly wrong when the user is working down a list of
 *    several opened rows.
 *  - **One square button**, sized to the row height, matching the reference UI.
 *
 * The gesture is a plain `detectHorizontalDragGestures` rather than
 * `anchoredDraggable`: the anchor machinery only pays for itself when a row
 * owns its state, which is precisely what is being avoided here. Drag tracks the
 * finger, release snaps to open/closed by a half-width threshold, and the
 * settled offset is derived from [openState] so an externally-driven change
 * (closeAll) animates correctly.
 */
@Composable
fun ForkSwipeToRemoveRow(
    id: String,
    openState: ForkSwipeOpenState,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    actionWidth: Dp = 72.dp,
    actionIcon: ImageVector,
    actionLabel: String,
    containerColor: Color = MaterialTheme.colorScheme.error,
    contentColor: Color = MaterialTheme.colorScheme.onError,
    rowColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val revealPx = with(density) { actionWidth.toPx() }

    val isOpen = openState.isOpen(id)

    // Live finger offset while dragging; null when not dragging, so the
    // animated settled position takes over.
    var dragOffset by remember(id) { mutableStateOf<Float?>(null) }
    val settled by animateFloatAsState(
        targetValue = if (isOpen) -revealPx else 0f,
        label = "forkSwipeSettle",
    )
    val offsetPx = dragOffset ?: settled

    var rowHeightPx by remember { mutableIntStateOf(0) }
    val rowHeight = with(density) { rowHeightPx.toDp() }

    Box(modifier = modifier.clipToBounds()) {
        // Action sits UNDER the row at the trailing edge, square to row height.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(actionWidth)
                .then(if (rowHeightPx > 0) Modifier.height(rowHeight) else Modifier.fillMaxHeight())
                .background(containerColor)
                // Tapping it removes immediately — no confirmation dialog, and
                // no close animation first. The row simply disappears from the
                // list on the next recomposition; siblings that are open stay
                // open because their state lives in [openState], not here.
                .clickable {
                    openState.forget(id)
                    onRemove()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                actionIcon,
                contentDescription = actionLabel,
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowHeightPx = it.height }
                // offset{} is read in the LAYOUT phase, so dragging never
                // invalidates composition.
                .offset { IntOffset(offsetPx.roundToInt(), 0) }
                // Opaque: the action is painted behind, so a translucent row
                // would leave it permanently visible instead of revealed.
                .background(rowColor)
                .pointerInput(id, revealPx) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragOffset = if (openState.isOpen(id)) -revealPx else 0f
                        },
                        onHorizontalDrag = { _, delta ->
                            val base = dragOffset ?: 0f
                            // Clamp to [-revealPx, 0]: no right-swipe past rest,
                            // no over-pull past the button.
                            dragOffset = (base + delta).coerceIn(-revealPx, 0f)
                        },
                        onDragEnd = {
                            val end = dragOffset ?: 0f
                            val shouldOpen = abs(end) > revealPx / 2f
                            if (shouldOpen && !openState.isOpen(id)) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            if (shouldOpen) openState.open(id) else openState.close(id)
                            dragOffset = null
                        },
                        onDragCancel = { dragOffset = null },
                    )
                },
        ) {
            content()
        }
    }
}
