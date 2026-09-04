package com.openminis.app.ui.settings.mods

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * [FORK] A bottom sheet with NO drag surface at all.
 *
 * ## Why this replaced the earlier confirmValueChange approach
 *
 * The first cut kept material3's `ModalBottomSheet` and vetoed the Hidden state
 * via `confirmValueChange`. That stops the sheet from *closing*, but it cannot
 * stop it from *moving* — and the movement was the actual complaint: swiping a
 * model card made the whole sheet shudder.
 *
 * In material3 1.3.2 the sheet exposes two independent drag surfaces onto the
 * same offset:
 *
 *  1. `nestedScroll(ConsumeSwipeWithinBottomSheetBounds…)`, whose `onPostScroll`
 *     hands every pixel the inner LazyColumn did not consume to
 *     `dispatchRawDelta`;
 *  2. a `.draggable(orientation = Vertical)` on the sheet Surface itself.
 *
 * `dispatchRawDelta` assigns `offset` DIRECTLY and never consults
 * `confirmValueChange`. So for any swipe with a vertical component — which is
 * every real finger swipe, a "horizontal" one included — the sequence was:
 * offset moves → sheet visibly slides → finger lifts → `settle()` targets
 * Hidden → veto → `animateTo(previousValue)` springs it back. The veto did not
 * prevent the bounce, it *guaranteed* one.
 *
 * Tuning that is a dead end: any fix must leave the offset alone, and the offset
 * belongs to those two modifiers. So this is built on `Dialog` instead — a plain
 * window with a bottom-aligned Surface. No AnchoredDraggableState, no
 * nested-scroll interception, no offset to perturb, so a horizontal swipe on a
 * row cannot move the container. kelivo's sheets are not drag-to-dismiss either;
 * they close on ✕ and on system back, which is what was asked for.
 *
 * `DialogProperties` provides the semantics natively rather than by veto:
 *   dismissOnBackPress    = true   → device back closes it
 *   dismissOnClickOutside = false  → scrim taps do nothing
 *
 * A Dialog has no built-in transition, so the slide in/out is animated here.
 * [ForkBottomSheetState.close] plays the exit BEFORE invoking `onDismiss`, so
 * the sheet slides away instead of blinking out.
 */
@Stable
class ForkBottomSheetState internal constructor() {
    /** false while the exit animation runs. */
    internal var visible by mutableStateOf(false)

    /** Set by [close]; the host then animates out and calls onDismiss. */
    internal var closing by mutableStateOf(false)

    /** Animate the sheet away, then notify the caller. The programmatic exit. */
    fun close() {
        closing = true
        visible = false
    }
}

@Composable
fun rememberForkBottomSheetState(): ForkBottomSheetState = remember { ForkBottomSheetState() }

/** Duration of both the enter and the exit slide. */
private const val SHEET_ANIM_MS = 260

/**
 * Bottom-sheet container. See [ForkBottomSheetState] for why this is a Dialog
 * and not a ModalBottomSheet.
 *
 * @param onDismiss invoked after the exit animation (from [state].close()), or
 *   immediately when the device back gesture fires.
 * @param heightFraction how much of the window height the sheet occupies.
 */
@Composable
fun ForkBottomSheet(
    state: ForkBottomSheetState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    heightFraction: Float = 0.92f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val latestDismiss by rememberUpdatedState(onDismiss)

    // Enter on first composition; `visible` starts false so the first frame is
    // off-screen and the slide-in is actually seen.
    LaunchedEffect(Unit) { state.visible = true }

    // Exit: wait out the animation, then tell the caller to drop us.
    LaunchedEffect(state) {
        snapshotFlow { state.closing }.collect { closing ->
            if (closing) {
                delay(SHEET_ANIM_MS.toLong())
                latestDismiss()
            }
        }
    }

    val slide by animateFloatAsState(
        targetValue = if (state.visible) 0f else 1f,
        animationSpec = tween(durationMillis = SHEET_ANIM_MS),
        label = "forkSheetSlide",
    )
    val scrimAlpha by animateFloatAsState(
        targetValue = if (state.visible) 0.42f else 0f,
        animationSpec = tween(durationMillis = SHEET_ANIM_MS),
        label = "forkSheetScrim",
    )

    Dialog(
        // Device back — the window's own dismissal path, no veto involved.
        onDismissRequest = latestDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            // The sheet must span the full width and reach the window edges,
            // which the platform default width would prevent.
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                modifier = modifier
                    .fillMaxWidth()
                    .fillMaxHeight(heightFraction)
                    .graphicsLayer {
                        // Translate rather than animate a layout value: the slide
                        // then costs nothing in measure/layout, which matters
                        // with a long LazyColumn inside.
                        translationY = slide * size.height
                    },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding(),
                    content = content,
                )
            }
        }
    }
}

/** Slim grab handle, drawn for familiarity — decorative, not draggable. */
@Composable
fun ForkSheetHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .width(34.dp)
                .height(4.dp)
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    RoundedCornerShape(2.dp),
                ),
        )
    }
}
