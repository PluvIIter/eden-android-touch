// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.ui

import android.content.Context
import org.yuzu.yuzu_emu.R
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.yuzu.yuzu_emu.overlay.touch.model.ControlShape
import org.yuzu.yuzu_emu.overlay.touch.model.ControlType
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
import org.yuzu.yuzu_emu.overlay.touch.runtime.TouchRuntime
import org.yuzu.yuzu_emu.overlay.touch.store.TouchEditSession
import org.yuzu.yuzu_emu.overlay.touch.store.TouchValidator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TouchWorkspace(context: Context) : FrameLayout(context) {
    var onSave: ((TouchLibrary, String) -> Unit)? = null
    var onExit: (() -> Unit)? = null
    var onTestState: ((Boolean) -> Unit)? = null
    var onTouchActivity: ((MotionEvent) -> Unit)? = null
    var gameId: String? = null
    val draftBaselineRevision: Long get() = saved.revision
    val baselineRevision: Long get() = saved.revision
    private var inputsSuspended = false
    private var focusSuspended = false
    var isEditing: Boolean = true
        private set
    val isTesting: Boolean get() = testing
    var controlsVisible: Boolean = true
        set(value) {
            if (field == value) return
            releaseInputs()
            field = value
            configureRuntime()
            canvas.invalidate()
        }
    var touchscreenEnabled: Boolean = true
        set(value) {
            field = value
            runtime.setTouchscreenEnabled(value && isolatedId == null)
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
            gesture?.before ?: draft,
            saved,
            templateId,
            selectedId,
            isEditing || testing,
            tools.exportState()
        )
    )

    fun restoreDraft(serialized: String) {
        val state = Json.decodeFromString<TouchWorkspaceDraft>(serialized)
        state.requireRestorable(saved.revision)
        releaseInputs()
        tools.closePanel()
        draft = state.draft
        saved = state.saved
        templateId = state.templateId
        selectedId = state.selectedId?.takeIf { id -> template.controls.any { it.id == id } }
        testing = false
        isolatedId = null
        debugBefore = null
        isEditing = state.editing
        toolsHidden = false
        tools.visibility = if (isEditing) VISIBLE else GONE
        onTestState?.invoke(false)
        updateGeometry()
        tools.restoreState(state.tools)
        if (!isEditing) postFrame()
    }

    fun editPreset(presetId: String) {
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
    internal var toolsHidden = false
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
    })
    private var output: InputSink = object : InputSink {}
    private var saved = draft
    private val undo = ArrayDeque<TouchLibrary>()
    private val redo = ArrayDeque<TouchLibrary>()
    private val canvas = TouchCanvas(context, this)
    internal val tools = TouchTools(context, this)
    private val canvasPointers = mutableSetOf<Int>()
    private val allPointers = mutableSetOf<Int>()
    private var geometryAvailable = true
    private var waitForFreshDown = false
    private var restoreAfterPointers = false
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
            if (!isEditing) runtime.tick(SystemClock.uptimeMillis())
            canvas.invalidate()
            if (!isEditing) postFrame()
        }
    }
    private data class GeometryGesture(
        val pointer: Int,
        val startX: Float,
        val startY: Float,
        val original: TouchRect,
        val before: TouchLibrary,
        val handle: Int,
        val creating: Boolean = false,
        var moving: Boolean = false,
        var accepted: TouchRect = original
    )

    init {
        clipChildren = false
        isMotionEventSplittingEnabled = true
        addView(canvas, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(tools, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
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
        require(library.templates.any { it.id == templateId }) { context.getString(R.string.touch_workspace_missing_template) }
        this.draft = library
        this.saved = library
        this.templateId = templateId
        this.gameAvailable = gameAvailable
        output = sink
        undo.clear()
        redo.clear()
        selectedId = null
        testing = false
        isolatedId = null
        tools.closePanel()
        updateGeometry()
        tools.refreshToolbar()
    }

    fun setEditing(editing: Boolean) {
        releaseInputs()
        isEditing = editing
        testing = false
        isolatedId = null
        runtime.setIsolatedSwipe(null)
        toolsHidden = false
        tools.closePanel()
        tools.visibility = if (editing) VISIBLE else GONE
        if (editing) onTestState?.invoke(false)
        configureRuntime()
        canvas.invalidate()
        if (!editing) postFrame()
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
            draft = it.before
            placements = TouchGeometry.placements(template, safe.width().coerceAtLeast(1f), safe.height().coerceAtLeast(1f))
        }
        gesture = null
        canvas.preview = null
        waitForFreshDown = true
        restoreAfterPointers = false
        canvas.invalidate()
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
        if (toolsHidden) {
            releaseInputs()
            pendingControl = null
            adjustSelected = false
            showTools()
            return true
        }
        if (testing) {
            finishTest(false)
            return true
        }
        if (!isEditing) return false
        if (tools.hasPanel) {
            tools.cancelPanel()
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
        if (undo.lastOrNull() != before) undo.addLast(before)
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
        val rect = placements[value.id] ?: value.bounds
        if (value.isArea && template.controls.any {
            it.id != value.id && it.isArea && (placements[it.id] ?: it.bounds).intersects(rect)
        }
        ) {
            message(context.getString(R.string.touch_workspace_overlap))
            return false
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

    internal fun addPreset(value: SwipePreset) {
        draft = draft.copy(presets = draft.presets + value)
        runtime.updatePresets(draft.presets)
    }

    internal fun undo() {
        if (undo.isEmpty()) return
        redo.addLast(draft)
        replaceDraft(undo.removeLast())
        tools.closePanel()
    }

    internal fun redo() {
        if (redo.isEmpty()) return
        undo.addLast(draft)
        replaceDraft(redo.removeLast())
        tools.closePanel()
    }

    internal fun select(id: String) {
        if (selectedId != id) tools.finishSelectionPanel()
        selectedId = id
        tools.refreshToolbar()
        canvas.invalidate()
        tools.avoidSelection()
    }

    internal fun hideTools(adjust: Boolean = false) {
        hideKeyboard()
        toolsHidden = true
        tools.visibility = GONE
        adjustSelected = adjust
        waitForFreshDown = true
        message(context.getString(R.string.touch_workspace_back_restores))
    }

    private fun showTools() {
        toolsHidden = false
        tools.visibility = if (isEditing || testing) VISIBLE else GONE
        tools.refreshToolbar()
        tools.avoidSelection()
    }

    internal fun beginPlacement(control: TouchControl) {
        tools.closePanel()
        pendingControl = control
        selectedId = null
        hideTools()
        canvas.invalidate()
    }

    internal fun deleteSelected() {
        val id = selectedId ?: return
        checkpoint()
        replaceTemplate(
            template.copy(
                controls = template.controls.filterNot { it.id == id },
                screens = template.screens.map { it.copy(placements = it.placements - id) }
            )
        )
        selectedId = null
        tools.closePanel()
        tools.refreshToolbar()
    }

    internal fun duplicateSelected() {
        val value = selected ?: return
        val copy = value.copy(
            id = newTouchId(),
            name = context.getString(R.string.touch_workspace_copy_name, value.name),
            groupKeys = value.groupKeys.map { it.copy(id = newTouchId()) }
        )
        beginPlacement(copy)
    }

    internal fun reorderSelected(front: Boolean) {
        val value = selected ?: return
        if (value.isArea) { message(context.getString(R.string.touch_workspace_area_layer)); return }
        checkpoint()
        val others = template.controls.filterNot { it.id == value.id }
        replaceTemplate(template.copy(controls = if (front) others + value else listOf(value) + others))
    }

    internal fun startTest(isolate: Boolean) {
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
        tools.closePanel()
        testing = true
        isEditing = false
        isolatedId = if (isolate) control?.id else null
        configureRuntime()
        runtime.setTouchscreenEnabled(touchscreenEnabled && !isolate)
        runtime.setIsolatedSwipe(isolatedId)
        onTestState?.invoke(gameAvailable)
        if (isolate) {
            toolsHidden = false
            tools.visibility = VISIBLE
            tools.showTuning(control!!.swipePresetId!!, true)
        } else {
            hideTools()
        }
        postFrame()
    }

    internal fun finishTest(rollback: Boolean) {
        releaseInputs()
        if (rollback) {
            debugBefore?.let { draft = it }
        } else if (debugBefore != draft) debugBefore?.let { checkpoint(it) }
        debugBefore = null
        testing = false
        isolatedId = null
        isEditing = true
        runtime.setIsolatedSwipe(null)
        runtime.setTouchscreenEnabled(touchscreenEnabled)
        onTestState?.invoke(false)
        tools.closePanel()
        updateGeometry()
        showTools()
    }

    internal fun save(exit: Boolean = false) {
        val issues = TouchValidator.validate(draft)
        if (issues.isNotEmpty()) {
            MaterialAlertDialogBuilder(context).setTitle(context.getString(R.string.touch_workspace_invalid_title))
                .setMessage(R.string.touch_workspace_invalid_message)
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
        if (gameId != null) dialog.setNeutralButton(context.getString(R.string.touch_workspace_copy_game)) { _, _ -> commit(true) }
        dialog.show()
    }

    internal fun setGeometry(id: String, rect: TouchRect): Boolean {
        val control = template.controls.firstOrNull { it.id == id } ?: return false
        if (!legal(control, rect)) return false
        val screen = currentScreen()
        val updated = screen.copy(placements = placements + (id to rect))
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
        template.controls.firstOrNull { it.id == id }?.let { legal(it, rect) } ?: false

    private fun legal(control: TouchControl, rect: TouchRect): Boolean {
        if (!TouchEditorGeometry.fits(rect, safe.width(), safe.height())) return false
        val conflict = TouchEditorGeometry.conflict(control, rect, template.controls, placements)
        if (conflict != null) {
            canvas.feedback = context.getString(R.string.touch_workspace_overlap_named, conflict.name)
            return false
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
        if (allPointers.isEmpty() && restoreAfterPointers) {
            restoreAfterPointers = false
            post { if (allPointers.isEmpty()) showTools() else restoreAfterPointers = true }
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
                if (restoreAfterPointers) { restoreAfterPointers = false; showTools() }
            }
        }
        canvas.invalidate()
        return true
    }

    private fun beginGeometry(pointer: Int, x: Float, y: Float) {
        pendingControl?.let { control ->
            val size = min(dp(72), min(safe.width(), safe.height()))
            val rect = if (control.isArea) {
                TouchRect(x, y, 1f, 1f)
            } else {
                TouchRect(
                    (x - size / 2).coerceIn(0f, safe.width() - size),
                    (y - size / 2).coerceIn(0f, safe.height() - size),
                    size,
                    size
                )
            }
            gesture = GeometryGesture(pointer, x, y, rect, draft, 4, true, true)
            canvas.preview = control.copy(bounds = rect)
            return
        }
        val active = selected
        val activeRect = active?.let { placements[it.id] }
        val handle = activeRect?.let { handleAt(it, x, y) } ?: -1
        val value = if (adjustSelected && active != null) {
            active
        } else if (handle >= 0) active else hitControl(x, y)
        if (value == null) { selectedId = null; tools.refreshToolbar(); return }
        select(value.id)
        if (value.locked) { message(context.getString(R.string.touch_workspace_locked)); return }
        val rect = placements[value.id] ?: value.bounds
        gesture = GeometryGesture(pointer, x, y, rect, draft, handle)
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
            tools.finishSelectionPanel()
            state.moving = true
            toolsHidden = true
            tools.visibility = GONE
        }
        val control = if (state.creating) pendingControl else selected
        if (control == null) return
        val original = state.original
        var rect = if (state.creating && control.isArea) {
            TouchRect(
                min(state.startX, x).coerceAtLeast(0f),
                min(state.startY, y).coerceAtLeast(0f),
                abs(dx).coerceAtLeast(1f),
                abs(dy).coerceAtLeast(1f)
            )
        } else if (state.handle < 0 || state.creating) {
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
        if (control.type == ControlType.BUTTON && control.shape == ControlShape.CIRCLE) {
            val diameter = min(rect.width, rect.height)
            rect = rect.copy(width = diameter, height = diameter)
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
                val rect = state.accepted
                fun mapped(screen: ScreenLayout): TouchRect = TouchEditorGeometry.map(
                    rect,
                    safe.width(),
                    safe.height(),
                    screen.width,
                    screen.height
                )
                val baseRect = mapped(base)
                val otherConflict = template.screens.any { s ->
                    if (s.id == screen.id || !control.isArea) {
                        false
                    } else {
                        template.controls.any { c ->
                            c.isArea && TouchGeometry.savedBounds(template, s, c).intersects(mapped(s))
                        }
                    }
                }
                if (otherConflict) {
                    message(context.getString(R.string.touch_workspace_other_screen_conflict))
                    canvas.preview = null
                    return
                }
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
                pendingControl = null
            } else {
                message(context.getString(R.string.touch_workspace_no_position))
            }
            canvas.preview = null
        } else if (state.moving && draft != state.before) checkpoint(state.before)
        if (state.moving) restoreAfterPointers = pendingControl == null
        adjustSelected = false
        tools.refreshToolbar()
    }

    private fun cancelGeometry() {
        gesture?.let { draft = it.before }
        gesture = null
        canvas.preview = null
        pendingControl = null
        adjustSelected = false
        updateGeometry(false)
    }

    private fun hitControl(x: Float, y: Float): TouchControl? {
        val tolerance = dp(10)
        return template.controls.sortedBy { if (it.isArea) 0 else 1 }.asReversed().firstOrNull {
            val r = placements[it.id] ?: it.bounds
            x >= r.x - tolerance && x <= r.right + tolerance && y >= r.y - tolerance && y <= r.bottom + tolerance
        }
    }

    private fun handleAt(r: TouchRect, x: Float, y: Float): Int =
        listOf(r.x to r.y, r.right to r.y, r.right to r.bottom, r.x to r.bottom)
            .indexOfFirst { abs(it.first - x) <= dp(18) && abs(it.second - y) <= dp(18) }

    private fun configureRuntime() {
        if (safe.width() <= 0 || safe.height() <= 0) return
        try {
            val runtimeTemplate = if (!controlsVisible && !isEditing && !testing) template.copy(controls = emptyList()) else template
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
        tools.post { tools.avoidSelection() }
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
            postDelayed(frame, 8L)
        }
    }

    internal fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(windowToken, 0)
        clearFocus()
    }

    internal fun message(text: String) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    internal fun dp(value: Int): Float = value * resources.displayMetrics.density
}
