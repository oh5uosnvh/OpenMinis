package com.openminis.app.ui.settings.mods

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

/** Where the finger has dragged this row to, outside the snapshot system. */
private class DragAccumulator(var value: Float)

/** How the row settles after release. No bounce — this is a drawer, not a toy. */
private val SettleSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * [FORK] Swipe-left-to-reveal a single square action button, kelivo-style.
 *
 * ## The bounce this version fixes
 *
 * The first cut rendered the row from `dragOffset ?: settled`, where
 * `dragOffset` was live finger state (null when not dragging) and `settled` came
 * from [androidx.compose.animation.core.animateFloatAsState]. Two sources of
 * truth for one position, and the handoff between them threw the finger's
 * position away:
 *
 *   finger at −60 → release → `open(id)` and `dragOffset = null` in the same
 *   handler → next frame reads `settled`, whose internal animation is still
 *   sitting at 0 and only NOW retargets to −72 → the row snaps back to rest and
 *   then slides open again.
 *
 * That is precisely the reported "滑到左边自动回弹右边再自动滑动回左边". It is not
 * a timing quirk to be tuned away: `animateFloatAsState` cannot be seeded, it
 * always animates from wherever its own Animatable happens to be, which is the
 * *settled* value and never the finger's. The closing direction had the mirror
 * image of the same flaw.
 *
 * So there is now exactly ONE source of truth: a single [Animatable] that the
 * drag writes into with `snapTo` and the release animates with `animateTo` from
 * whatever value it already holds. Nothing hands off, so there is nothing to
 * lose, and no offset the row can be at other than the one being animated.
 *
 * ## Other differences from upstream's SwipeRowActions
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

    // THE position. Seeded from the hoisted state so a row that is already open
    // when it enters composition (restored after a config change, or recycled
    // back into view by the LazyColumn) draws open instead of sliding open.
    val offset = remember(id) { Animatable(if (openState.isOpen(id)) -revealPx else 0f) }

    // Finger travel, deliberately NOT snapshot state: the drag must not
    // recompose anything, it only moves a layout offset.
    val drag = remember(id) { DragAccumulator(offset.value) }
    var dragging by remember(id) { mutableStateOf(false) }

    // Bumped on every release so the settle below re-runs even when the open/
    // closed state did not change (a small drag that snaps back to where it
    // started still has to animate back).
    var settleTick by remember(id) { mutableIntStateOf(0) }

    // The single settle path, for gestures AND for external changes such as
    // closeAll(). animateTo starts from the Animatable's current value, so
    // whichever of those triggered it, the row continues from where it is.
    LaunchedEffect(id, isOpen, revealPx, settleTick) {
        if (dragging) return@LaunchedEffect
        val target = if (isOpen) -revealPx else 0f
        if (offset.value != target) offset.animateTo(target, SettleSpec)
    }

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
                // Read in the LAYOUT phase, so neither the drag nor the settle
                // animation invalidates composition.
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                // Opaque: the action is painted behind, so a translucent row
                // would leave it permanently visible instead of revealed.
                .background(rowColor)
                .pointerInput(id, revealPx) {
                    // `coroutineScope` here rather than rememberCoroutineScope:
                    // the snapTo launches then belong to the gesture detector
                    // and die with it, instead of outliving it on the
                    // composition's scope. This is the shape the Compose
                    // gesture docs' swipe-to-dismiss sample uses.
                    coroutineScope {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                dragging = true
                                // Continue from the RENDERED position, which may
                                // be mid-settle: the first snapTo cancels that
                                // animation (Animatable serialises its mutators),
                                // so the row picks the finger up from where it
                                // visually is rather than jumping.
                                drag.value = offset.value
                            },
                            onHorizontalDrag = { _, delta ->
                                // Clamp to [-revealPx, 0]: no right-swipe past
                                // rest, no over-pull past the button.
                                drag.value = (drag.value + delta).coerceIn(-revealPx, 0f)
                                // Read the accumulator INSIDE the launch rather
                                // than capturing a snapshot of it: each drag
                                // event starts its own coroutine, so two could
                                // in principle run out of order, and a captured
                                // stale value would then win and jitter the row.
                                // Reading live means every ordering converges on
                                // the newest finger position.
                                launch { offset.snapTo(drag.value) }
                            },
                            onDragEnd = {
                                val shouldOpen = abs(drag.value) > revealPx / 2f
                                if (shouldOpen && !openState.isOpen(id)) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                dragging = false
                                if (shouldOpen) openState.open(id) else openState.close(id)
                                settleTick++
                            },
                            onDragCancel = {
                                dragging = false
                                settleTick++
                            },
                        )
                    }
                },
        ) {
            content()
        }
    }
}
