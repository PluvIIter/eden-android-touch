// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.ui

import android.content.Context
import android.graphics.RectF
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.yuzu.yuzu_emu.overlay.touch.model.AuxiliaryMode
import org.yuzu.yuzu_emu.overlay.touch.model.ButtonBinding
import org.yuzu.yuzu_emu.overlay.touch.model.ControlShape
import org.yuzu.yuzu_emu.overlay.touch.model.ControlType
import org.yuzu.yuzu_emu.overlay.touch.model.CurvePoint
import org.yuzu.yuzu_emu.overlay.touch.model.GestureBinding
import org.yuzu.yuzu_emu.overlay.touch.model.GestureDirection
import org.yuzu.yuzu_emu.overlay.touch.model.GestureTiming
import org.yuzu.yuzu_emu.overlay.touch.model.GroupKey
import org.yuzu.yuzu_emu.overlay.touch.model.GroupStyle
import org.yuzu.yuzu_emu.overlay.touch.model.MotionScale
import org.yuzu.yuzu_emu.overlay.touch.model.StickTarget
import org.yuzu.yuzu_emu.overlay.touch.model.SwipePreset
import org.yuzu.yuzu_emu.overlay.touch.model.TouchControl
import org.yuzu.yuzu_emu.overlay.touch.model.TouchLibrary
import org.yuzu.yuzu_emu.overlay.touch.model.TouchRect
import org.yuzu.yuzu_emu.overlay.touch.model.newTouchId
import org.yuzu.yuzu_emu.overlay.touch.store.TouchValidator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class TouchTools(context: Context, private val workspace: TouchWorkspace) : FrameLayout(context) {
    private var panelAlpha = 0.94f
    private val toolbar = card()
    private val toolbarContent = column()
    private val title = label("")
    private val actions = row()
    private val selectionActions = row()
    private var panel: MaterialCardView? = null
    private var panelBody: LinearLayout? = null
    private var panelBefore: TouchLibrary? = null
    private var panelCancel: (() -> Unit)? = null
    private var collapsed = false
    private var panelCollapsed = false
    private var panelScroll: ScrollView? = null
    private var propertyTab = 0
    private var childIndex: Int? = null
    private var propertyId: String? = null
    private var draggingTools = false
    private var panelKey: String? = null
    private val scrollPositions = mutableMapOf<String, Int>()
    private var toolbarPosition: TouchToolPosition? = null
    private var panelPosition: TouchToolPosition? = null
    private val invalidFields = mutableSetOf<TextInputLayout>()
    val hasPanel: Boolean get() = panel != null
    var hasModalDialog: Boolean = false
        private set

    fun exportState(): TouchToolState {
        panelKey?.let { key -> panelScroll?.let { scrollPositions[key] = it.scrollY } }
        return TouchToolState(
            toolbarPosition ?: positionOf(toolbar), panelPosition ?: panel?.let(::positionOf),
            panelAlpha, collapsed, panelCollapsed, propertyTab, childIndex, propertyId, scrollPositions.toMap(), panelKey
        ).normalized()
    }

    fun restoreState(value: TouchToolState) {
        val state = value.normalized()
        toolbarPosition = state.toolbar
        panelPosition = state.panel
        panelAlpha = state.opacity
        collapsed = state.toolbarCollapsed
        panelCollapsed = state.panelCollapsed
        propertyTab = state.propertyTab
        childIndex = state.childIndex
        propertyId = state.propertyId
        scrollPositions.clear()
        scrollPositions.putAll(state.scrollPositions)
        toolbar.setCardBackgroundColor(surfaceColor())
        panel?.setCardBackgroundColor(surfaceColor())
        refreshToolbar()
        post {
            val active = state.activePanel
            when {
                active?.startsWith("control:") == true && workspace.selected != null -> showProperties()
                active?.startsWith("preset:") == true -> {
                    val parts = active.split(':')
                    if (parts.size == 3 && workspace.draft.presets.any { it.id == parts[1] }) {
                        showTuning(parts[1], false, parts[2].toIntOrNull()?.coerceIn(0, 2) ?: 0)
                    }
                }
            }
            toolbarPosition?.let { applyPosition(toolbar, it) }
        }
    }

    private fun positionOf(view: View): TouchToolPosition = TouchToolPosition(
        view.x / max(1f, width - view.width.toFloat()),
        view.y / max(1f, height - view.height.toFloat())
    ).normalized()

    private fun applyPosition(view: View, position: TouchToolPosition) {
        val normalized = position.normalized()
        view.x = normalized.x * max(0f, width - view.width.toFloat())
        view.y = normalized.y * max(0f, height - view.height.toFloat())
    }

    fun finishSelectionPanel() {
        if (panelBefore != null) completePanel() else closePanel()
    }

    init {
        clipChildren = false
        isMotionEventSplittingEnabled = true
        val head = row()
        head.addView(title, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        head.addView(button("Fold") { collapsed = !collapsed; refreshToolbar() })
        draggable(head, toolbar)
        toolbarContent.addView(head)
        toolbarContent.addView(horizontal(actions))
        toolbarContent.addView(horizontal(selectionActions))
        toolbar.addView(toolbarContent)
        addView(
            toolbar,
            LayoutParams(panelWidth(), LayoutParams.WRAP_CONTENT).apply {
                leftMargin = px(12); topMargin = px(12)
            }
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post {
            toolbarPosition?.let { applyPosition(toolbar, it) }
            panel?.let { view -> panelPosition?.let { applyPosition(view, it) } }
        }
    }

    fun refreshToolbar() {
        toolbar.layoutParams?.let { params ->
            val target = if (collapsed) px(180) else panelWidth()
            if (params.width != target) { params.width = target; toolbar.layoutParams = params }
        }
        val runtime = workspace.testing
        title.text = if (runtime) "Test · unsaved draft" else "${workspace.template.name} · draft"
        title.maxLines = 2
        actions.removeAllViews()
        selectionActions.removeAllViews()
        actions.visibility = if (collapsed) GONE else VISIBLE
        selectionActions.visibility = if (collapsed) GONE else VISIBLE
        if (collapsed) return
        if (runtime) {
            actions.addView(button("Return to edit") { workspace.finishTest(false) })
            if (workspace.isolatedId != null) {
                actions.addView(
                    button("Parameters") {
                        workspace.selected?.swipePresetId?.let { showTuning(it, true) }
                    }
                )
                actions.addView(button("Undo this debug") { workspace.finishTest(true) })
            }
            actions.addView(button("Hide tools") { workspace.hideTools() })
            actions.addView(button("Tool opacity") { showToolOpacity() })
            return
        }
        actions.addView(button("Add") { showAdd() })
        actions.addView(button("Undo") { workspace.undo() })
        actions.addView(button("Redo") { workspace.redo() })
        actions.addView(button("Controls") { showControls() })
        actions.addView(
            button(if (workspace.gameAvailable) "Game test" else "Local test") {
                if (validFields()) { completePanel(); workspace.startTest(false) }
            }
        )
        actions.addView(button("Save") { if (validFields()) { completePanel(); workspace.save() } })
        actions.addView(button("Hide tools") { workspace.hideTools() })
        actions.addView(button("Tool opacity") { showToolOpacity() })
        actions.addView(
            button("Set screen as base") {
                if (validFields()) { completePanel(); workspace.setCurrentScreenAsBase() }
            }
        )
        val selected = workspace.selected ?: return
        selectionActions.addView(label("Selected: ${selected.name}"))
        selectionActions.addView(button("Properties") { showProperties() })
        selectionActions.addView(
            button("Adjust in place") {
                if (validFields()) { completePanel(); workspace.hideTools(true) }
            }
        )
        if (selected.type == ControlType.SWIPE && workspace.gameAvailable) {
            selectionActions.addView(
                button("Single-control debug") {
                    if (validFields()) { completePanel(); workspace.startTest(true) }
                }
            )
        }
    }

    fun closePanel() {
        panelKey?.let { key -> panelScroll?.let { scrollPositions[key] = it.scrollY } }
        panelKey = null
        panel?.let { panelPosition = positionOf(it); removeView(it) }
        panel = null
        panelBody = null
        panelScroll = null
        panelCancel = null
        panelBefore = null
        invalidFields.clear()
    }

    fun cancelPanel() {
        val cancel = panelCancel
        val before = panelBefore
        closePanel()
        if (cancel != null) cancel() else if (before != null) workspace.replaceDraft(before)
    }

    private fun completePanel() {
        val before = panelBefore
        closePanel()
        if (before != null && before != workspace.draft) workspace.checkpoint(before)
    }

    private fun openPanel(
        name: String,
        transactional: Boolean = false,
        onClose: (() -> Unit)? = null,
        key: String? = null
    ): LinearLayout {
        closePanel()
        panelKey = key
        panelBefore = if (transactional) workspace.draft else null
        panelCancel = onClose
        val surface = card()
        panel = surface
        val content = column()
        val header = row()
        val heading = label(name, 16f)
        header.addView(heading, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            button("Fold") {
                panelCollapsed = !panelCollapsed
                panelScroll?.visibility = if (panelCollapsed) GONE else VISIBLE
            }
        )
        draggable(header, surface)
        content.addView(header)
        val body = column()
        panelBody = body
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(body)
            visibility = if (panelCollapsed) GONE else VISIBLE
        }
        panelScroll = scroll
        content.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, panelHeight()))
        surface.addView(content)
        addView(
            surface,
            LayoutParams(panelWidth(), LayoutParams.WRAP_CONTENT).apply {
                leftMargin = px(16)
                topMargin = px(140)
            }
        )
        post {
            if (panel == surface) {
                key?.let { scroll.scrollTo(0, scrollPositions[it] ?: 0) }
                panelPosition?.let { applyPosition(surface, it) }
                avoidSelection()
            }
        }
        return body
    }

    fun avoidSelection() {
        if (draggingTools || workspace.runtime.snapshot().pointers.isNotEmpty()) return
        val selected = workspace.selectedId?.let { workspace.placements[it] }
        val avoid = selected?.let {
            RectF(
                it.x + workspace.safe.left - px(22),
                it.y + workspace.safe.top - px(22),
                it.right + workspace.safe.left + px(22),
                it.bottom + workspace.safe.top + px(22)
            )
        }
        val occupied = mutableListOf<RectF>()
        for (view in listOfNotNull(toolbar, panel)) {
            if (view.visibility != VISIBLE || view.width <= 0 || width <= 0) continue
            val maxX = max(0f, width - view.width - px(8f))
            val maxY = max(0f, height - view.height - px(8f))
            val candidates = listOf(
                view.x to view.y,
                px(8f) to px(8f),
                maxX to px(8f),
                px(8f) to maxY,
                maxX to maxY
            )
            val position = candidates.firstOrNull { (x, y) ->
                val r = RectF(x, y, x + view.width, y + view.height)
                (avoid == null || !RectF.intersects(r, avoid)) && occupied.none { RectF.intersects(r, it) }
            }
            if (position != null) { view.x = position.first.coerceIn(0f, maxX); view.y = position.second.coerceIn(0f, maxY) } else if (view == toolbar && !collapsed) { collapsed = true; refreshToolbar() } else if (view == panel && !panelCollapsed && !workspace.testing) {
                panelCollapsed = true
                panelScroll?.visibility = GONE
            }
            occupied += RectF(view.x, view.y, view.x + view.width, view.y + view.height)
        }
    }

    private fun showToolOpacity() {
        val body = openPanel("Tool background")
        number(body, "Background opacity", panelAlpha, 0.4f, 1f, true) {
            panelAlpha = it
            toolbar.setCardBackgroundColor(surfaceColor())
            panel?.setCardBackgroundColor(surfaceColor())
        }
        body.addView(button("Done") { closePanel() })
    }

    private fun showControls() {
        val body = openPanel("Controls · top layer, then areas")
        val ordered = workspace.template.controls.sortedBy { if (it.isArea) 0 else 1 }.asReversed()
        for (control in ordered) {
            body.addView(
                button(
                    "${control.name} · ${control.type.name}" +
                        if (!control.enabled) " · disabled" else if (control.locked) " · locked" else ""
                ) {
                    workspace.select(control.id)
                    closePanel()
                }
            )
            val switches = row()
            toggle(switches, "Enabled", control.enabled) {
                workspace.checkpoint()
                workspace.template.controls.firstOrNull { c -> c.id == control.id }?.let { c -> workspace.updateControl(c.copy(enabled = it)) }
            }
            toggle(switches, "Lock geometry", control.locked) {
                workspace.checkpoint()
                workspace.template.controls.firstOrNull { c -> c.id == control.id }?.let { c -> workspace.updateControl(c.copy(locked = it)) }
            }
            body.addView(switches)
        }
        body.addView(button("Adjust selected in place") { closePanel(); workspace.hideTools(true) })
    }

    private fun showAdd() {
        val body = openPanel("Add control · choose a type")
        val descriptions = mapOf(
            ControlType.BUTTON to "Button · one or simultaneous buttons",
            ControlType.BUTTON_GROUP to "Button group · face keys or D-pad",
            ControlType.STICK to "Stick · fixed or free movement",
            ControlType.SWIPE to "Swipe area · relative camera motion",
            ControlType.GESTURE to "Gesture area · tap or directional actions"
        )
        for ((type, name) in descriptions) body.addView(button(name) { configureNew(type) })
    }

    private fun configureNew(type: ControlType) {
        var control = TouchControl(
            name = type.name.lowercase().replace('_', ' '),
            type = type,
            shape = if (type == ControlType.SWIPE || type == ControlType.GESTURE) ControlShape.RECTANGLE else ControlShape.CIRCLE,
            swipePresetId = workspace.draft.presets.firstOrNull()?.id,
            gestures = if (type == ControlType.GESTURE) mapOf(GestureDirection.TAP to GestureBinding(button = 0)) else emptyMap()
        )
        val body = openPanel("New ${control.name} · configure input")
        textField(body, "Name", control.name, false) { control = control.copy(name = it) }
        when (type) {
            ControlType.BUTTON -> body.addView(
                button("Select button mapping") {
                    mapping(control.binding.buttons, false) { control = control.copy(binding = control.binding.copy(buttons = it)) }
                }
            )
            ControlType.BUTTON_GROUP -> choice(body, "Group style", GroupStyle.values().toList(), control.groupStyle) {
                control = control.copy(
                    groupStyle = it,
                    groupKeys = if (it == GroupStyle.DPAD) {
                        listOf(
                            GroupKey(text = "↑", binding = ButtonBinding(setOf(13))),
                            GroupKey(text = "→", binding = ButtonBinding(setOf(14))),
                            GroupKey(text = "↓", binding = ButtonBinding(setOf(15))),
                            GroupKey(text = "←", binding = ButtonBinding(setOf(12)))
                        )
                    } else {
                        TouchControl().groupKeys
                    }
                )
            }
            ControlType.STICK -> {
                choice(body, "Target stick", StickTarget.values().toList(), control.stickTarget) { control = control.copy(stickTarget = it) }
                toggle(body, "Free stick", false) { control = control.copy(freeStick = it, triggerOnly = false) }
            }
            ControlType.SWIPE -> {
                choice(body, "Target stick", StickTarget.values().toList(), control.stickTarget) { control = control.copy(stickTarget = it) }
                if (workspace.draft.presets.isEmpty()) {
                    body.addView(label("A default camera preset will be created when placing."))
                } else {
                    presetChoice(body, control.swipePresetId) { control = control.copy(swipePresetId = it) }
                }
            }
            ControlType.GESTURE -> body.addView(
                button("Tap output (default A)") {
                    gestureTarget(control.gestures[GestureDirection.TAP]) {
                        control = control.copy(gestures = if (it == null) emptyMap() else mapOf(GestureDirection.TAP to it))
                    }
                }
            )
        }
        body.addView(label("Next: tools disappear. Lift all fingers, then tap to place or draw an area. Back restores tools."))
        body.addView(
            button("Place control") {
                if (!validFields()) return@button
                if (control.name.isBlank()) { workspace.message("Enter a name"); return@button }
                if (control.type == ControlType.GESTURE && control.gestures.isEmpty()) {
                    workspace.message("At least one gesture output is required"); return@button
                }
                if (control.type == ControlType.SWIPE && control.swipePresetId == null) {
                    val preset = SwipePreset()
                    workspace.checkpoint()
                    workspace.addPreset(preset)
                    control = control.copy(swipePresetId = preset.id)
                }
                workspace.beginPlacement(control)
            }
        )
        body.addView(button("Cancel") { showAdd() })
    }

    private fun showProperties(keepTransaction: TouchLibrary? = null) {
        val control = workspace.selected ?: return
        if (propertyId != control.id) { propertyTab = 0; childIndex = null; propertyId = control.id }
        val before = keepTransaction ?: workspace.draft
        val body = openPanel(
            "${control.name} · properties",
            true,
            key = "control:${control.id}:$propertyTab:$childIndex"
        )
        panelBefore = before
        val tabs = row()
        listOf("Input", "Appearance", "Geometry").forEachIndexed { i, text ->
            tabs.addView(
                button(if (propertyTab == i) "• $text" else text) {
                    if (validFields()) { propertyTab = i; showProperties(before) }
                }
            )
        }
        body.addView(horizontal(tabs))
        if (control.type == ControlType.BUTTON_GROUP) {
            val children = row()
            children.addView(
                button(if (childIndex == null) "• Whole group" else "Whole group") {
                    childIndex = null; showProperties(before)
                }
            )
            control.groupKeys.forEachIndexed { index, key ->
                children.addView(
                    button(if (childIndex == index) "• ${key.text}" else key.text) {
                        childIndex = index; showProperties(before)
                    }
                )
            }
            body.addView(horizontal(children))
        }
        when (propertyTab) {
            0 -> inputProperties(body, control, before)
            1 -> appearanceProperties(body, control)
            2 -> geometryProperties(body, control)
        }
        body.addView(button("Done") { if (validFields()) { workspace.hideKeyboard(); completePanel() } })
        body.addView(button("Cancel changes") { cancelPanel() })
        body.addView(
            button("Adjust in place") {
                if (validFields()) { completePanel(); workspace.hideTools(true) }
            }
        )
        body.addView(button("Duplicate") { if (validFields()) { completePanel(); workspace.duplicateSelected() } })
        val order = row()
        order.addView(button("Bring forward") { workspace.reorderSelected(true) })
        order.addView(button("Send backward") { workspace.reorderSelected(false) })
        body.addView(order)
        body.addView(
            button("Delete control") {
                MaterialAlertDialogBuilder(context).setTitle("Delete ${control.name}?")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                        completePanel(); workspace.deleteSelected()
                    }.show()
            }
        )
    }

    private fun inputProperties(body: LinearLayout, control: TouchControl, before: TouchLibrary) {
        when (control.type) {
            ControlType.BUTTON -> bindingFields(body, control.binding) { value -> change { it.copy(binding = value) } }
            ControlType.BUTTON_GROUP -> {
                val index = childIndex
                if (index == null) {
                    body.addView(label("${control.groupStyle} · select a child to edit its mapping and turbo."))
                } else {
                    val key = control.groupKeys[index]
                    body.addView(label("Child: ${key.text} · ${listOf("Top", "Right", "Bottom", "Left")[index]}"))
                    bindingFields(body, key.binding) { binding ->
                        change { c ->
                            c.copy(groupKeys = c.groupKeys.mapIndexed { i, k -> if (i == index) k.copy(binding = binding) else k })
                        }
                    }
                }
            }
            ControlType.STICK -> {
                choice(body, "Target stick", StickTarget.values().toList(), control.stickTarget) { change { c -> c.copy(stickTarget = it) } }
                toggle(body, "Free stick", control.freeStick) {
                    if (change { c -> c.copy(freeStick = it, triggerOnly = if (it) c.triggerOnly else false) }) showProperties(before)
                }
                if (control.freeStick) {
                    toggle(body, "Show only when touched (area)", control.triggerOnly) {
                        if (change { c -> c.copy(triggerOnly = it) }) showProperties(before)
                    }
                }
                toggle(body, "Maximum deflection outside deadzone", control.maximumDeflection) { change { c -> c.copy(maximumDeflection = it) } }
                number(body, "Stick deadzone [0–0.95]", control.stickDeadzone, 0f, 0.95f, true) { change { c -> c.copy(stickDeadzone = it) } }
                body.addView(
                    button("Auxiliary: ${control.auxiliaryButton?.let { buttonName(it) } ?: "None"}") {
                        mapping(control.auxiliaryButton?.let { setOf(it) } ?: emptySet(), true, true) { values ->
                            change { it.copy(auxiliaryButton = values.firstOrNull()) }; showProperties(before)
                        }
                    }
                )
                if (control.auxiliaryButton != null) {
                    choice(body, "Auxiliary mode", AuxiliaryMode.values().toList(), control.auxiliaryMode) { change { c -> c.copy(auxiliaryMode = it) } }
                    body.addView(label("Push: held outside center. Double tap: held from second DOWN until its UP."))
                    number(body, "Double-tap timeout (ms)", control.tapTimeoutMs.toFloat(), 1f, 10000f) { change { c -> c.copy(tapTimeoutMs = it.toLong()) } }
                    number(body, "Double-tap tolerance (dp)", control.tapSlop, 0f, 500f) { change { c -> c.copy(tapSlop = it) } }
                }
            }
            ControlType.SWIPE -> {
                choice(body, "Target stick", StickTarget.values().toList(), control.stickTarget) { change { c -> c.copy(stickTarget = it) } }
                presetChoice(body, control.swipePresetId) { change { c -> c.copy(swipePresetId = it) }; showProperties(before) }
                body.addView(
                    button("Tune shared preset") {
                        if (validFields()) { completePanel(); workspace.selected?.swipePresetId?.let { showTuning(it, false) } }
                    }
                )
                body.addView(
                    button("Copy preset and tune") {
                        if (!validFields()) return@button
                        completePanel()
                        val p = workspace.draft.presets.firstOrNull { it.id == workspace.selected?.swipePresetId } ?: return@button
                        workspace.checkpoint()
                        val copy = p.copy(id = newTouchId(), name = "${p.name} copy")
                        workspace.addPreset(copy)
                        change { it.copy(swipePresetId = copy.id) }
                        showTuning(copy.id, false)
                    }
                )
                if (workspace.gameAvailable) {
                    body.addView(
                        button("Single-control live debug") {
                            if (validFields()) { completePanel(); workspace.startTest(true) }
                        }
                    )
                }
            }
            ControlType.GESTURE -> {
                toggle(body, "Eight recognition directions", control.diagonalGestures) { change { c -> c.copy(diagonalGestures = it) }; showProperties(before) }
                body.addView(label("Recognition direction · select a compass cell to set its output"))
                compass(
                    body,
                    control.diagonalGestures,
                    true,
                    null,
                    { direction -> gestureSummary(control.gestures[direction]) }
                ) { direction ->
                    gestureTarget(control.gestures[direction]) { value ->
                        change { c -> c.copy(gestures = if (value == null) c.gestures - direction else c.gestures + (direction to value)) }
                        showProperties(before)
                    }
                }
                number(body, "Recognition distance (dp)", control.gestureDistance, 1f, 1000f) { change { c -> c.copy(gestureDistance = it) } }
                choice(body, "Swipe timing", GestureTiming.values().toList(), control.gestureTiming) { change { c -> c.copy(gestureTiming = it) } }
                body.addView(label("Immediate: hold until UP. Release: finite pulse. Tap always produces a pulse."))
                number(body, "Tap / release pulse (ms)", control.gesturePulseMs.toFloat(), 1f, 10000f) { change { c -> c.copy(gesturePulseMs = it.toLong()) } }
                number(body, "Tap timeout (ms)", control.tapTimeoutMs.toFloat(), 1f, 10000f) { change { c -> c.copy(tapTimeoutMs = it.toLong()) } }
                number(body, "Tap movement tolerance (dp)", control.tapSlop, 0f, 500f) { change { c -> c.copy(tapSlop = it) } }
            }
        }
    }

    private fun appearanceProperties(body: LinearLayout, control: TouchControl) {
        if (control.type != ControlType.BUTTON_GROUP || childIndex == null) {
            textField(body, "Control name", control.name, false) { value -> change { it.copy(name = value) } }
        }
        if (control.type == ControlType.BUTTON || (control.type == ControlType.BUTTON_GROUP && childIndex != null)) {
            val index = childIndex
            textField(body, "Display text", if (index != null) control.groupKeys[index].text else control.text, true) { value ->
                change { c ->
                    if (index == null) {
                        c.copy(text = value)
                    } else {
                        c.copy(
                            groupKeys = c.groupKeys.mapIndexed { i, k ->
                                if (i == index) k.copy(text = value) else k
                            }
                        )
                    }
                }
            }
        }
        if (control.type == ControlType.BUTTON) {
            choice(body, "Shape", ControlShape.values().toList(), control.shape) {
                val r = workspace.placements[control.id] ?: control.bounds
                if (it == ControlShape.CIRCLE) workspace.setGeometry(control.id, r.copy(height = r.width))
                change { c -> c.copy(shape = it) }
            }
        }
        if (control.type != ControlType.BUTTON_GROUP || childIndex == null) {
            number(body, "Opacity (0–100%)", control.opacity * 100, 0f, 100f, true) { value -> change { it.copy(opacity = value / 100) } }
            if (control.type == ControlType.STICK && control.triggerOnly) {
                number(body, "Active stick diameter (dp)", control.stickDiameter, 1f, 1000f) { change { c -> c.copy(stickDiameter = it) } }
            }
        }
        body.addView(label("Opacity never disables input. Use Enabled in the control list to disable a control."))
    }

    private fun geometryProperties(body: LinearLayout, control: TouchControl) {
        if (control.type == ControlType.BUTTON_GROUP && childIndex != null) {
            body.addView(label("Child keys use the whole group's fixed geometry.")); return
        }
        val rect = workspace.placements[control.id] ?: return
        body.addView(label("Safe-area pixels · ${workspace.safe.width().roundToInt()} × ${workspace.safe.height().roundToInt()}"))
        fun geometryError(rect: TouchRect): String? = if (workspace.canSetGeometry(control.id, rect)) null else "Outside the safe area or overlaps another area"
        number(body, "X (px)", rect.x, 0f, workspace.safe.width(), validate = { value ->
            geometryError((workspace.placements[control.id] ?: rect).copy(x = value))
        }) { value ->
            workspace.placements[control.id]?.let { r -> if (!workspace.setGeometry(control.id, r.copy(x = value))) workspace.message("Position exceeds the safe area or overlaps another area") }
        }
        number(body, "Y (px)", rect.y, 0f, workspace.safe.height(), validate = { value ->
            geometryError((workspace.placements[control.id] ?: rect).copy(y = value))
        }) { value ->
            workspace.placements[control.id]?.let { r -> if (!workspace.setGeometry(control.id, r.copy(y = value))) workspace.message("Position exceeds the safe area or overlaps another area") }
        }
        val circle = control.type == ControlType.BUTTON && control.shape == ControlShape.CIRCLE
        number(body, if (circle) "Diameter (px)" else "Width (px)", rect.width, 1f, workspace.safe.width(), validate = { value ->
            val current = workspace.placements[control.id] ?: rect
            geometryError(current.copy(width = value, height = if (circle) value else current.height))
        }) { value ->
            workspace.placements[control.id]?.let { r -> if (!workspace.setGeometry(control.id, r.copy(width = value, height = if (circle) value else r.height))) workspace.message("Invalid size or area conflict") }
        }
        if (!circle) {
            number(body, "Height (px)", rect.height, 1f, workspace.safe.height(), validate = { value ->
                geometryError((workspace.placements[control.id] ?: rect).copy(height = value))
            }) { value ->
                workspace.placements[control.id]?.let { r -> if (!workspace.setGeometry(control.id, r.copy(height = value))) workspace.message("Invalid size or area conflict") }
            }
        }
        val steps = row()
        listOf("←" to (-1f to 0f), "↑" to (0f to -1f), "↓" to (0f to 1f), "→" to (1f to 0f)).forEach { (label, d) ->
            steps.addView(
                button("$label 1px") {
                    workspace.placements[control.id]?.let { workspace.setGeometry(control.id, it.copy(x = it.x + d.first, y = it.y + d.second)) }
                }
            )
        }
        body.addView(horizontal(steps))
    }

    private fun bindingFields(body: LinearLayout, binding: ButtonBinding, update: (ButtonBinding) -> Unit) {
        var current = binding
        val mappingButton = button("Simultaneous: ${binding.buttons.joinToString(" + ") { buttonName(it) }}") {}
        mappingButton.setOnClickListener {
            mapping(current.buttons, false) { buttons ->
                current = current.copy(buttons = buttons)
                update(current)
                mappingButton.text = "Simultaneous: ${buttons.joinToString(" + ") { buttonName(it) }}"
            }
        }
        body.addView(mappingButton)
        toggle(body, "Turbo · starts immediately, stops on release", binding.turbo) {
            current = current.copy(turbo = it); update(current)
        }
        number(
            body,
            "Turbo frequency (1–60 Hz)",
            binding.frequencyHz,
            TouchValidator.MIN_FREQUENCY_HZ,
            TouchValidator.MAX_FREQUENCY_HZ,
            true
        ) {
            current = current.copy(frequencyHz = it); update(current)
        }
        body.addView(label("Game input sampling limits the effective frequency. Combo members pulse together."))
    }

    private fun presetChoice(body: LinearLayout, selected: String?, update: (String) -> Unit) {
        val presets = workspace.draft.presets
        val current = presets.firstOrNull { it.id == selected }
        val refs = workspace.draft.templates.sumOf { t -> t.controls.count { it.swipePresetId == selected } }
        body.addView(label("Continuous shared reference · $refs areas"))
        body.addView(
            button("Preset: ${current?.name ?: "Missing — select a preset"}") {
                if (presets.isEmpty()) { workspace.message("No swipe presets available"); return@button }
                var index = presets.indexOfFirst { it.id == selected }.coerceAtLeast(0)
                MaterialAlertDialogBuilder(context).setTitle("Select shared swipe preset")
                    .setSingleChoiceItems(presets.map { it.name }.toTypedArray(), index) { _, i -> index = i }
                    .setNegativeButton("Cancel", null).setPositiveButton("Select") { _, _ -> update(presets[index].id) }.show()
            }
        )
    }

    fun showTuning(id: String, live: Boolean, tab: Int = 0, before: TouchLibrary? = null) {
        val preset = workspace.draft.presets.firstOrNull { it.id == id } ?: return
        val snapshot = before ?: workspace.draft
        val refs = workspace.draft.templates.sumOf { t -> t.controls.count { it.swipePresetId == id } }
        val body = openPanel(
            if (live) "${workspace.selected?.name} · ${workspace.selected?.stickTarget} · LIVE" else "Tune ${preset.name}",
            !live,
            if (live) ({ workspace.finishTest(false) }) else null,
            key = "preset:$id:$tab"
        )
        if (!live) panelBefore = snapshot
        body.addView(label("${preset.name} · $refs area references · ${if (live) "immediate preview, not saved" else "draft only"}"))
        val tabs = row()
        listOf("Basic", "Advanced", "Notes").forEachIndexed { i, label ->
            tabs.addView(
                button(if (i == tab) "• $label" else label) {
                    if (validFields()) showTuning(id, live, i, snapshot)
                }
            )
        }
        body.addView(horizontal(tabs))
        fun update(block: (SwipePreset) -> SwipePreset) {
            workspace.draft.presets.firstOrNull { it.id == id }?.let { workspace.updatePreset(block(it)) }
        }
        if (tab == 0) {
            number(body, "Horizontal sensitivity (>0)", preset.sensitivityX, 0.01f, 10f, true) { update { p -> p.copy(sensitivityX = it) } }
            number(body, "Vertical sensitivity (>0)", preset.sensitivityY, 0.01f, 10f, true) { update { p -> p.copy(sensitivityY = it) } }
            toggle(body, "Invert X", preset.invertX) { update { p -> p.copy(invertX = it) } }
            toggle(body, "Invert Y", preset.invertY) { update { p -> p.copy(invertY = it) } }
            toggle(body, "Swap axes", preset.swapAxes) { update { p -> p.copy(swapAxes = it) } }
            val scales = row()
            MotionScale.values().forEach { next ->
                scales.addView(
                    button("Scale: $next") {
                        val shortEdgeDp = min(workspace.safe.width(), workspace.safe.height()) / resources.displayMetrics.density
                        update { p ->
                            val speed = if (p.scale == next) {
                                p.maximumSpeed
                            } else if (next == MotionScale.DP) {
                                p.maximumSpeed * shortEdgeDp
                            } else {
                                p.maximumSpeed / shortEdgeDp.coerceAtLeast(1f)
                            }
                            p.copy(scale = next, maximumSpeed = speed)
                        }
                    }
                )
            }
            body.addView(scales)
            body.addView(label("Speed uses dp/second for DP or screen-short-edges/second for SHORT_EDGE. Switching scale converts maximum speed."))
            body.addView(
                SwipeCurveView(context) { workspace.draft.presets.first { it.id == id } },
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, px(130))
            )
            body.addView(label("Response preview · input → output, not measured game camera feedback"))
            body.addView(
                button("Reset basic group") {
                    update { p ->
                        p.copy(
                            sensitivityX = 1f,
                            sensitivityY = 1f,
                            invertX = false,
                            invertY = false,
                            swapAxes = false,
                            maximumSpeed = if (p.scale == MotionScale.DP) {
                                p.maximumSpeed /
                                    (min(workspace.safe.width(), workspace.safe.height()) / resources.displayMetrics.density).coerceAtLeast(1f)
                            } else {
                                p.maximumSpeed
                            },
                            scale = MotionScale.SHORT_EDGE
                        )
                    }
                    showTuning(id, live, tab, snapshot)
                }
            )
        } else if (tab == 1) {
            expandable(body, "Response and curve") { section ->
                number(section, "Deadzone compensation [0–0.95]", preset.deadzone, 0f, 0.95f, true) { update { p -> p.copy(deadzone = it) } }
                number(section, "Exponent (>0)", preset.exponent, 0.1f, 5f, true) { update { p -> p.copy(exponent = it, curve = emptyList()) } }
                val curve = textField(section, "Curve points: input:output, …", preset.curve.joinToString(", ") { "${it.input}:${it.output}" }, true) { value ->
                    val points = if (value.isBlank()) {
                        emptyList()
                    } else {
                        value.split(',').mapNotNull { pair ->
                            val parts = pair.trim().split(':')
                            if (parts.size != 2) {
                                null
                            } else {
                                parts[0].toFloatOrNull()?.let { x ->
                                    parts[1].toFloatOrNull()?.let { y -> CurvePoint(x, y) }
                                }
                            }
                        }
                    }
                    val valid = value.isBlank() || (
                        points.size == value.split(',').size && points.size >= 2 &&
                            points.first() == CurvePoint(0f, 0f) && points.last() == CurvePoint(1f, 1f) &&
                            points.all { it.input.isFinite() && it.output.isFinite() && it.input in 0f..1f && it.output in 0f..1f } &&
                            points.zipWithNext().all { (a, b) -> a.input < b.input && a.output <= b.output }
                        )
                    if (valid) update { p -> p.copy(curve = points) }
                }
                (curve.editText as? TextInputEditText)?.doAfterTextChanged { editable ->
                    val candidate = editable?.toString().orEmpty()
                    val valid = candidate.isBlank() || runCatching {
                        val ps = candidate.split(',').map { pair -> pair.trim().split(':').let { CurvePoint(it[0].toFloat(), it[1].toFloat()) } }
                        ps.size >= 2 && ps.first() == CurvePoint(0f, 0f) && ps.last() == CurvePoint(1f, 1f) &&
                            ps.all { it.input.isFinite() && it.output.isFinite() && it.input in 0f..1f && it.output in 0f..1f } &&
                            ps.zipWithNext().all { (a, b) -> a.input < b.input && a.output <= b.output }
                    }.getOrDefault(false)
                    setError(curve, if (valid) null else "Use increasing inputs, monotonic outputs, from 0:0 to 1:1")
                }
                section.addView(
                    button("Reset response group") {
                        update { it.copy(deadzone = 0.15f, exponent = 1f, curve = emptyList()) }
                        showTuning(id, live, tab, snapshot)
                    }
                )
            }
            expandable(body, "Timing and saturation") { section ->
                val dpScale = preset.scale == MotionScale.DP
                number(
                    section,
                    if (dpScale) "Maximum speed (dp/s)" else "Maximum speed (short edges/s)",
                    preset.maximumSpeed,
                    if (dpScale) 0.01f else 0.001f,
                    if (dpScale) 10000f else 20f,
                    true
                ) {
                    update { p -> p.copy(maximumSpeed = it) }
                }
                number(section, "Smoothing (0–10000 ms)", preset.smoothingMs, 0f, 10000f) { update { p -> p.copy(smoothingMs = it) } }
                number(section, "Stop timeout (1–10000 ms)", preset.stopTimeoutMs.toFloat(), 1f, 10000f) { update { p -> p.copy(stopTimeoutMs = it.toLong()) } }
                number(section, "Accumulation cap (1–1000 ms)", preset.maxAccumulationMs.toFloat(), 1f, 1000f) { update { p -> p.copy(maxAccumulationMs = it.toLong()) } }
                section.addView(label("Release always clears pending motion. Excess beyond the accumulation cap is discarded."))
                section.addView(
                    button("Reset timing group") {
                        update {
                            it.copy(
                                maximumSpeed = if (it.scale == MotionScale.DP) {
                                    2f * min(workspace.safe.width(), workspace.safe.height()) / resources.displayMetrics.density
                                } else {
                                    2f
                                },
                                smoothingMs = 0f,
                                stopTimeoutMs = 40,
                                maxAccumulationMs = 16
                            )
                        }
                        showTuning(id, live, tab, snapshot)
                    }
                )
            }
        } else {
            textField(body, "Preset name", preset.name, false) { name -> update { it.copy(name = name) } }
            textField(body, "Calibration / game sensitivity notes", preset.notes, true, multiline = true) { notes -> update { it.copy(notes = notes) } }
            body.addView(label("Joystick-based open-loop estimate. Measure actual camera response in your game; this editor does not read camera angles."))
        }
        if (live) {
            body.addView(button("Return to editing · keep draft") { if (validFields()) workspace.finishTest(false) })
            body.addView(button("Undo this debug session") { workspace.finishTest(true) })
            body.addView(button("Hide all tools · Back restores") { workspace.hideTools() })
        } else {
            body.addView(button("Done") { if (validFields()) completePanel() })
            body.addView(button("Cancel tuning") { cancelPanel() })
            body.addView(
                button(if (workspace.gameAvailable) "Full-screen game test" else "Full-screen local test") {
                    if (validFields()) { completePanel(); workspace.startTest(false) }
                }
            )
        }
    }

    private fun gestureTarget(current: GestureBinding?, update: (GestureBinding?) -> Unit) {
        val options = arrayOf("Controller button", "Left stick direction", "Right stick direction", "Unassigned")
        MaterialAlertDialogBuilder(context).setTitle("Gesture output · single target")
            .setItems(options) { _, index ->
                when (index) {
                    0 -> mapping(current?.button?.let { setOf(it) } ?: emptySet(), true) { update(GestureBinding(button = it.first())) }
                    1, 2 -> {
                        val body = column()
                        var direction = current?.direction?.takeIf { it != GestureDirection.TAP } ?: GestureDirection.UP
                        body.addView(label("Output vector · choose one direction"))
                        compass(body, true, false, direction) { value -> direction = value }
                        MaterialAlertDialogBuilder(context).setTitle(if (index == 1) "Left stick output" else "Right stick output")
                            .setView(body).setNegativeButton("Cancel", null)
                            .setPositiveButton("Use direction") { _, _ ->
                                update(
                                    GestureBinding(
                                        stick = if (index == 1) StickTarget.LEFT else StickTarget.RIGHT,
                                        direction = direction
                                    )
                                )
                            }.show()
                    }
                    else -> update(null)
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun mapping(initial: Set<Int>, single: Boolean, allowEmpty: Boolean = false, update: (Set<Int>) -> Unit) {
        val values = initial.filter { it in TouchValidator.buttonTargets }.toMutableSet()
        val body = column()
        body.addView(label(if (single) "Choose one output button" else "Outputs are pressed simultaneously, not a sequence."))
        val summary = ChipGroup(context).apply { chipSpacingHorizontal = px(4); chipSpacingVertical = px(4) }
        body.addView(summary)
        val tabs = ChipGroup(context).apply { isSingleSelection = true; isSelectionRequired = true }
        val grid = ChipGroup(context).apply { chipSpacingHorizontal = px(8); chipSpacingVertical = px(8) }
        body.addView(horizontal(tabs))
        body.addView(grid)
        val categories = listOf(
            "Face" to listOf(0, 1, 2, 3),
            "Shoulders" to listOf(6, 7, 8, 9),
            "System" to listOf(4, 5, 10, 11, 18, 19),
            "Directions" to listOf(13, 14, 15, 12)
        )
        var category = 0
        var rebuilding: Boolean
        fun refresh() {
            rebuilding = true
            summary.removeAllViews()
            if (values.isEmpty()) summary.addView(label(if (allowEmpty) "No auxiliary output" else "Select at least one output"))
            for (id in values.sorted()) summary.addView(
                Chip(context).apply {
                    text = buttonName(id)
                    isCloseIconVisible = true
                    minHeight = px(48)
                    setEnsureMinTouchTargetSize(true)
                    contentDescription = "Selected ${buttonName(id)}. Remove output."
                    setOnCloseIconClickListener { values -= id; refresh() }
                }
            )
            grid.removeAllViews()
            grid.isSingleSelection = false
            for (id in categories[category].second) grid.addView(
                Chip(context).apply {
                    text = buttonName(id)
                    isCheckable = true
                    isChecked = id in values
                    minHeight = px(48)
                    setEnsureMinTouchTargetSize(true)
                    contentDescription = "Map ${buttonName(id)}"
                    setOnCheckedChangeListener { _, checked ->
                        if (!rebuilding) {
                            if (single && checked) values.clear()
                            if (checked) values += id else values -= id
                            refresh()
                        }
                    }
                }
            )
            rebuilding = false
        }
        categories.forEachIndexed { index, (name, _) ->
            tabs.addView(
                Chip(context).apply {
                    id = View.generateViewId()
                    text = name
                    isCheckable = true
                    isChecked = index == 0
                    minHeight = px(48)
                    setEnsureMinTouchTargetSize(true)
                    setOnCheckedChangeListener { _, checked -> if (checked) { category = index; refresh() } }
                }
            )
        }
        refresh()
        val scroll = ScrollView(context).apply { addView(body) }
        val dialog = MaterialAlertDialogBuilder(context).setTitle(if (single) "Button output" else "Button combination")
            .setView(scroll).setNegativeButton("Cancel", null).setNeutralButton("Clear", null)
            .setPositiveButton("Done", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (values.isEmpty() && !allowEmpty) {
                    workspace.message("Select at least one button")
                } else {
                    val apply = { update(values.toSet()); dialog.dismiss() }
                    if (values.any { it == 18 || it == 19 }) {
                        MaterialAlertDialogBuilder(context)
                            .setTitle("System button mapping").setMessage("Home and Capture may execute their real system action during game tests.")
                            .setPositiveButton("Use mapping") { _, _ -> apply() }.setNegativeButton("Cancel", null).show()
                    } else {
                        apply()
                    }
                }
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                values.clear()
                refresh()
            }
        }
        dialog.show()
    }

    private fun compass(
        body: LinearLayout,
        diagonals: Boolean,
        tap: Boolean,
        selected: GestureDirection?,
        summary: ((GestureDirection) -> String)? = null,
        update: (GestureDirection) -> Unit
    ) {
        val directions = listOf(
            GestureDirection.UP_LEFT, GestureDirection.UP, GestureDirection.UP_RIGHT,
            GestureDirection.LEFT, GestureDirection.TAP, GestureDirection.RIGHT,
            GestureDirection.DOWN_LEFT, GestureDirection.DOWN, GestureDirection.DOWN_RIGHT
        )
        val arrows = listOf("↖", "↑", "↗", "←", "Tap", "→", "↙", "↓", "↘")
        val buttons = mutableMapOf<GestureDirection, MaterialButton>()
        directions.chunked(3).forEachIndexed { rowIndex, values ->
            val row = row()
            values.forEachIndexed { column, direction ->
                val enabled = if (direction == GestureDirection.TAP) {
                    tap
                } else {
                    diagonals ||
                        direction in listOf(GestureDirection.UP, GestureDirection.RIGHT, GestureDirection.DOWN, GestureDirection.LEFT)
                }
                val title = arrows[rowIndex * 3 + column] + (summary?.let { "\n${it(direction)}" } ?: "")
                val button = button(title) {
                    if (selected != null) buttons.forEach { (target, view) -> view.isChecked = target == direction }
                    update(direction)
                }.apply {
                    isEnabled = enabled
                    isCheckable = selected != null
                    isChecked = selected == direction
                    contentDescription = "$direction${summary?.let { ": ${it(direction)}" } ?: ""}"
                    maxLines = 4
                }
                buttons[direction] = button
                row.addView(button, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            body.addView(row)
        }
    }

    private fun gestureSummary(value: GestureBinding?): String = when {
        value == null -> "Unassigned"
        value.button != null -> buttonName(value.button)
        else -> "${value.stick} stick ${value.direction}"
    }

    private fun change(block: (TouchControl) -> TouchControl): Boolean = workspace.selected?.let { workspace.updateControl(block(it)) } ?: false

    private fun <T> choice(body: LinearLayout, label: String, values: List<T>, selected: T, update: (T) -> Unit) {
        val button = button("$label: $selected") {}
        button.setOnClickListener {
            workspace.runtime.cancelAll()
            hasModalDialog = true
            val dialog = MaterialAlertDialogBuilder(context).setTitle(label)
                .setSingleChoiceItems(values.map { it.toString() }.toTypedArray(), values.indexOf(selected)) { dialog, which ->
                    update(values[which]); button.text = "$label: ${values[which]}"; dialog.dismiss()
                }.setNegativeButton("Cancel", null).create()
            dialog.setOnDismissListener { hasModalDialog = false }
            dialog.show()
        }
        body.addView(button)
    }

    private fun toggle(body: LinearLayout, title: String, checked: Boolean, update: (Boolean) -> Unit) {
        body.addView(
            MaterialSwitch(context).apply {
                text = title; isChecked = checked; minHeight = px(48)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                setOnCheckedChangeListener { _, value -> update(value) }
            }
        )
    }

    private fun number(
        body: LinearLayout,
        title: String,
        value: Float,
        min: Float,
        max: Float,
        slider: Boolean = false,
        validate: ((Float) -> String?)? = null,
        update: (Float) -> Unit
    ) {
        val input = TextInputLayout(context).apply { hint = title }
        val edit = TextInputEditText(input.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(format(value)); setSingleLine()
        }
        input.addView(edit)
        body.addView(input, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        var updating = false
        var sliderView: Slider? = null
        edit.doAfterTextChanged {
            val number = it?.toString()?.toFloatOrNull()
            if (number == null || !number.isFinite() || number < min || number > max) {
                setError(input, "Enter a finite value in $min–$max")
            } else {
                val error = validate?.invoke(number)
                setError(input, error)
                if (error == null && !updating) {
                    updating = true
                    sliderView?.value = number.coerceIn(min, max)
                    update(number)
                    updating = false
                }
            }
        }
        if (slider && max > min) {
            val view = Slider(context).apply {
                valueFrom = min; valueTo = max; this.value = value.coerceIn(min, max)
                contentDescription = title
                addOnChangeListener { _, next, fromUser ->
                    if (fromUser && !updating) {
                        updating = true
                        edit.setText(format(next))
                        setError(input, null)
                        update(next)
                        updating = false
                    }
                }
            }
            sliderView = view
            body.addView(view)
        }
    }

    private fun textField(
        body: LinearLayout,
        title: String,
        value: String,
        allowEmpty: Boolean,
        multiline: Boolean = false,
        update: (String) -> Unit
    ): TextInputLayout {
        val input = TextInputLayout(context).apply { hint = title }
        val edit = TextInputEditText(input.context).apply {
            setText(value)
            if (!multiline) setSingleLine() else minLines = 3
        }
        input.addView(edit)
        body.addView(input, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        edit.doAfterTextChanged {
            val text = it?.toString().orEmpty()
            setError(input, if (!allowEmpty && text.isBlank()) "Must not be empty" else null)
            if (allowEmpty || text.isNotBlank()) update(text)
        }
        return input
    }

    private fun setError(input: TextInputLayout, error: String?) {
        input.error = error
        if (error == null) invalidFields -= input else invalidFields += input
    }

    private fun validFields(): Boolean {
        if (invalidFields.isEmpty()) return true
        invalidFields.first().requestFocus()
        workspace.message("Correct the highlighted fields first")
        return false
    }

    private fun expandable(body: LinearLayout, title: String, build: (LinearLayout) -> Unit) {
        val section = column().apply { visibility = GONE }
        body.addView(button(title) { section.visibility = if (section.visibility == VISIBLE) GONE else VISIBLE })
        build(section)
        body.addView(section)
    }

    private fun draggable(handle: View, target: View) {
        var startX = 0f
        var startY = 0f
        var originalX = 0f
        var originalY = 0f
        var pointer = -1
        handle.contentDescription = "Drag floating tool window"
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointer = event.getPointerId(0)
                    startX = event.rawX; startY = event.rawY
                    originalX = target.x; originalY = target.y
                    draggingTools = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.getPointerId(0) == pointer) {
                        target.x = (originalX + event.rawX - startX).coerceIn(0f, max(0f, width - target.width.toFloat()))
                        target.y = (originalY + event.rawY - startY).coerceIn(0f, max(0f, height - px(48f)))
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingTools = false
                    pointer = -1
                    if (target == toolbar) {
                        toolbarPosition = positionOf(target)
                    } else {
                        panelPosition = positionOf(target)
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun button(text: String, click: () -> Unit): MaterialButton = MaterialButton(
        context,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        this.text = text
        minHeight = px(48)
        minimumHeight = px(48)
        setPadding(px(10), px(4), px(10), px(4))
        setOnClickListener { click() }
    }

    private fun label(text: String, size: Float = 14f): TextView = TextView(context).apply {
        this.text = text; textSize = size
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
        setPadding(px(6), px(6), px(6), px(6))
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(px(8), px(4), px(8), px(4)) }
    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun horizontal(view: View) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(view)
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }
    private fun card() = MaterialCardView(context).apply {
        radius = px(16f)
        cardElevation = px(4f)
        setCardBackgroundColor(surfaceColor())
        isClickable = true
    }
    private fun surfaceColor(): Int {
        val color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
        return (color and 0x00ffffff) or ((panelAlpha * 255).toInt() shl 24)
    }
    private fun panelWidth() = min(px(360), max(px(180), workspace.width - px(24)))
    private fun panelHeight() = max(px(120), min(px(430), workspace.height - px(110)))
    private fun px(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun px(value: Float) = value * resources.displayMetrics.density
    private fun format(value: Float) = if (abs(value - value.roundToInt()) < 0.0001f) value.roundToInt().toString() else "%.3f".format(java.util.Locale.ROOT, value)
    private fun buttonName(id: Int) = mapOf(
        0 to "A", 1 to "B", 2 to "X", 3 to "Y", 4 to "L3", 5 to "R3", 6 to "L", 7 to "R",
        8 to "ZL", 9 to "ZR", 10 to "+", 11 to "−", 12 to "D-left", 13 to "D-up", 14 to "D-right", 15 to "D-down", 18 to "Home", 19 to "Capture"
    )[id] ?: id.toString()
}
