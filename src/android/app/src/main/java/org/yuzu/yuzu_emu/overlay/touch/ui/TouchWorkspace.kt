// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.ui

import android.content.Context
import org.yuzu.yuzu_emu.R
import android.graphics.RectF
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.yuzu.yuzu_emu.overlay.touch.model.ControlShape
import org.yuzu.yuzu_emu.overlay.touch.model.ControlType
import org.yuzu.yuzu_emu.overlay.touch.model.ButtonAction
import org.yuzu.yuzu_emu.overlay.touch.model.ScreenLayout
import org.yuzu.yuzu_emu.overlay.touch.model.StickTarget
import org.yuzu.yuzu_emu.overlay.touch.model.SwipePreset
import org.yuzu.yuzu_emu.overlay.touch.model.TouchControl
import org.yuzu.yuzu_emu.overlay.touch.model.TouchDefaults
import org.yuzu.yuzu_emu.overlay.touch.model.TouchGeometry
import org.yuzu.yuzu_emu.overlay.touch.model.TouchLibrary
import org.yuzu.yuzu_emu.overlay.touch.model.TouchRect
import org.yuzu.yuzu_emu.overlay.touch.model.TouchTemplate
import org.yuzu.yuzu_emu.overlay.touch.model.newTouchId
import org.yuzu.yuzu_emu.overlay.touch.runtime.InputSink
import org.yuzu.yuzu_emu.overlay.touch.runtime.TouchHitGeometry
import org.yuzu.yuzu_emu.overlay.touch.runtime.TouchRuntime
import org.yuzu.yuzu_emu.overlay.touch.store.TouchEditSession
import org.yuzu.yuzu_emu.overlay.touch.store.RemovedControl
import org.yuzu.yuzu_emu.overlay.touch.store.TouchSanitizer
import org.yuzu.yuzu_emu.overlay.touch.store.TouchValidationIssue
import org.yuzu.yuzu_emu.overlay.touch.store.TouchValidator
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.features.settings.model.IntSetting
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class TouchWorkspace(context: Context) : FrameLayout(context) {
    var onSave: ((TouchLibrary, String) -> Unit)? = null
    var onExit: (() -> Unit)? = null
    var onSanitized: ((TouchLibrary) -> Unit)? = null
    internal var removedOnBind: List<RemovedControl> = emptyList()
        private set
    var onTestState: ((Boolean) -> Unit)? = null
    var onTouchActivity: ((MotionEvent) -> Unit)? = null
    var gameId: String? = null
    var standalonePresetId: String? = null
        private set
    val draftBaselineRevision: Long get() = saved.revision
    val baselineRevision: Long get() = saved.revision
    private var inputsSuspended = false
    private var focusSuspended = false
    var isEditing: Boolean = true
        private set
    val isTesting: Boolean get() = testing
    private val vibrator by lazy {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    }
    private var requestedControlsVisible = true
    var controlsVisible: Boolean
        get() = requestedControlsVisible && !layoutRetired
        set(value) {
            if (requestedControlsVisible == value) return
            releaseInputs()
            requestedControlsVisible = value
            configureRuntime()
            canvas.invalidate()
        }
    var touchscreenEnabled: Boolean = true
        set(value) {
            field = value
            runtime.setTouchscreenEnabled(value && isolatedId == null)
        }
    private var controlsHidden = false
    internal data class LastGeometry(
        val bounds: TouchRect,
        val shape: ControlShape,
        val stickDiameter: Float
    )
    private val lastGeometryByType = mutableMapOf<ControlType, LastGeometry>()
    var snapToGrid: Boolean = runCatching {
        BooleanSetting.OVERLAY_SNAP_TO_GRID.getBoolean()
    }.getOrDefault(true)
        private set

    internal fun toggleSnapToGrid() {
        snapToGrid = !snapToGrid
        runCatching { BooleanSetting.OVERLAY_SNAP_TO_GRID.setBoolean(snapToGrid) }
        tools.refreshToolbar()
        canvas.invalidate()
    }

    internal fun gridStep(): Float = runCatching {
        IntSetting.OVERLAY_GRID_SIZE.getInt().coerceIn(16, 128).toFloat()
    }.getOrElse { dp(16) }

    internal fun toggleControlsVisibility() {
        controlsHidden = !controlsHidden
        canvas.invalidate()
    }

    internal fun isControlVisible(control: TouchControl): Boolean {
        if (isEditing || testing) return true
        if (!controlsVisible) return false
        if (!controlsHidden) return true
        return (control.buttonAction == ButtonAction.TOGGLE_VISIBILITY || control.type == ControlType.VISIBILITY) &&
            !control.hideSelf
    }

    internal fun switchGameTemplate(id: String) {
        if (!gameAvailable || isEditing || id == templateId) return
        if (draft.templates.none { it.id == id }) return
        releaseInputs()
        templateId = id
        selectedId = null
        controlsHidden = false
        updateGeometry()
        tools.refreshToolbar()
        canvas.invalidate()
        postFrame()
    }

    fun acceptSaved(library: TouchLibrary) {
        require(library.templates.any { it.id == templateId }) { context.getString(R.string.touch_workspace_missing_saved) }
        saved = library
        draft = library
        undo.clear()
        redo.clear()
        updateGeometry()
    }

    fun exportDraft(): String = Json.encodeToString(
        TouchWorkspaceDraft(
            gesture?.let(::rollbackGeometry) ?: draft,
            saved,
            templateId,
            selectedId,
            isEditing || testing,
            tools.exportState(),
            standalonePresetId
        )
    )

    fun restoreDraft(serialized: String) {
        val state = Json { ignoreUnknownKeys = true }.decodeFromString<TouchWorkspaceDraft>(serialized)
        state.requireRestorable(saved.revision)
        releaseInputs()
        tools.closePanel()
        draft = state.draft
        saved = state.saved
        templateId = state.templateId
        standalonePresetId = state.standalonePresetId
        selectedId = state.selectedId?.takeIf { id -> template.controls.any { it.id == id } }
        testing = false
        isolatedId = null
        debugBefore = null
        isEditing = state.editing
        tools.visibility = if (isEditing) VISIBLE else GONE
        onTestState?.invoke(false)
        updateGeometry()
        tools.restoreState(state.tools)
        if (!isEditing) postFrame()
    }

    fun editPreset(presetId: String, standalone: Boolean = false) {
        standalonePresetId = presetId.takeIf { standalone }
        require(draft.presets.any { it.id == presetId }) { context.getString(R.string.touch_workspace_missing_preset) }
        if (!isEditing) setEditing(true)
        tools.showTuning(presetId, false)
    }

    internal var draft = TouchDefaults.library()
        private set
    internal var templateId = draft.templates.first().id
        private set
    internal val template: TouchTemplate get() = draft.templates.first { it.id == templateId }
    internal var selectedId: String? = null
    internal val selected: TouchControl? get() = template.controls.firstOrNull { it.id == selectedId }
    internal var gameAvailable = false
        private set
    internal var testing = false
        private set
    internal var isolatedId: String? = null
        private set
    internal val safe = RectF()
    internal var placements = emptyMap<String, TouchRect>()
        private set
    internal val runtime = TouchRuntime(object : InputSink {
        override fun reset() { output.reset() }
        override fun sync() { if (gameAvailable && !isEditing) output.sync() }
        override fun buttons(pressed: Set<Int>) { if (gameAvailable && !isEditing) output.buttons(pressed) }
        override fun stick(target: StickTarget, x: Float, y: Float) {
            if (gameAvailable && !isEditing) output.stick(target, x, y)
        }
        override fun touchDown(pointerId: Int, x: Float, y: Float) {
            if (gameAvailable && !isEditing) output.touchDown(pointerId, x + safe.left, y + safe.top)
        }
        override fun touchMove(pointerId: Int, x: Float, y: Float) {
            if (gameAvailable && !isEditing) output.touchMove(pointerId, x + safe.left, y + safe.top)
        }
        override fun touchUp(pointerId: Int) { if (gameAvailable && !isEditing) output.touchUp(pointerId) }
        override fun haptic(control: TouchControl, strong: Boolean) {
            if (!gameAvailable || isEditing || !control.haptics) return
            if (!BooleanSetting.HAPTIC_FEEDBACK.getBoolean()) return
            val duration = if (strong) DOUBLE_TAP_HAPTIC_MS else NORMAL_HAPTIC_MS
            val device = vibrator
            if (device?.hasVibrator() == true) {
                device.vibrate(
                    VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
                )
                return
            }
            performHapticFeedback(
                if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY
            )
        }
        override fun toggleVisibility() {
            if (gameAvailable && !isEditing) toggleControlsVisibility()
        }
        override fun switchTemplate(templateId: String) {
            if (gameAvailable && !isEditing) {
                post { if (gameAvailable && !isEditing) switchGameTemplate(templateId) }
            }
        }
    })
    private var output: InputSink = object : InputSink {}
    private var saved = draft
    private sealed interface UndoEntry {
        data class Full(val library: TouchLibrary) : UndoEntry
        data class Window(val change: TouchWindowChange) : UndoEntry
    }
    private val undo = ArrayDeque<UndoEntry>()
    private val redo = ArrayDeque<UndoEntry>()
    private val canvas = TouchCanvas(context, this)
    internal val tools = TouchTools(context, this)
    private val placementCancel = MaterialButton(
        ContextThemeWrapper(context, R.style.TouchButton_Theme_Secondary),
        null,
        com.google.android.material.R.attr.materialButtonStyle
    ).apply {
        text = context.getString(R.string.touch_workspace_cancel_placement)
        contentDescription = text
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        minHeight = dp(40).toInt()
        visibility = GONE
        setOnClickListener { cancelPlacement() }
    }
    private val canvasPointers = mutableSetOf<Int>()
    private val allPointers = mutableSetOf<Int>()
    private var geometryAvailable = true
    private var layoutRetired = false
    private var waitForFreshDown = false
    private var pendingControl: TouchControl? = null
    private var adjustSelected = false
    private var gesture: GeometryGesture? = null
    private var debugBefore: TouchLibrary? = null
    private var lastWidth = 0f
    private var lastHeight = 0f
    private var insetsLeft = 0
    private var insetsTop = 0
    private var insetsRight = 0
    private var insetsBottom = 0
    private var framePosted = false
    private val frame = object : Runnable {
        override fun run() {
            framePosted = false
            if (!isAttachedToWindow || inputsSuspended || focusSuspended || windowVisibility != VISIBLE || visibility != VISIBLE) return
            if (!isEditing) {
                val active = runtime.hasActiveInputs()
                runtime.tick(SystemClock.uptimeMillis())
                if (active) canvas.invalidate()
                postFrame()
            }
        }
    }
    private data class GeometryGesture(
        val pointer: Int,
        val startX: Float,
        val startY: Float,
        val original: TouchRect,
        val before: TouchLibrary,
        val handle: Int,
        val controlId: String? = null,
        val creating: Boolean = false,
        var moving: Boolean = false,
        var accepted: TouchRect = original
    )

    init {
        clipChildren = false
        isMotionEventSplittingEnabled = true
        addView(canvas, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(tools, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            placementCancel,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(20).toInt()
            }
        )
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            tools.onImeInsets(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            val changed = insetsLeft != bars.left || insetsTop != bars.top ||
                insetsRight != bars.right || insetsBottom != bars.bottom
            insetsLeft = bars.left
            insetsTop = bars.top
            insetsRight = bars.right
            insetsBottom = bars.bottom
            if (changed) { releaseInputs(); updateGeometry() }
            insets
        }
        tools.refreshToolbar()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.addAccessibilityAction(this, context.getString(R.string.touch_workspace_accessibility_tools)) { _, _ ->
            releaseInputs()
            pendingControl = null
            adjustSelected = false
            showTools()
            true
        }
    }

    fun bind(library: TouchLibrary, templateId: String, gameAvailable: Boolean, sink: InputSink) {
        releaseInputs()
        val sanitized = TouchSanitizer.sanitize(library, resources.displayMetrics.density)
        removedOnBind = sanitized.removed
        require(sanitized.library.templates.any { it.id == templateId }) { context.getString(R.string.touch_workspace_missing_template) }
        layoutRetired = false
        this.draft = sanitized.library
        this.saved = sanitized.library
        this.templateId = templateId
        this.gameAvailable = gameAvailable
        output = sink
        undo.clear()
        redo.clear()
        selectedId = null
        testing = false
        isolatedId = null
        controlsHidden = false
        tools.closePanel()
        updateGeometry()
        tools.refreshToolbar()
        if (sanitized.removed.isNotEmpty()) {
            onSanitized?.invoke(sanitized.library)
            post { reportRemoved(sanitized.removed) }
        }    }

    private fun reportRemoved(removed: List<RemovedControl>) {
        val details = removed.joinToString("\n") { "「${it.name}」：${it.reason}" }
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.touch_workspace_removed_title))
            .setMessage(context.getString(R.string.touch_workspace_removed_message, details))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun setEditing(editing: Boolean) {
        if (editing && layoutRetired) return
        releaseInputs()
        if (!editing && pendingControl != null) {
            pendingControl = null
            canvas.preview = null
            placementCancel.visibility = GONE
        }
        isEditing = editing
        testing = false
        isolatedId = null
        runtime.setIsolatedSwipe(null)
        tools.closePanel()
        tools.visibility = if (editing) VISIBLE else GONE
        if (editing) onTestState?.invoke(false)
        configureRuntime()
        canvas.invalidate()
        if (!editing) postFrame()
    }

    fun retireLayout() {
        releaseInputs()
        if (testing) finishTest(false)
        layoutRetired = true
        isEditing = false
        pendingControl = null
        placementCancel.visibility = GONE
        tools.closeAll()
        tools.visibility = GONE
        configureRuntime()
        canvas.invalidate()
    }

    fun suspendInputs() {
        inputsSuspended = true
        removeCallbacks(frame)
        framePosted = false
        if (testing) finishTest(false) else releaseInputs()
    }

    fun resumeInputs() {
        inputsSuspended = false
        postFrame()
    }

    fun releaseInputs() {
        runtime.cancelAll()
        canvasPointers.clear()
        gesture?.let {
            draft = rollbackGeometry(it)
            placements = TouchGeometry.placements(template, safe.width().coerceAtLeast(1f), safe.height().coerceAtLeast(1f))
        }
        gesture = null
        canvas.preview = null
        waitForFreshDown = true
        canvas.invalidate()
    }

    internal fun requestExit() {
        if (handleBack()) return
        onExit?.invoke()
    }

    fun handleBack(): Boolean {
        if (ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(windowToken, 0)
            return true
        }
        if (gesture != null) {
            cancelGeometry()
            showTools()
            return true
        }
        if (pendingControl != null) {
            cancelPlacement()
            return true
        }
        if (testing) {
            finishTest(false)
            return true
        }
        if (!isEditing) return false
        if (tools.hasPanel) {
            tools.closeTopWindow(false)
            return true
        }
        if (draft != saved) {
            MaterialAlertDialogBuilder(context).setTitle(context.getString(R.string.touch_workspace_unsaved_title))
                .setMessage(context.getString(R.string.touch_workspace_unsaved_message))
                .setPositiveButton(context.getString(R.string.touch_workspace_save)) { _, _ -> save(true) }
                .setNegativeButton(context.getString(R.string.touch_workspace_discard)) { _, _ ->
                    releaseInputs()
                    draft = saved
                    updateGeometry()
                    onExit?.invoke()
                }
                .setNeutralButton(context.getString(R.string.touch_workspace_keep_editing), null).show()
        } else {
            onExit?.invoke()
        }
        return true
    }

    internal fun checkpoint(before: TouchLibrary = draft) {
        val entry = UndoEntry.Full(before)
        if (undo.lastOrNull() != entry) undo.addLast(entry)
        while (undo.size > 80) undo.removeFirst()
        redo.clear()
    }

    internal fun checkpointWindow(before: TouchWindowSnapshot, after: TouchWindowSnapshot) {
        val change = TouchWindowChange(templateId, before, after)
        if (!change.changes(draft)) return
        undo.addLast(UndoEntry.Window(change))
        while (undo.size > 80) undo.removeFirst()
        redo.clear()
    }

    internal fun replaceDraft(value: TouchLibrary) {
        draft = value
        updateGeometry(false)
        tools.refreshToolbar()
    }

    internal fun setCurrentScreenAsBase() {
        MaterialAlertDialogBuilder(context).setTitle(context.getString(R.string.touch_workspace_base_title))
            .setMessage(context.getString(R.string.touch_workspace_base_message))
            .setNegativeButton(context.getString(R.string.touch_workspace_cancel), null).setPositiveButton(context.getString(R.string.touch_workspace_set_base)) { _, _ ->
                checkpoint()
                val current = currentScreen().copy(placements = placements)
                val screens = template.screens.filterNot { it.id == current.id }.map { screen ->
                    screen.copy(
                        placements = template.controls.associate { control ->
                            control.id to TouchGeometry.savedBounds(template, screen, control)
                        }
                    )
                } + current
                replaceTemplate(
                    template.copy(
                        screens = screens,
                        baseScreenId = current.id,
                        controls = template.controls.map { it.copy(bounds = placements[it.id] ?: it.bounds) }
                    )
                )
            }.show()
    }

    internal fun updateControl(value: TouchControl): Boolean {
        if (value.isVisibleStick) {
            val diameter = value.stickDiameter * resources.displayMetrics.density
            val screens = template.screens.map { screen ->
                val current = TouchGeometry.savedBounds(template, screen, value)
                val centerX = current.x + current.width / 2f
                val centerY = current.y + current.height / 2f
                screen.copy(
                    placements = screen.placements + (value.id to TouchRect(
                        centerX - diameter / 2f,
                        centerY - diameter / 2f,
                        diameter,
                        diameter
                    ))
                )
            }
            val centerX = value.bounds.x + value.bounds.width / 2f
            val centerY = value.bounds.y + value.bounds.height / 2f
            replaceTemplate(
                template.copy(
                    screens = screens,
                    controls = template.controls.map {
                        if (it.id == value.id) {
                            value.copy(
                                bounds = TouchRect(
                                    centerX - diameter / 2f,
                                    centerY - diameter / 2f,
                                    diameter,
                                    diameter
                                )
                            )
                        } else {
                            it
                        }
                    }
                )
            )
            return true
        }
        val makeCircle = value.type == ControlType.BUTTON && value.shape == ControlShape.CIRCLE
        if (makeCircle) {
            val screens = template.screens.map { screen ->
                val current = TouchGeometry.savedBounds(template, screen, value)
                val size = min(current.width, current.height)
                screen.copy(placements = screen.placements + (value.id to current.copy(width = size, height = size)))
            }
            val size = min(value.bounds.width, value.bounds.height)
            val circular = value.copy(bounds = value.bounds.copy(width = size, height = size))
            replaceTemplate(
                template.copy(
                    screens = screens,
                    controls = template.controls.map { if (it.id == value.id) circular else it }
                )
            )
        } else {
            replaceTemplate(template.copy(controls = template.controls.map { if (it.id == value.id) value else it }))
        }
        return true
    }

    internal fun replaceTemplate(value: TouchTemplate) {
        draft = draft.copy(templates = draft.templates.map { if (it.id == value.id) value else it })
        updateGeometry(false)
        tools.refreshToolbar()
    }

    internal fun updatePreset(value: SwipePreset) {
        draft = draft.copy(presets = draft.presets.map { if (it.id == value.id) value else it })
        runtime.updatePresets(draft.presets)
        canvas.invalidate()
    }

    internal fun updateGlobalOpacity(value: Float) {
        replaceTemplate(template.copy(globalOpacity = value.coerceIn(0f, 1f)))
        canvas.invalidate()
    }

    internal fun applyAllColors(color: Int?) {
        replaceTemplate(
            template.copy(
                globalColor = color,
                controls = template.controls.map { it.copy(color = color) }
            )
        )
        canvas.invalidate()
    }

    internal fun overrideGlobalOpacity() {
        val opacity = template.globalOpacity
        replaceTemplate(
            template.copy(
                globalOpacity = 1f,
                controls = template.controls.map { it.copy(opacity = opacity) }
            )
        )
        canvas.invalidate()
    }

    internal fun addPreset(value: SwipePreset) {
        draft = draft.copy(presets = draft.presets + value)
        runtime.updatePresets(draft.presets)
    }

    internal fun undo() {
        if (undo.isEmpty()) return
        val entry = undo.removeLast()
        when (entry) {
            is UndoEntry.Full -> { redo.addLast(UndoEntry.Full(draft)); replaceDraft(entry.library) }
            is UndoEntry.Window -> { redo.addLast(entry); replaceDraft(entry.change.undo(draft)) }
        }
        tools.historyApplied((entry as? UndoEntry.Window)?.change, true)
    }

    internal fun redo() {
        if (redo.isEmpty()) return
        val entry = redo.removeLast()
        when (entry) {
            is UndoEntry.Full -> { undo.addLast(UndoEntry.Full(draft)); replaceDraft(entry.library) }
            is UndoEntry.Window -> { undo.addLast(entry); replaceDraft(entry.change.redo(draft)) }
        }
        tools.historyApplied((entry as? UndoEntry.Window)?.change, false)
    }

    internal fun select(id: String) {
        if (layoutRetired) return
        template.controls.firstOrNull { it.id == id }?.let(::rememberGeometry)
        selectedId = id
        tools.refreshToolbar()
        canvas.invalidate()
        if (isEditing) tools.onControlSelected(id)
    }

    private fun rememberGeometry(control: TouchControl) {
        lastGeometryByType[control.type] = LastGeometry(
            placements[control.id] ?: control.bounds,
            control.shape,
            control.stickDiameter
        )
    }

    internal fun lastGeometry(type: ControlType): LastGeometry? = lastGeometryByType[type]

    internal fun prepareGeometryAdjustment() {
        hideKeyboard()
        adjustSelected = true
        waitForFreshDown = true
    }

    private fun showTools() {
        tools.visibility = if ((isEditing || testing) && pendingControl == null) VISIBLE else GONE
        tools.refreshToolbar()
    }

    internal fun beginPlacement(control: TouchControl) {
        pendingControl = control
        selectedId = null
        waitForFreshDown = true
        tools.visibility = GONE
        placementCancel.visibility = VISIBLE
        message(context.getString(R.string.touch_workspace_place_prompt))
        canvas.invalidate()
    }

    /** Abort an in-progress placement and restore the configuration panel that started it. */
    internal fun cancelPlacement() {
        if (pendingControl == null) return
        pendingControl = null
        waitForFreshDown = true
        canvas.preview = null
        placementCancel.visibility = GONE
        if (isEditing || testing) tools.visibility = VISIBLE
        canvas.invalidate()
    }

    internal fun deleteSelected(controlId: String? = selectedId) {
        val id = controlId ?: return
        checkpoint()
        replaceTemplate(
            template.copy(
                controls = template.controls.filterNot { it.id == id },
                screens = template.screens.map { it.copy(placements = it.placements - id) }
            )
        )
        selectedId = null
        tools.refreshToolbar()
    }

    internal fun duplicateSelected(controlId: String? = selectedId) {
        val value = template.controls.firstOrNull { it.id == controlId } ?: return
        val copy = value.copy(
            id = newTouchId(),
            name = context.getString(R.string.touch_workspace_copy_name, value.name),
            groupKeys = value.groupKeys.map { it.copy(id = newTouchId()) }
        )
        beginPlacement(copy)
    }

    internal fun reorderSelected(front: Boolean, controlId: String? = selectedId) {
        val value = template.controls.firstOrNull { it.id == controlId } ?: return
        if (value.isArea) { message(context.getString(R.string.touch_workspace_area_layer)); return }
        checkpoint()
        val others = template.controls.filterNot { it.id == value.id }
        replaceTemplate(template.copy(controls = if (front) others + value else listOf(value) + others))
    }

    internal fun startTest(isolate: Boolean) {
        if (layoutRetired) return
        if (!isolate && gameAvailable) {
            message(context.getString(R.string.touch_workspace_test_in_game_disabled))
            return
        }
        val control = selected
        if (isolate && (!gameAvailable || control?.type != ControlType.SWIPE || !control.enabled)) {
            message(context.getString(R.string.touch_workspace_select_swipe))
            return
        }
        if (TouchValidator.validate(draft).isNotEmpty()) {
            message(context.getString(R.string.touch_workspace_repair_test))
            return
        }
        if (isolate && draft.presets.none { it.id == control?.swipePresetId }) {
            message(context.getString(R.string.touch_workspace_select_preset))
            return
        }
        hideKeyboard()
        releaseInputs()
        debugBefore = draft
        testing = true
        isEditing = false
        isolatedId = if (isolate) control?.id else null
        configureRuntime()
        runtime.setTouchscreenEnabled(touchscreenEnabled && !isolate)
        runtime.setIsolatedSwipe(isolatedId)
        onTestState?.invoke(gameAvailable)
        if (isolate) {
            tools.visibility = VISIBLE
            tools.showTuning(control!!.swipePresetId!!, true)
        } else {
            waitForFreshDown = true
        }
        postFrame()
    }

    internal fun finishTest(rollback: Boolean) {
        releaseInputs()
        if (rollback) {
            val presetId = template.controls.firstOrNull { it.id == isolatedId }?.swipePresetId
            debugBefore?.presets?.firstOrNull { it.id == presetId }?.let { old ->
                draft = draft.copy(presets = draft.presets.map { if (it.id == old.id) old else it })
            }
        } else if (debugBefore != draft) debugBefore?.let { checkpoint(it) }
        debugBefore = null
        testing = false
        isolatedId = null
        isEditing = true
        runtime.setIsolatedSwipe(null)
        runtime.setTouchscreenEnabled(touchscreenEnabled)
        onTestState?.invoke(false)
        updateGeometry()
        showTools()
    }

    internal fun save(exit: Boolean = false) {
        if (layoutRetired) return
        if (!tools.validateAndCompleteAll()) return
        val issues = TouchValidator.validate(draft)
        if (issues.isNotEmpty()) {
            MaterialAlertDialogBuilder(context).setTitle(context.getString(R.string.touch_workspace_invalid_title))
                .setMessage(context.getString(R.string.touch_workspace_invalid_detail, describeIssues(issues)))
                .setPositiveButton(context.getString(R.string.touch_workspace_keep_editing), null).show()
            return
        }
        val games = draft.gameTemplates.filterValues { it == templateId }.keys
        val changedPresets = draft.presets.filter { p -> saved.presets.firstOrNull { it.id == p.id } != p }
        val refs = draft.templates.flatMap { t ->
            t.controls.filter { c -> changedPresets.any { it.id == c.swipePresetId } }
                .map { "${t.name} / ${it.name}" }
        }
        fun commit(copyForGame: Boolean) {
            releaseInputs()
            val previousDraft = draft
            val previousTemplate = templateId
            val previousSaved = saved
            standalonePresetId?.let { id ->
                val preset = draft.presets.firstOrNull { it.id == id } ?: return
                draft = saved.copy(presets = saved.presets.map { if (it.id == id) preset else it })
            }
            if (copyForGame) {
                val session = TouchEditSession(saved)
                session.update { draft }
                val copiedId = session.copyTemplateForGame(templateId, gameId ?: return)
                draft = session.draft
                templateId = copiedId
            }
            try {
                onSave?.invoke(draft, templateId)
                if (saved != draft && copyForGame) {
                    draft = previousDraft
                    templateId = previousTemplate
                }
                if (exit && saved == draft) onExit?.invoke()
            } catch (error: Exception) {
                draft = previousDraft
                templateId = previousTemplate
                saved = previousSaved
                message(context.getString(R.string.touch_workspace_save_failed))
            }
            updateGeometry()
        }
        val dialog = MaterialAlertDialogBuilder(context).setTitle(context.getString(R.string.touch_workspace_sync))
            .setMessage(
                context.getString(
                    R.string.touch_workspace_sync_summary,
                    template.name,
                    games.joinToString().ifEmpty { context.getString(R.string.touch_workspace_no_games) },
                    context.getString(if (draft.defaultTemplateId == templateId) R.string.touch_workspace_yes else R.string.touch_workspace_no),
                    refs.joinToString("\n").ifEmpty { context.getString(R.string.touch_workspace_none) },
                    draft.gameTemplates.filter { (game, id) -> saved.gameTemplates[game] != id }.keys.joinToString()
                        .ifEmpty { context.getString(R.string.touch_workspace_none) }
                )
            )
            .setPositiveButton(context.getString(R.string.touch_workspace_sync)) { _, _ -> commit(false) }
            .setNegativeButton(context.getString(R.string.touch_workspace_cancel), null)
        if (gameId != null && standalonePresetId == null) dialog.setNeutralButton(context.getString(R.string.touch_workspace_copy_game)) { _, _ -> commit(true) }
        dialog.show()
    }

    internal fun setGeometry(id: String, rect: TouchRect): Boolean {
        val control = template.controls.firstOrNull { it.id == id } ?: return false
        val target = control.stickBounds(rect, density = resources.displayMetrics.density)
        if (!legal(control, target)) return false
        val screen = currentScreen()
        val updated = screen.copy(placements = placements + (id to target))
        replaceTemplate(template.copy(screens = template.screens.filterNot { it.id == screen.id } + updated))
        return true
    }

    private fun currentScreen(): ScreenLayout = template.screens.firstOrNull {
        abs(it.width - safe.width()) < 0.5f && abs(it.height - safe.height()) < 0.5f
    } ?: ScreenLayout(
        name = "${safe.width().toInt()} × ${safe.height().toInt()}",
        width = safe.width(),
        height = safe.height(),
        placements = placements
    )

    internal fun canSetGeometry(id: String, rect: TouchRect): Boolean =
        template.controls.firstOrNull { it.id == id }?.let { legal(it, it.stickBounds(rect, density = resources.displayMetrics.density)) } ?: false

    private fun legal(control: TouchControl, rect: TouchRect): Boolean {
        if (!TouchEditorGeometry.fits(rect, safe.width(), safe.height())) {
            canvas.feedback = context.getString(R.string.touch_workspace_out_of_bounds)
            return false
        }
        val current = template.screens.filter {
            abs(it.width - safe.width()) < 0.5f && abs(it.height - safe.height()) < 0.5f
        }
        for (screen in template.screens) {
            val onCurrentScreen = current.any { it.id == screen.id }
            val target = if (onCurrentScreen) {
                rect
            } else {
                TouchEditorGeometry.map(rect, safe.width(), safe.height(), screen.width, screen.height)
            }
            if (!TouchEditorGeometry.fits(target, screen.width, screen.height)) {
                canvas.feedback = context.getString(R.string.touch_workspace_screen_overflow, screen.name)
                return false
            }
        }
        canvas.feedback = null
        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        onTouchActivity?.invoke(event)
        if ((inputsSuspended || focusSuspended) && !isEditing) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                allPointers.clear()
                allPointers += event.getPointerId(0)
                waitForFreshDown = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> allPointers += event.getPointerId(event.actionIndex)
        }
        val handled = super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> allPointers -= event.getPointerId(event.actionIndex)
            MotionEvent.ACTION_CANCEL -> allPointers.clear()
        }
        return handled
    }

    internal fun onCanvasTouch(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val id = event.getPointerId(index)
        val x = event.getX(index) - safe.left
        val y = event.getY(index) - safe.top
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                canvasPointers.clear()
                canvasPointers += id
                if (waitForFreshDown && allPointers.size > 1) return true
                waitForFreshDown = false
                if (!isEditing && geometryAvailable) {
                    runtime.down(id, x, y, event.eventTime)
                    removeCallbacks(frame)
                    framePosted = false
                    postFrame()
                } else if (isEditing) beginGeometry(id, x, y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                canvasPointers += id
                if (!isEditing && !waitForFreshDown && geometryAvailable && !inputsSuspended) runtime.down(id, x, y, event.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                if (waitForFreshDown) return true
                if (!isEditing && geometryAvailable && !inputsSuspended) {
                    for (i in 0 until event.pointerCount) {
                        runtime.move(
                            event.getPointerId(i),
                            event.getX(i) - safe.left,
                            event.getY(i) - safe.top,
                            event.eventTime
                        )
                    }
                } else {
                    moveGeometry(event)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (!isEditing && !waitForFreshDown && geometryAvailable && !inputsSuspended) runtime.up(id, x, y, event.eventTime)
                if (isEditing && gesture?.pointer == id) endGeometry()
                canvasPointers -= id
            }
            MotionEvent.ACTION_CANCEL -> {
                runtime.cancelAll()
                cancelGeometry()
                canvasPointers.clear()
            }
        }
        canvas.invalidate()
        return true
    }

    private fun beginGeometry(pointer: Int, x: Float, y: Float) {
        pendingControl?.let { control ->
            val width = control.bounds.width.coerceAtLeast(1f)
            val height = control.bounds.height.coerceAtLeast(1f)
            val rect = if (control.isVisibleStick) {
                val diameter = control.stickDiameter * resources.displayMetrics.density
                TouchRect(
                    (x - diameter / 2).coerceIn(0f, max(0f, safe.width() - diameter)),
                    (y - diameter / 2).coerceIn(0f, max(0f, safe.height() - diameter)),
                    diameter,
                    diameter
                )
            } else {
                TouchRect(
                    (x - width / 2).coerceIn(0f, max(0f, safe.width() - width)),
                    (y - height / 2).coerceIn(0f, max(0f, safe.height() - height)),
                    width,
                    height
                )
            }
            gesture = GeometryGesture(pointer, x, y, rect, draft, 4, control.id, true, true)
            canvas.preview = control.copy(bounds = rect)
            return
        }
        val active = selected
        val activeRect = active?.let { placements[it.id] }
        val handle = if (active?.isVisibleStick == true) -1 else activeRect?.let { handleAt(it, x, y) } ?: -1
        val value = if (adjustSelected && active != null) {
            active
        } else if (handle >= 0) active else hitControl(x, y)
        if (value == null) { selectedId = null; tools.refreshToolbar(); return }
        select(value.id)
        if (value.locked) { message(context.getString(R.string.touch_workspace_locked)); return }
        val rect = placements[value.id] ?: value.bounds
        gesture = GeometryGesture(pointer, x, y, rect, draft, handle, value.id)
    }

    private fun moveGeometry(event: MotionEvent) {
        val state = gesture ?: return
        val index = event.findPointerIndex(state.pointer)
        if (index < 0) return
        val x = event.getX(index) - safe.left
        val y = event.getY(index) - safe.top
        val dx = x - state.startX
        val dy = y - state.startY
        if (!state.moving && max(abs(dx), abs(dy)) < ViewConfiguration.get(context).scaledTouchSlop) return
        if (!state.moving) {
            state.moving = true
        }
        val control = if (state.creating) pendingControl else template.controls.firstOrNull { it.id == state.controlId }
        if (control == null) return
        val original = state.original
        var rect = if (state.creating && control.isVisibleStick) {
            val diameter = control.stickDiameter * resources.displayMetrics.density
            TouchRect(
                (x - diameter / 2).coerceIn(0f, max(0f, safe.width() - diameter)),
                (y - diameter / 2).coerceIn(0f, max(0f, safe.height() - diameter)),
                diameter,
                diameter
            )
        } else if (state.creating && control.isArea) {
            TouchRect(
                min(state.startX, x).coerceAtLeast(0f),
                min(state.startY, y).coerceAtLeast(0f),
                abs(dx).coerceAtLeast(1f),
                abs(dy).coerceAtLeast(1f)
            )
        } else if (state.creating) {
            val left = min(state.startX, x).coerceAtLeast(0f)
            val top = min(state.startY, y).coerceAtLeast(0f)
            val width = abs(dx).coerceAtLeast(1f)
            val height = abs(dy).coerceAtLeast(1f)
            if (control.shape == ControlShape.CIRCLE) {
                val side = max(width, height)
                TouchRect(left, top, side, side)
            } else {
                TouchRect(left, top, width, height)
            }
        } else if (state.handle < 0) {
            original.copy(
                x = (original.x + dx).coerceIn(0f, max(0f, safe.width() - original.width)),
                y = (original.y + dy).coerceIn(0f, max(0f, safe.height() - original.height))
            )
        } else {
            val left = if (state.handle == 0 || state.handle == 3) min(x, original.right - 1) else original.x
            val top = if (state.handle == 0 || state.handle == 1) min(y, original.bottom - 1) else original.y
            val right = if (state.handle == 1 || state.handle == 2) max(x, original.x + 1) else original.right
            val bottom = if (state.handle == 2 || state.handle == 3) max(y, original.y + 1) else original.bottom
            TouchRect(left.coerceAtLeast(0f), top.coerceAtLeast(0f), right - left, bottom - top)
        }
        if ((control.type == ControlType.BUTTON || control.type == ControlType.VISIBILITY) &&
            control.shape == ControlShape.CIRCLE
        ) {
            val diameter = min(rect.width, rect.height)
            rect = rect.copy(width = diameter, height = diameter)
        }
        rect = control.stickBounds(rect, density = resources.displayMetrics.density)
        rect = gridRect(rect, resize = state.creating || state.handle >= 0)
        if (state.handle < 0 && !state.creating) {
            rect = rect.copy(
                x = rect.x.coerceIn(0f, max(0f, safe.width() - rect.width)),
                y = rect.y.coerceIn(0f, max(0f, safe.height() - rect.height))
            )
        }
        if (legal(control, rect)) {
            state.accepted = rect
            if (state.creating) {
                canvas.preview = control.copy(bounds = rect)
            } else {
                setGeometry(control.id, rect)
            }
        }
        canvas.invalidate()
    }

    private fun endGeometry() {
        val state = gesture ?: return
        gesture = null
        if (state.creating) {
            val control = pendingControl
            if (control != null && legal(control, state.accepted) &&
                (!control.isArea || min(state.accepted.width, state.accepted.height) >= dp(8))
            ) {
                checkpoint(state.before)
                val screen = currentScreen()
                val base = template.screens.firstOrNull { it.id == template.baseScreenId } ?: screen
                val rect = control.stickBounds(state.accepted, density = resources.displayMetrics.density)
                fun mapped(screen: ScreenLayout): TouchRect = control.stickBounds(
                    TouchEditorGeometry.map(
                        rect,
                        safe.width(),
                        safe.height(),
                        screen.width,
                        screen.height
                    ),
                    density = resources.displayMetrics.density
                )
                val baseRect = mapped(base)
                replaceTemplate(
                    template.copy(
                        controls = template.controls + control.copy(bounds = baseRect),
                        screens = template.screens.map { s ->
                            if (s.id == screen.id) {
                                s.copy(placements = s.placements + (control.id to rect))
                            } else {
                                s.copy(placements = s.placements + (control.id to mapped(s)))
                            }
                        }.let { screens ->
                            if (screens.any { it.id == screen.id }) {
                                screens
                            } else {
                                screens + screen.copy(placements = placements + (control.id to rect))
                            }
                        }
                    )
                )
                selectedId = control.id
                rememberGeometry(control)
                pendingControl = null
                placementCancel.visibility = GONE
                if (isEditing || testing) tools.visibility = VISIBLE
                tools.onControlPlaced(control.id)
            } else {
                message(context.getString(R.string.touch_workspace_no_position))
            }
            canvas.preview = null
        } else if (state.moving && draft != state.before) {
            val id = state.controlId
            if (id != null) {
                val before = geometrySnapshot(state.before, id)
                val after = geometrySnapshot(draft, id)
                checkpointWindow(before, after)
                tools.rebaseGeometry(id)
                tools.avoidSelectedControl(id)
            }
        }
        adjustSelected = false
        tools.refreshToolbar()
    }

    private fun geometrySnapshot(library: TouchLibrary, id: String): TouchWindowSnapshot {
        val template = library.templates.first { it.id == templateId }
        val control = template.controls.first { it.id == id }
        return TouchWindowSnapshot(
            key = "geometry:$id",
            controlId = id,
            beforeControl = control,
            beforePlacements = template.screens.associate { screen -> screen.id to screen.placements[id] },
            placementsOnly = true
        )
    }

    private fun rollbackGeometry(state: GeometryGesture): TouchLibrary {
        if (state.creating) return draft
        val id = state.controlId ?: return draft
        val oldTemplate = state.before.templates.firstOrNull { it.id == templateId } ?: return draft
        return draft.copy(
            templates = draft.templates.map { current ->
                if (current.id != templateId) {
                    current
                } else {
                    current.copy(
                        screens = current.screens.map { screen ->
                            val old = oldTemplate.screens.firstOrNull { it.id == screen.id }
                            val rect = old?.placements?.get(id)
                            screen.copy(placements = if (rect == null) screen.placements - id else screen.placements + (id to rect))
                        }
                    )
                }
            }
        )
    }

    private fun cancelGeometry() {
        gesture?.let { draft = rollbackGeometry(it) }
        gesture = null
        canvas.preview = null
        val wasPlacing = pendingControl != null
        pendingControl = null
        adjustSelected = false
        if (wasPlacing) {
            placementCancel.visibility = GONE
            if (isEditing || testing) tools.visibility = VISIBLE
        }
        updateGeometry(false)
    }

    private fun hitControl(x: Float, y: Float): TouchControl? {
        val tolerance = dp(10)
        return template.controls.sortedBy { if (it.isArea) 0 else 1 }.asReversed().firstOrNull {
            val r = placements[it.id] ?: it.bounds
            if (it.isVisibleStick) {
                TouchHitGeometry.contains(it, r, x, y)
            } else {
                x >= r.x - tolerance && x <= r.right + tolerance && y >= r.y - tolerance && y <= r.bottom + tolerance
            }
        }
    }

    private fun handleAt(r: TouchRect, x: Float, y: Float): Int =
        listOf(r.x to r.y, r.right to r.y, r.right to r.bottom, r.x to r.bottom)
            .indexOfFirst { abs(it.first - x) <= dp(18) && abs(it.second - y) <= dp(18) }

    private fun configureRuntime() {
        if (safe.width() <= 0 || safe.height() <= 0) return
        try {
            val runtimeTemplate = if (layoutRetired || (!controlsVisible && !isEditing && !testing)) template.copy(controls = emptyList()) else template
            runtime.configure(runtimeTemplate, placements, draft.presets, safe.width(), safe.height(), resources.displayMetrics.density)
            runtime.setIsolatedSwipe(isolatedId)
            runtime.setTouchscreenEnabled(touchscreenEnabled && isolatedId == null)
            geometryAvailable = true
        } catch (error: IllegalArgumentException) {
            runtime.cancelAll()
            geometryAvailable = false
            canvas.feedback = context.getString(R.string.touch_workspace_invalid_runtime)
        }
    }

    private fun updateGeometry(configure: Boolean = true) {
        safe.set(
            insetsLeft.toFloat(),
            insetsTop.toFloat(),
            max(insetsLeft + 1, width - insetsRight).toFloat(),
            max(insetsTop + 1, height - insetsBottom).toFloat()
        )
        placements = TouchGeometry.placements(template, safe.width(), safe.height())
        if (configure && width > 0 && height > 0) configureRuntime()
        tools.refreshToolbar()
        // The toolbar is rebuilt while the workspace is laying out; request one more pass so the
        // card is measured with the final width instead of staying at its pre-layout size.
        if (width > 0 && height > 0) tools.scheduleRelayout()
        canvas.invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        if (lastWidth != w.toFloat() || lastHeight != h.toFloat()) {
            if (testing) finishTest(false) else releaseInputs()
            lastWidth = w.toFloat()
            lastHeight = h.toFloat()
        }
        updateGeometry()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        focusSuspended = !hasWindowFocus
        if (!hasWindowFocus) {
            removeCallbacks(frame)
            framePosted = false
            if (testing && tools.hasModalDialog) {
                releaseInputs()
            } else if (testing) finishTest(false) else releaseInputs()
        } else {
            postFrame()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            removeCallbacks(frame)
            framePosted = false
            if (testing) finishTest(false) else releaseInputs()
        } else {
            postFrame()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); postFrame() }
    override fun onDetachedFromWindow() {
        releaseInputs()
        removeCallbacks(frame)
        framePosted = false
        if (testing) { isEditing = true; testing = false; isolatedId = null; onTestState?.invoke(false) }
        super.onDetachedFromWindow()
    }

    private fun postFrame() {
        if (!framePosted && isAttachedToWindow && !inputsSuspended && !focusSuspended && windowVisibility == VISIBLE && visibility == VISIBLE) {
            framePosted = true
            postDelayed(frame, if (runtime.hasActiveInputs()) 8L else 100L)
        }
    }

    internal fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(windowToken, 0)
        clearFocus()
    }

    internal fun message(text: String) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    internal fun dp(value: Int): Float = value * resources.displayMetrics.density

    /** Turn validator paths into readable "layout / control: reason" lines. */
    private fun describeIssues(issues: List<TouchValidationIssue>): String {
        val labels = mutableMapOf<String, String>()
        draft.templates.forEach { template ->
            labels[template.id] = template.name
            template.controls.forEach { control -> labels[control.id] = control.name.ifBlank { control.id } }
            template.screens.forEach { screen -> labels[screen.id] = screen.name }
        }
        draft.presets.forEach { preset -> labels[preset.id] = preset.name }
        val lines = issues.take(8).joinToString("\n") { issue ->
            val path = issue.path.split('.').joinToString(" / ") { labels[it] ?: it }
            "• $path: ${issue.message}"
        }
        return if (issues.size > 8) "$lines\n• …" else lines
    }

    /** Snap to the editing grid; moving keeps the size, resizing snaps every edge. */
    private fun gridRect(rect: TouchRect, resize: Boolean): TouchRect {
        if (!snapToGrid) return rect
        val step = gridStep()
        fun snap(value: Float): Float = (value / step).roundToInt() * step
        return if (!resize) {
            rect.copy(x = snap(rect.x), y = snap(rect.y))
        } else {
            val left = snap(rect.x)
            val top = snap(rect.y)
            val right = snap(rect.right).coerceAtLeast(left + step)
            val bottom = snap(rect.bottom).coerceAtLeast(top + step)
            TouchRect(left, top, right - left, bottom - top)
        }
    }

    companion object {
        private const val NORMAL_HAPTIC_MS = 20L
        private const val DOUBLE_TAP_HAPTIC_MS = 30L
    }
}
