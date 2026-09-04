package com.openminis.app.ui.settings.mods

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * [FORK] A ModalBottomSheet that closes ONLY on an explicit action — the
 * sheet's own Done / X button, or the device back gesture. Scrim taps and
 * swipe-down do nothing.
 *
 * ## Why the model list needs this
 *
 * A model picker is a list you scroll, search and read. With the default sheet
 * behaviour every one of those is a chance to lose it: a downward flick that
 * starts on a row (rather than inside the list's own scroll range) is handed to
 * the sheet's drag handler and dismisses it, and a tap that lands a few pixels
 * above the sheet hits the scrim and dismisses it too. On a long list, both
 * happen constantly, and the cost is not just the sheet — it is the search text
 * and scroll position that go with it.
 *
 * ## How the gate works
 *
 * Every dismissal route in material3's ModalBottomSheet is one of three:
 *
 *  1. Scrim tap → `animateToDismiss`, which is explicitly guarded by
 *     `confirmValueChange(Hidden)`. Veto ⇒ nothing happens at all.
 *  2. Drag / fling down → `settleToDismiss` → `AnchoredDraggableState.settle`,
 *     which asks `confirmValueChange(targetValue)` and, on a veto, animates
 *     back to the previous anchor. Veto ⇒ the sheet springs back, which reads
 *     as "it's pinned", not as a dropped frame.
 *  3. Back press → the dialog's own `onDismissRequest`, which calls
 *     `sheetState.hide()` and then `onDismissRequest()` UNCONDITIONALLY,
 *     bypassing `confirmValueChange` entirely.
 *
 * So a single predicate — "refuse to become Hidden" — blocks exactly (1) and
 * (2) while leaving (3) working. That is the requested behaviour precisely, and
 * it is why this needs no back-handler of its own.
 *
 * [close] is how the Done / X button gets out: it flips the veto off first, so
 * the normal hide animation runs and the sheet slides away instead of blinking
 * out of existence. Without that flip, `hide()` would animate the offset but
 * `currentValue` would never reach Hidden.
 *
 * `internal` because [SheetState] is an experimental material3 type: keeping it
 * out of the module's public API means the opt-in never has to propagate to
 * callers.
 */
@OptIn(ExperimentalMaterial3Api::class)
internal class ForkStickySheet(
    val state: SheetState,
    private val allowDismiss: MutableState<Boolean>,
    private val scope: CoroutineScope,
    private val dismiss: () -> Unit,
) {
    /** Animate the sheet away and notify the caller. The only programmatic exit. */
    fun close() {
        allowDismiss.value = true
        scope.launch {
            // hide() throws only when skipHiddenState is set (it is not here);
            // runCatching guards against a cancelled animation racing dismissal.
            runCatching { state.hide() }
            dismiss()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberForkStickySheet(onDismiss: () -> Unit): ForkStickySheet {
    val allowDismiss = remember { mutableStateOf(false) }
    // remember-ed so the lambda is ONE stable instance: rememberModalBottomSheetState
    // keys its rememberSaveable on confirmValueChange, so a fresh lambda per
    // recomposition would rebuild the SheetState and drop the sheet's position.
    val confirm = remember {
        { value: SheetValue -> value != SheetValue.Hidden || allowDismiss.value }
    }
    val state = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = confirm,
    )
    val scope = rememberCoroutineScope()
    val latestDismiss = rememberUpdatedState(onDismiss)
    return remember(state) {
        ForkStickySheet(state, allowDismiss, scope) { latestDismiss.value() }
    }
}
