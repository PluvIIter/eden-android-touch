// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.ui

import android.content.Context
import android.graphics.RectF
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout
import org.yuzu.yuzu_emu.R
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
    private var panelFooter: LinearLayout? = null
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
        toolbar.radius = px(28f)
        toolbarContent.setPadding(px(4), px(4), px(4), px(4))
        toolbarContent.addView(actions)
        toolbar.addView(toolbarContent)
        addView(
            toolbar,
            LayoutParams(toolbarWidth(), LayoutParams.WRAP_CONTENT).apply {
                leftMargin = px(12)
                topMargin = px(12)
            }
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post {
            toolbarPosition?.let { applyPosition(toolbar, it) }
            panel?.let { view ->
                view.layoutParams?.let { params ->
                    params.height = if (panelCollapsed) px(64) else panelHeight()
                    view.layoutParams = params
                }
                panelPosition?.let { applyPosition(view, it) }
            }
            post { avoidSelection() }
        }
    }

    fun refreshToolbar() {
        toolbar.layoutParams?.let { params ->
            val target = if (collapsed) px(48) else toolbarWidth()
            if (params.width != target) { params.width = target; toolbar.layoutParams = params }
        }
        actions.removeAllViews()
        toolbarContent.setPadding(if (collapsed) 0 else px(4), if (collapsed) 0 else px(4), if (collapsed) 0 else px(4), if (collapsed) 0 else px(4))
        if (collapsed) {
            val entry = iconButton(R.drawable.touch_tools_edit, s(R.string.touch_tools_show_tools)) {
                collapsed = false
                refreshToolbar()
            }
            draggable(entry, toolbar, true)
            actions.addView(entry)
            return
        }
        val compact = toolbarWidth() < px(520)
        val veryCompact = toolbarWidth() < px(360)
        val handle = iconButton(R.drawable.touch_tools_drag, s(R.string.touch_tools_drag)) {}
        draggable(handle, toolbar)
        actions.addView(handle)
        val heading = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        title.apply {
            text = workspace.template.name
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, 0, 0)
        }
        (title.parent as? LinearLayout)?.removeView(title)
        heading.addView(title)
        if (!veryCompact) {
            heading.addView(
                label(s(if (workspace.testing) R.string.touch_tools_test_draft else R.string.touch_tools_draft), 11f).apply {
                    setPadding(0, 0, 0, 0)
                    setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                }
            )
        }
        draggable(heading, toolbar)
        actions.addView(heading, LinearLayout.LayoutParams(0, px(48), 1f))
        if (!workspace.testing && !compact) {
            actions.addView(iconButton(R.drawable.touch_tools_add, s(R.string.touch_tools_add)) { showAdd() })
            actions.addView(iconButton(R.drawable.touch_tools_undo, s(R.string.touch_tools_undo)) { workspace.undo() })
            actions.addView(iconButton(R.drawable.touch_tools_list, s(R.string.touch_tools_controls)) { showControls() })
        }
        if (!veryCompact) {
            actions.addView(
                button(s(if (workspace.testing) R.string.touch_tools_return_edit else R.string.touch_tools_test)) {
                    if (workspace.testing) {
                        workspace.finishTest(false)
                    } else if (validFields()) { completePanel(); workspace.startTest(false) }
                }.apply { setPadding(px(12), 0, px(12), 0) }
            )
        }
        actions.addView(
            primaryButton(s(R.string.touch_tools_save)) {
                if (workspace.testing) workspace.finishTest(false)
                if (validFields()) { completePanel(); workspace.save() }
            }.apply {
                icon = ContextCompat.getDrawable(context, R.drawable.touch_tools_save)
                iconSize = px(18)
                iconPadding = px(6)
                iconTint = ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorOnPrimary))
                setPadding(px(12), 0, px(12), 0)
            }
        )
        actions.addView(iconButton(R.drawable.touch_tools_more, s(R.string.touch_tools_more)) { showOverflow(it) })
    }

    private fun showOverflow(anchor: View) {
        val popup = PopupMenu(context, anchor)
        fun item(label: Int, action: () -> Unit) { popup.menu.add(s(label)).setOnMenuItemClickListener { action(); true } }
        if (workspace.testing) {
            item(R.string.touch_tools_return_edit) { workspace.finishTest(false) }
            workspace.selected?.swipePresetId?.let { id -> item(R.string.touch_tools_parameters) { showTuning(id, true) } }
            item(R.string.touch_tools_undo_debug) { workspace.finishTest(true) }
        } else {
            item(R.string.touch_tools_add) { showAdd() }
            item(R.string.touch_tools_undo) { workspace.undo() }
            item(R.string.touch_tools_redo) { workspace.redo() }
            item(R.string.touch_tools_controls) { showControls() }
            item(R.string.touch_tools_test) { if (validFields()) { completePanel(); workspace.startTest(false) } }
            workspace.selected?.let {
                item(R.string.touch_tools_properties) { showProperties() }
                item(R.string.touch_tools_adjust) { if (validFields()) { completePanel(); workspace.hideTools(true) } }
                if (it.type == ControlType.SWIPE && workspace.gameAvailable) {
                    item(R.string.touch_tools_live_debug) {
                        if (validFields()) { completePanel(); workspace.startTest(true) }
                    }
                }
            }
            item(R.string.touch_tools_set_base) { if (validFields()) { completePanel(); workspace.setCurrentScreenAsBase() } }
        }
        item(R.string.touch_tools_tool_opacity) { showToolOpacity() }
        item(R.string.touch_tools_hide_tools) { workspace.hideTools() }
        item(R.string.touch_tools_fold) { collapsed = true; refreshToolbar() }
        popup.show()
    }

    fun closePanel() {
        panelKey?.let { key -> panelScroll?.let { scrollPositions[key] = it.scrollY } }
        panelKey = null
        panel?.let { panelPosition = positionOf(it); removeView(it) }
        panel = null
        panelBody = null
        panelFooter = null
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
        val heading = label(name, 16f).apply { setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium) }
        header.addView(heading, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            iconButton(R.drawable.touch_tools_fold, s(R.string.touch_tools_fold)) {
                panelCollapsed = !panelCollapsed
                panelScroll?.visibility = if (panelCollapsed) GONE else VISIBLE
                panelFooter?.visibility = if (panelCollapsed) GONE else VISIBLE
                surface.layoutParams?.let { params ->
                    params.height = if (panelCollapsed) px(64) else panelHeight()
                    surface.layoutParams = params
                }
                post { avoidSelection() }
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
        content.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        panelFooter = row().apply { gravity = Gravity.END; visibility = GONE }
        content.addView(panelFooter, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        surface.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            surface,
            LayoutParams(panelWidth(), if (panelCollapsed) px(64) else panelHeight()).apply {
                leftMargin = px(8)
                topMargin = px(72)
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
            if (view.visibility != VISIBLE || view.width <= 0 || width <= 0 || height <= 0) continue
            val margin = px(8f)
            val minX = margin
            val minY = margin
            val maxX = max(minX, width - view.width - margin)
            val maxY = max(minY, height - view.height - margin)
            val belowToolbar = toolbar.y + toolbar.height + margin
            val candidates = listOf(
                view.x to view.y,
                minX to belowToolbar,
                maxX to belowToolbar,
                minX to minY,
                maxX to minY,
                minX to maxY,
                maxX to maxY
            ).distinct().filter { (x, y) ->
                x >= minX && y >= minY && x + view.width <= width - margin + 0.01f &&
                    y + view.height <= height - margin + 0.01f
            }
            fun rect(position: Pair<Float, Float>) = RectF(
                position.first,
                position.second,
                position.first + view.width,
                position.second + view.height
            )
            val position = candidates.firstOrNull { candidate ->
                val bounds = rect(candidate)
                (avoid == null || !RectF.intersects(bounds, avoid)) && occupied.none { RectF.intersects(bounds, it) }
            } ?: candidates.firstOrNull { candidate -> occupied.none { RectF.intersects(rect(candidate), it) } }
                ?: (view.x.coerceIn(minX, maxX) to view.y.coerceIn(minY, maxY))
            view.x = position.first
            view.y = position.second
            occupied += rect(position)
        }
    }

    private fun showToolOpacity() {
        val body = openPanel(s(R.string.touch_tools_tool_background))
        number(body, s(R.string.touch_tools_background_opacity), panelAlpha, 0.4f, 1f, true) {
            panelAlpha = it
            toolbar.setCardBackgroundColor(surfaceColor())
            panel?.setCardBackgroundColor(surfaceColor())
        }
        body.addView(button(s(R.string.touch_tools_done)) { closePanel() })
    }

    private fun showControls() {
        val body = openPanel(s(R.string.touch_tools_controls_title))
        val ordered = workspace.template.controls.sortedBy { if (it.isArea) 0 else 1 }.asReversed()
        var areaSection: Boolean? = null
        for (control in ordered) {
            if (areaSection != control.isArea) {
                areaSection = control.isArea
                body.addView(
                    label(s(if (control.isArea) R.string.touch_tools_area_layer else R.string.touch_tools_top_layer), 12f).apply {
                        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    }
                )
            }
            val container = column()
            val item = button(s(R.string.touch_tools_control_row, control.name, enumLabel(control.type))) {
                workspace.select(control.id)
                showProperties()
            }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
            }
            container.addView(item, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            toggle(container, s(R.string.touch_tools_enabled), control.enabled) {
                workspace.checkpoint()
                workspace.template.controls.firstOrNull { c -> c.id == control.id }?.let { c -> workspace.updateControl(c.copy(enabled = it)) }
            }
            toggle(container, s(R.string.touch_tools_lock_geometry), control.locked) {
                workspace.checkpoint()
                workspace.template.controls.firstOrNull { c -> c.id == control.id }?.let { c -> workspace.updateControl(c.copy(locked = it)) }
            }
            body.addView(container)
            body.addView(
                View(context).apply { setBackgroundColor(themeColor(com.google.android.material.R.attr.colorOutlineVariant)) },
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, px(1))
            )
        }
        body.addView(button(s(R.string.touch_tools_adjust_selected)) { closePanel(); workspace.hideTools(true) })
    }

    private fun showAdd() {
        val body = openPanel(s(R.string.touch_tools_add_title))
        val descriptions = mapOf(
            ControlType.BUTTON to s(R.string.touch_tools_button_description),
            ControlType.BUTTON_GROUP to s(R.string.touch_tools_group_description),
            ControlType.STICK to s(R.string.touch_tools_stick_description),
            ControlType.SWIPE to s(R.string.touch_tools_swipe_description),
            ControlType.GESTURE to s(R.string.touch_tools_gesture_description)
        )
        for ((type, name) in descriptions) body.addView(button(name) { configureNew(type) })
    }

    private fun configureNew(type: ControlType) {
        var control = TouchControl(
            name = enumLabel(type),
            type = type,
            shape = if (type == ControlType.SWIPE || type == ControlType.GESTURE) ControlShape.RECTANGLE else ControlShape.CIRCLE,
            swipePresetId = workspace.draft.presets.firstOrNull()?.id,
            gestures = if (type == ControlType.GESTURE) mapOf(GestureDirection.TAP to GestureBinding(button = 0)) else emptyMap()
        )
        val body = openPanel(s(R.string.touch_tools_new_control, control.name))
        textField(body, s(R.string.touch_tools_name), control.name, false) { control = control.copy(name = it) }
        when (type) {
            ControlType.BUTTON -> body.addView(
                button(s(R.string.touch_tools_select_mapping)) {
                    mapping(control.binding.buttons, false) { control = control.copy(binding = control.binding.copy(buttons = it)) }
                }
            )
            ControlType.BUTTON_GROUP -> choice(body, s(R.string.touch_tools_group_style), GroupStyle.values().toList(), control.groupStyle) {
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
                choice(body, s(R.string.touch_tools_target_stick), StickTarget.values().toList(), control.stickTarget) { control = control.copy(stickTarget = it) }
                toggle(body, s(R.string.touch_tools_free_stick), false) { control = control.copy(freeStick = it, triggerOnly = false) }
            }
            ControlType.SWIPE -> {
                choice(body, s(R.string.touch_tools_target_stick), StickTarget.values().toList(), control.stickTarget) { control = control.copy(stickTarget = it) }
                if (workspace.draft.presets.isEmpty()) {
                    body.addView(label(s(R.string.touch_tools_create_default_preset)))
                } else {
                    presetChoice(body, control.swipePresetId) { control = control.copy(swipePresetId = it) }
                }
            }
            ControlType.GESTURE -> body.addView(
                button(s(R.string.touch_tools_tap_default)) {
                    gestureTarget(control.gestures[GestureDirection.TAP]) {
                        control = control.copy(gestures = if (it == null) emptyMap() else mapOf(GestureDirection.TAP to it))
                    }
                }
            )
        }
        body.addView(label(s(R.string.touch_tools_place_hint)))
        body.addView(
            button(s(R.string.touch_tools_place)) {
                if (!validFields()) return@button
                if (control.name.isBlank()) { workspace.message(s(R.string.touch_tools_enter_name)); return@button }
                if (control.type == ControlType.GESTURE && control.gestures.isEmpty()) {
                    workspace.message(s(R.string.touch_tools_gesture_required)); return@button
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
        body.addView(button(s(R.string.touch_tools_cancel)) { showAdd() })
    }

    private fun showProperties(keepTransaction: TouchLibrary? = null) {
        val control = workspace.selected ?: return
        if (propertyId != control.id) { propertyTab = 0; childIndex = null; propertyId = control.id }
        val before = keepTransaction ?: workspace.draft
        val body = openPanel(
            s(R.string.touch_tools_control_properties, control.name),
            true,
            key = "control:${control.id}:$propertyTab:$childIndex"
        )
        panelBefore = before
        materialTabs(body, listOf(s(R.string.touch_tools_input), s(R.string.touch_tools_appearance), s(R.string.touch_tools_geometry)), propertyTab) { i ->
            if (validFields()) { propertyTab = i; showProperties(before) }
        }
        if (control.type == ControlType.BUTTON_GROUP) {
            val children = row()
            children.addView(
                button(s(R.string.touch_tools_whole_group)) {
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
        panelFooter?.apply {
            visibility = if (panelCollapsed) GONE else VISIBLE
            addView(button(s(R.string.touch_tools_cancel)) { cancelPanel() })
            addView(primaryButton(s(R.string.touch_tools_done)) { if (validFields()) { workspace.hideKeyboard(); completePanel() } })
        }
        body.addView(
            button(s(R.string.touch_tools_adjust)) {
                if (validFields()) { completePanel(); workspace.hideTools(true) }
            }
        )
        val moreActions = button(s(R.string.touch_tools_property_actions)) {}
        moreActions.setOnClickListener { showControlActions(it, control) }
        body.addView(moreActions)
    }

    private fun showControlActions(anchor: View, control: TouchControl) {
        val popup = PopupMenu(context, anchor)
        fun item(label: Int, action: () -> Unit) { popup.menu.add(s(label)).setOnMenuItemClickListener { action(); true } }
        item(R.string.touch_tools_duplicate) { if (validFields()) { completePanel(); workspace.duplicateSelected() } }
        item(R.string.touch_tools_bring_forward) { workspace.reorderSelected(true) }
        item(R.string.touch_tools_send_backward) { workspace.reorderSelected(false) }
        item(R.string.touch_tools_delete_control) {
            MaterialAlertDialogBuilder(context).setTitle(s(R.string.touch_tools_delete_confirm, control.name))
                .setNegativeButton(s(R.string.touch_tools_cancel), null).setPositiveButton(s(R.string.touch_tools_delete)) { _, _ ->
                    completePanel(); workspace.deleteSelected()
                }.show()
        }
        popup.show()
    }

    private fun inputProperties(body: LinearLayout, control: TouchControl, before: TouchLibrary) {
        when (control.type) {
            ControlType.BUTTON -> bindingFields(body, control.binding) { value -> change { it.copy(binding = value) } }
            ControlType.BUTTON_GROUP -> {
                val index = childIndex
                if (index == null) {
                    body.addView(label(s(R.string.touch_tools_group_hint, enumLabel(control.groupStyle))))
                } else {
                    val key = control.groupKeys[index]
                    body.addView(label(s(R.string.touch_tools_child_scope, key.text, listOf(s(R.string.touch_tools_top), s(R.string.touch_tools_right), s(R.string.touch_tools_bottom), s(R.string.touch_tools_left))[index])))
                    bindingFields(body, key.binding) { binding ->
                        change { c ->
                            c.copy(groupKeys = c.groupKeys.mapIndexed { i, k -> if (i == index) k.copy(binding = binding) else k })
                        }
                    }
                }
            }
            ControlType.STICK -> {
                choice(body, s(R.string.touch_tools_target_stick), StickTarget.values().toList(), control.stickTarget) { change { c -> c.copy(stickTarget = it) } }
                toggle(body, s(R.string.touch_tools_free_stick), control.freeStick) {
                    if (change { c -> c.copy(freeStick = it, triggerOnly = if (it) c.triggerOnly else false) }) showProperties(before)
                }
                if (control.freeStick) {
                    toggle(body, s(R.string.touch_tools_trigger_only), control.triggerOnly) {
                        if (change { c -> c.copy(triggerOnly = it) }) showProperties(before)
                    }
                }
                toggle(body, s(R.string.touch_tools_max_deflection), control.maximumDeflection) { change { c -> c.copy(maximumDeflection = it) } }
                number(body, s(R.string.touch_tools_stick_deadzone), control.stickDeadzone, 0f, 0.95f, true) { change { c -> c.copy(stickDeadzone = it) } }
                body.addView(
                    button(s(R.string.touch_tools_auxiliary, control.auxiliaryButton?.let { buttonName(it) } ?: s(R.string.touch_tools_none))) {
                        mapping(control.auxiliaryButton?.let { setOf(it) } ?: emptySet(), true, true) { values ->
                            change { it.copy(auxiliaryButton = values.firstOrNull()) }; showProperties(before)
                        }
                    }
                )
                if (control.auxiliaryButton != null) {
                    choice(body, s(R.string.touch_tools_aux_mode), AuxiliaryMode.values().toList(), control.auxiliaryMode) { change { c -> c.copy(auxiliaryMode = it) } }
                    body.addView(label(s(R.string.touch_tools_aux_hint)))
                    number(body, s(R.string.touch_tools_double_timeout), control.tapTimeoutMs.toFloat(), 1f, 10000f) { change { c -> c.copy(tapTimeoutMs = it.toLong()) } }
                    number(body, s(R.string.touch_tools_double_tolerance), control.tapSlop, 0f, 500f) { change { c -> c.copy(tapSlop = it) } }
                }
            }
            ControlType.SWIPE -> {
                choice(body, s(R.string.touch_tools_target_stick), StickTarget.values().toList(), control.stickTarget) { change { c -> c.copy(stickTarget = it) } }
                presetChoice(body, control.swipePresetId) { change { c -> c.copy(swipePresetId = it) }; showProperties(before) }
                body.addView(
                    button(s(R.string.touch_tools_tune_shared)) {
                        if (validFields()) { completePanel(); workspace.selected?.swipePresetId?.let { showTuning(it, false) } }
                    }
                )
                body.addView(
                    button(s(R.string.touch_tools_copy_tune)) {
                        if (!validFields()) return@button
                        completePanel()
                        val p = workspace.draft.presets.firstOrNull { it.id == workspace.selected?.swipePresetId } ?: return@button
                        workspace.checkpoint()
                        val copy = p.copy(id = newTouchId(), name = s(R.string.touch_tools_copy_name, p.name))
                        workspace.addPreset(copy)
                        change { it.copy(swipePresetId = copy.id) }
                        showTuning(copy.id, false)
                    }
                )
                if (workspace.gameAvailable) {
                    body.addView(
                        button(s(R.string.touch_tools_single_live)) {
                            if (validFields()) { completePanel(); workspace.startTest(true) }
                        }
                    )
                }
            }
            ControlType.GESTURE -> {
                toggle(body, s(R.string.touch_tools_eight_directions), control.diagonalGestures) { change { c -> c.copy(diagonalGestures = it) }; showProperties(before) }
                body.addView(label(s(R.string.touch_tools_recognition_hint)))
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
                number(body, s(R.string.touch_tools_recognition_distance), control.gestureDistance, 1f, 1000f) { change { c -> c.copy(gestureDistance = it) } }
                choice(body, s(R.string.touch_tools_swipe_timing), GestureTiming.values().toList(), control.gestureTiming) { change { c -> c.copy(gestureTiming = it) } }
                body.addView(label(s(R.string.touch_tools_gesture_timing_hint)))
                number(body, s(R.string.touch_tools_gesture_pulse), control.gesturePulseMs.toFloat(), 1f, 10000f) { change { c -> c.copy(gesturePulseMs = it.toLong()) } }
                number(body, s(R.string.touch_tools_tap_timeout), control.tapTimeoutMs.toFloat(), 1f, 10000f) { change { c -> c.copy(tapTimeoutMs = it.toLong()) } }
                number(body, s(R.string.touch_tools_tap_tolerance), control.tapSlop, 0f, 500f) { change { c -> c.copy(tapSlop = it) } }
            }
        }
    }

    private fun appearanceProperties(body: LinearLayout, control: TouchControl) {
        if (control.type != ControlType.BUTTON_GROUP || childIndex == null) {
            textField(body, s(R.string.touch_tools_control_name), control.name, false) { value -> change { it.copy(name = value) } }
        }
        if (control.type == ControlType.BUTTON || (control.type == ControlType.BUTTON_GROUP && childIndex != null)) {
            val index = childIndex
            textField(body, s(R.string.touch_tools_display_text), if (index != null) control.groupKeys[index].text else control.text, true) { value ->
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
            choice(body, s(R.string.touch_tools_shape), ControlShape.values().toList(), control.shape) {
                val r = workspace.placements[control.id] ?: control.bounds
                if (it == ControlShape.CIRCLE) workspace.setGeometry(control.id, r.copy(height = r.width))
                change { c -> c.copy(shape = it) }
            }
        }
        if (control.type != ControlType.BUTTON_GROUP || childIndex == null) {
            number(body, s(R.string.touch_tools_opacity), control.opacity * 100, 0f, 100f, true) { value -> change { it.copy(opacity = value / 100) } }
            if (control.type == ControlType.STICK && control.triggerOnly) {
                number(body, s(R.string.touch_tools_active_diameter), control.stickDiameter, 1f, 1000f) { change { c -> c.copy(stickDiameter = it) } }
            }
        }
        body.addView(label(s(R.string.touch_tools_opacity_hint)))
    }

    private fun geometryProperties(body: LinearLayout, control: TouchControl) {
        if (control.type == ControlType.BUTTON_GROUP && childIndex != null) {
            body.addView(label(s(R.string.touch_tools_child_geometry_hint))); return
        }
        val rect = workspace.placements[control.id] ?: return
        body.addView(label(s(R.string.touch_tools_safe_pixels, workspace.safe.width().roundToInt(), workspace.safe.height().roundToInt())))
        fun geometryError(rect: TouchRect): String? = if (workspace.canSetGeometry(control.id, rect)) null else s(R.string.touch_tools_geometry_error)
        number(body, s(R.string.touch_tools_x), rect.x, 0f, workspace.safe.width(), validate = { value ->
            geometryError((workspace.placements[control.id] ?: rect).copy(x = value))
        }) { value ->
            workspace.placements[control.id]?.let { r -> if (!workspace.setGeometry(control.id, r.copy(x = value))) workspace.message(s(R.string.touch_tools_position_error)) }
        }
        number(body, s(R.string.touch_tools_y), rect.y, 0f, workspace.safe.height(), validate = { value ->
            geometryError((workspace.placements[control.id] ?: rect).copy(y = value))
        }) { value ->
            workspace.placements[control.id]?.let { r -> if (!workspace.setGeometry(control.id, r.copy(y = value))) workspace.message(s(R.string.touch_tools_position_error)) }
        }
        val circle = control.type == ControlType.BUTTON && control.shape == ControlShape.CIRCLE
        number(body, if (circle) s(R.string.touch_tools_diameter) else s(R.string.touch_tools_width), rect.width, 1f, workspace.safe.width(), validate = { value ->
            val current = workspace.placements[control.id] ?: rect
            geometryError(current.copy(width = value, height = if (circle) value else current.height))
        }) { value ->
            workspace.placements[control.id]?.let { r -> if (!workspace.setGeometry(control.id, r.copy(width = value, height = if (circle) value else r.height))) workspace.message(s(R.string.touch_tools_size_error)) }
        }
        if (!circle) {
            number(body, s(R.string.touch_tools_height), rect.height, 1f, workspace.safe.height(), validate = { value ->
                geometryError((workspace.placements[control.id] ?: rect).copy(height = value))
            }) { value ->
                workspace.placements[control.id]?.let { r -> if (!workspace.setGeometry(control.id, r.copy(height = value))) workspace.message(s(R.string.touch_tools_size_error)) }
            }
        }
        val steps = row()
        listOf("←" to (-1f to 0f), "↑" to (0f to -1f), "↓" to (0f to 1f), "→" to (1f to 0f)).forEach { (label, d) ->
            steps.addView(
                button(s(R.string.touch_tools_step, label)) {
                    workspace.placements[control.id]?.let { workspace.setGeometry(control.id, it.copy(x = it.x + d.first, y = it.y + d.second)) }
                }
            )
        }
        body.addView(horizontal(steps))
    }

    private fun bindingFields(body: LinearLayout, binding: ButtonBinding, update: (ButtonBinding) -> Unit) {
        var current = binding
        val mappingButton = button(s(R.string.touch_tools_simultaneous, binding.buttons.joinToString(" + ") { buttonName(it) })) {}
        mappingButton.setOnClickListener {
            mapping(current.buttons, false) { buttons ->
                current = current.copy(buttons = buttons)
                update(current)
                mappingButton.text = s(R.string.touch_tools_simultaneous, buttons.joinToString(" + ") { buttonName(it) })
            }
        }
        body.addView(mappingButton, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        toggle(body, s(R.string.touch_tools_turbo), binding.turbo) {
            current = current.copy(turbo = it); update(current)
        }
        number(
            body,
            s(R.string.touch_tools_turbo_frequency),
            binding.frequencyHz,
            TouchValidator.MIN_FREQUENCY_HZ,
            TouchValidator.MAX_FREQUENCY_HZ,
            true
        ) {
            current = current.copy(frequencyHz = it); update(current)
        }
        body.addView(label(s(R.string.touch_tools_turbo_hint)))
    }

    private fun presetChoice(body: LinearLayout, selected: String?, update: (String) -> Unit) {
        val presets = workspace.draft.presets
        val current = presets.firstOrNull { it.id == selected }
        val refs = workspace.draft.templates.sumOf { t -> t.controls.count { it.swipePresetId == selected } }
        body.addView(label(s(R.string.touch_tools_shared_refs, refs)))
        body.addView(
            button(s(R.string.touch_tools_preset_value, current?.name ?: s(R.string.touch_tools_missing_preset))) {
                if (presets.isEmpty()) { workspace.message(s(R.string.touch_tools_no_presets)); return@button }
                var index = presets.indexOfFirst { it.id == selected }.coerceAtLeast(0)
                MaterialAlertDialogBuilder(context).setTitle(s(R.string.touch_tools_select_preset))
                    .setSingleChoiceItems(presets.map { it.name }.toTypedArray(), index) { _, i -> index = i }
                    .setNegativeButton(s(R.string.touch_tools_cancel), null).setPositiveButton(s(R.string.touch_tools_select)) { _, _ -> update(presets[index].id) }.show()
            }
        )
    }

    fun showTuning(id: String, live: Boolean, tab: Int = 0, before: TouchLibrary? = null) {
        val preset = workspace.draft.presets.firstOrNull { it.id == id } ?: return
        val snapshot = before ?: workspace.draft
        val refs = workspace.draft.templates.sumOf { t -> t.controls.count { it.swipePresetId == id } }
        val body = openPanel(
            if (live) s(R.string.touch_tools_live_title, workspace.selected?.name.orEmpty(), enumLabel(workspace.selected?.stickTarget)) else s(R.string.touch_tools_tune_title, preset.name),
            !live,
            if (live) ({ workspace.finishTest(false) }) else null,
            key = "preset:$id:$tab"
        )
        if (!live) panelBefore = snapshot
        body.addView(label(s(R.string.touch_tools_tuning_summary, preset.name, refs, s(if (live) R.string.touch_tools_live_preview else R.string.touch_tools_draft_only))))
        materialTabs(body, listOf(s(R.string.touch_tools_basic), s(R.string.touch_tools_advanced), s(R.string.touch_tools_notes)), tab) { i ->
            if (validFields()) showTuning(id, live, i, snapshot)
        }
        fun update(block: (SwipePreset) -> SwipePreset) {
            workspace.draft.presets.firstOrNull { it.id == id }?.let { workspace.updatePreset(block(it)) }
        }
        if (tab == 0) {
            number(body, s(R.string.touch_tools_sensitivity_x), preset.sensitivityX, 0.01f, 10f, true) { update { p -> p.copy(sensitivityX = it) } }
            number(body, s(R.string.touch_tools_sensitivity_y), preset.sensitivityY, 0.01f, 10f, true) { update { p -> p.copy(sensitivityY = it) } }
            toggle(body, s(R.string.touch_tools_invert_x), preset.invertX) { update { p -> p.copy(invertX = it) } }
            toggle(body, s(R.string.touch_tools_invert_y), preset.invertY) { update { p -> p.copy(invertY = it) } }
            toggle(body, s(R.string.touch_tools_swap_axes), preset.swapAxes) { update { p -> p.copy(swapAxes = it) } }
            val scales = row()
            MotionScale.values().forEach { next ->
                scales.addView(
                    button(s(R.string.touch_tools_scale_value, enumLabel(next))) {
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
            body.addView(label(s(R.string.touch_tools_scale_hint)))
            body.addView(
                SwipeCurveView(context) { workspace.draft.presets.first { it.id == id } },
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, px(130))
            )
            body.addView(label(s(R.string.touch_tools_curve_hint)))
            body.addView(
                button(s(R.string.touch_tools_reset_basic)) {
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
            expandable(body, s(R.string.touch_tools_response_curve)) { section ->
                number(section, s(R.string.touch_tools_compensation), preset.deadzone, 0f, 0.95f, true) { update { p -> p.copy(deadzone = it) } }
                number(section, s(R.string.touch_tools_exponent), preset.exponent, 0.1f, 5f, true) { update { p -> p.copy(exponent = it, curve = emptyList()) } }
                val curve = textField(section, s(R.string.touch_tools_curve_points), preset.curve.joinToString(", ") { "${it.input}:${it.output}" }, true) { value ->
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
                    setError(curve, if (valid) null else s(R.string.touch_tools_curve_error))
                }
                section.addView(
                    button(s(R.string.touch_tools_reset_response)) {
                        update { it.copy(deadzone = 0.15f, exponent = 1f, curve = emptyList()) }
                        showTuning(id, live, tab, snapshot)
                    }
                )
            }
            expandable(body, s(R.string.touch_tools_timing_saturation)) { section ->
                val dpScale = preset.scale == MotionScale.DP
                number(
                    section,
                    if (dpScale) s(R.string.touch_tools_speed_dp) else s(R.string.touch_tools_speed_edge),
                    preset.maximumSpeed,
                    if (dpScale) 0.01f else 0.001f,
                    if (dpScale) 10000f else 20f,
                    true
                ) {
                    update { p -> p.copy(maximumSpeed = it) }
                }
                number(section, s(R.string.touch_tools_smoothing), preset.smoothingMs, 0f, 10000f) { update { p -> p.copy(smoothingMs = it) } }
                number(section, s(R.string.touch_tools_stop_timeout), preset.stopTimeoutMs.toFloat(), 1f, 10000f) { update { p -> p.copy(stopTimeoutMs = it.toLong()) } }
                number(section, s(R.string.touch_tools_accumulation), preset.maxAccumulationMs.toFloat(), 1f, 1000f) { update { p -> p.copy(maxAccumulationMs = it.toLong()) } }
                section.addView(label(s(R.string.touch_tools_release_hint)))
                section.addView(
                    button(s(R.string.touch_tools_reset_timing)) {
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
            textField(body, s(R.string.touch_tools_preset_name), preset.name, false) { name -> update { it.copy(name = name) } }
            textField(body, s(R.string.touch_tools_calibration_notes), preset.notes, true, multiline = true) { notes -> update { it.copy(notes = notes) } }
            body.addView(label(s(R.string.touch_tools_calibration_hint)))
        }
        if (live) {
            body.addView(button(s(R.string.touch_tools_return_keep)) { if (validFields()) workspace.finishTest(false) })
            body.addView(button(s(R.string.touch_tools_undo_debug_session)) { workspace.finishTest(true) })
            body.addView(button(s(R.string.touch_tools_hide_restore)) { workspace.hideTools() })
        } else {
            body.addView(button(s(R.string.touch_tools_done)) { if (validFields()) completePanel() })
            body.addView(button(s(R.string.touch_tools_cancel_tuning)) { cancelPanel() })
            body.addView(
                button(if (workspace.gameAvailable) s(R.string.touch_tools_fullscreen_game_test) else s(R.string.touch_tools_fullscreen_local_test)) {
                    if (validFields()) { completePanel(); workspace.startTest(false) }
                }
            )
        }
    }

    private fun gestureTarget(current: GestureBinding?, update: (GestureBinding?) -> Unit) {
        val options = arrayOf(s(R.string.touch_tools_controller_button), s(R.string.touch_tools_left_direction), s(R.string.touch_tools_right_direction), s(R.string.touch_tools_unassigned))
        MaterialAlertDialogBuilder(context).setTitle(s(R.string.touch_tools_gesture_output))
            .setItems(options) { _, index ->
                when (index) {
                    0 -> mapping(current?.button?.let { setOf(it) } ?: emptySet(), true) { update(GestureBinding(button = it.first())) }
                    1, 2 -> {
                        val body = column()
                        var direction = current?.direction?.takeIf { it != GestureDirection.TAP } ?: GestureDirection.UP
                        body.addView(label(s(R.string.touch_tools_output_hint)))
                        compass(body, true, false, direction) { value -> direction = value }
                        MaterialAlertDialogBuilder(context).setTitle(if (index == 1) s(R.string.touch_tools_left_output) else s(R.string.touch_tools_right_output))
                            .setView(body).setNegativeButton(s(R.string.touch_tools_cancel), null)
                            .setPositiveButton(s(R.string.touch_tools_use_direction)) { _, _ ->
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
            }.setNegativeButton(s(R.string.touch_tools_cancel), null).show()
    }

    private fun mapping(initial: Set<Int>, single: Boolean, allowEmpty: Boolean = false, update: (Set<Int>) -> Unit) {
        val values = initial.filter { it in TouchValidator.buttonTargets }.toMutableSet()
        val body = column()
        body.addView(label(if (single) s(R.string.touch_tools_choose_one) else s(R.string.touch_tools_combo_hint)))
        val summary = ChipGroup(context).apply { chipSpacingHorizontal = px(4); chipSpacingVertical = px(4) }
        body.addView(summary)
        val tabs = ChipGroup(context).apply { isSingleSelection = true; isSelectionRequired = true }
        val grid = ChipGroup(context).apply { chipSpacingHorizontal = px(8); chipSpacingVertical = px(8) }
        body.addView(horizontal(tabs))
        body.addView(grid)
        val categories = listOf(
            s(R.string.touch_tools_face) to listOf(0, 1, 2, 3),
            s(R.string.touch_tools_shoulders) to listOf(6, 7, 8, 9),
            s(R.string.touch_tools_system) to listOf(4, 5, 10, 11, 18, 19),
            s(R.string.touch_tools_directions) to listOf(13, 14, 15, 12)
        )
        var category = 0
        var rebuilding: Boolean
        fun refresh() {
            rebuilding = true
            summary.removeAllViews()
            if (values.isEmpty()) summary.addView(label(if (allowEmpty) s(R.string.touch_tools_no_auxiliary) else s(R.string.touch_tools_select_output)))
            for (id in values.sorted()) summary.addView(
                Chip(context).apply {
                    text = buttonName(id)
                    isCloseIconVisible = true
                    minHeight = px(48)
                    setEnsureMinTouchTargetSize(true)
                    contentDescription = s(R.string.touch_tools_remove_output, buttonName(id))
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
                    contentDescription = s(R.string.touch_tools_map_output, buttonName(id))
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
        val dialog = MaterialAlertDialogBuilder(context).setTitle(if (single) s(R.string.touch_tools_button_output) else s(R.string.touch_tools_button_combination))
            .setView(scroll).setNegativeButton(s(R.string.touch_tools_cancel), null).setNeutralButton(s(R.string.touch_tools_clear), null)
            .setPositiveButton(s(R.string.touch_tools_done), null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (values.isEmpty() && !allowEmpty) {
                    workspace.message(s(R.string.touch_tools_select_button))
                } else {
                    val apply = { update(values.toSet()); dialog.dismiss() }
                    if (values.any { it == 18 || it == 19 }) {
                        MaterialAlertDialogBuilder(context)
                            .setTitle(s(R.string.touch_tools_system_mapping)).setMessage(s(R.string.touch_tools_system_warning))
                            .setPositiveButton(s(R.string.touch_tools_use_mapping)) { _, _ -> apply() }.setNegativeButton(s(R.string.touch_tools_cancel), null).show()
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
        val arrows = listOf("↖", "↑", "↗", "←", s(R.string.touch_tools_tap), "→", "↙", "↓", "↘")
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
                    contentDescription = s(R.string.touch_tools_label_value, enumLabel(direction), summary?.invoke(direction).orEmpty())
                    maxLines = 4
                }
                buttons[direction] = button
                row.addView(button, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            body.addView(row)
        }
    }

    private fun gestureSummary(value: GestureBinding?): String = when {
        value == null -> s(R.string.touch_tools_unassigned)
        value.button != null -> buttonName(value.button)
        else -> s(R.string.touch_tools_stick_direction, enumLabel(value.stick), enumLabel(value.direction))
    }

    private fun change(block: (TouchControl) -> TouchControl): Boolean = workspace.selected?.let { workspace.updateControl(block(it)) } ?: false

    private fun <T> choice(body: LinearLayout, label: String, values: List<T>, selected: T, update: (T) -> Unit) {
        val item = row().apply { minimumHeight = px(56); isClickable = true; isFocusable = true }
        item.addView(label(label), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val value = label(enumLabel(selected)).apply {
            gravity = Gravity.END
            setTextColor(themeColor(com.google.android.material.R.attr.colorSecondary))
        }
        item.addView(value, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        item.contentDescription = s(R.string.touch_tools_label_value, label, enumLabel(selected))
        item.setOnClickListener {
            workspace.runtime.cancelAll()
            hasModalDialog = true
            val dialog = MaterialAlertDialogBuilder(context).setTitle(label)
                .setSingleChoiceItems(values.map { enumLabel(it) }.toTypedArray(), values.indexOf(selected)) { dialog, which ->
                    update(values[which])
                    value.text = enumLabel(values[which])
                    item.contentDescription = s(R.string.touch_tools_label_value, label, enumLabel(values[which]))
                    dialog.dismiss()
                }.setNegativeButton(s(R.string.touch_tools_cancel), null).create()
            dialog.setOnDismissListener { hasModalDialog = false }
            dialog.show()
        }
        body.addView(item, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun materialTabs(body: LinearLayout, labels: List<String>, selected: Int, change: (Int) -> Unit) {
        val tabs = TabLayout(context).apply {
            tabMode = TabLayout.MODE_FIXED
            setBackgroundColor(Color.TRANSPARENT)
            setTabTextColors(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant), themeColor(com.google.android.material.R.attr.colorPrimary))
            setSelectedTabIndicatorColor(themeColor(com.google.android.material.R.attr.colorPrimary))
        }
        labels.forEachIndexed { index, text -> tabs.addTab(tabs.newTab().setText(text), index == selected) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { change(tab.position) }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        body.addView(tabs, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, px(48)))
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
                setError(input, s(R.string.touch_tools_range_error, format(min), format(max)))
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
            setError(input, if (!allowEmpty && text.isBlank()) s(R.string.touch_tools_not_empty) else null)
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
        workspace.message(s(R.string.touch_tools_fix_fields))
        return false
    }

    private fun expandable(body: LinearLayout, title: String, build: (LinearLayout) -> Unit) {
        val section = column().apply { visibility = GONE }
        body.addView(button(title) { section.visibility = if (section.visibility == VISIBLE) GONE else VISIBLE })
        build(section)
        body.addView(section)
    }

    private fun draggable(handle: View, target: View, clickOnTap: Boolean = false) {
        var startX = 0f
        var startY = 0f
        var originalX = 0f
        var originalY = 0f
        var pointer = -1
        handle.contentDescription = s(R.string.touch_tools_drag)
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
                    if (clickOnTap && event.actionMasked == MotionEvent.ACTION_UP &&
                        abs(event.rawX - startX) < px(8) && abs(event.rawY - startY) < px(8)
                    ) {
                        handle.performClick()
                    }
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
        0
    ).apply {
        this.text = text
        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        strokeWidth = 0
        isAllCaps = false
        minHeight = px(48)
        minimumHeight = px(48)
        minWidth = 0
        minimumWidth = 0
        insetTop = 0
        insetBottom = 0
        setPadding(px(12), px(4), px(12), px(4))
        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
        setOnClickListener { click() }
    }

    private fun primaryButton(text: String, click: () -> Unit): MaterialButton = MaterialButton(context).apply {
        this.text = text
        contentDescription = text
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = px(48)
        minimumHeight = px(48)
        insetTop = 0
        insetBottom = 0
        cornerRadius = px(24)
        backgroundTintList = ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorPrimary))
        setTextColor(themeColor(com.google.android.material.R.attr.colorOnPrimary))
        setPadding(px(20), px(4), px(20), px(4))
        setOnClickListener { click() }
    }

    private fun iconButton(drawable: Int, description: String, click: (View) -> Unit): MaterialButton =
        button("") {}.apply {
            icon = ContextCompat.getDrawable(context, drawable)
            iconSize = px(24)
            iconPadding = 0
            iconTint = ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorOnSurface))
            contentDescription = description
            tooltipText = description
            setPadding(px(12), 0, px(12), 0)
            layoutParams = LinearLayout.LayoutParams(px(48), px(48))
            setOnClickListener { click(this) }
        }

    private fun label(text: String, size: Float = 14f): TextView = TextView(context).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        this.text = text
        textSize = size
        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
        setPadding(0, px(4), 0, px(4))
    }

    private fun s(id: Int, vararg args: Any): String = context.getString(id, *args)
    private fun themeColor(attr: Int): Int = MaterialColors.getColor(this, attr)

    private fun enumLabel(value: Any?): String = when (value) {
        ControlType.BUTTON -> s(R.string.touch_tools_enum_controltype_button)
        ControlType.BUTTON_GROUP -> s(R.string.touch_tools_enum_controltype_button_group)
        ControlType.STICK -> s(R.string.touch_tools_enum_controltype_stick)
        ControlType.SWIPE -> s(R.string.touch_tools_enum_controltype_swipe)
        ControlType.GESTURE -> s(R.string.touch_tools_enum_controltype_gesture)
        ControlShape.CIRCLE -> s(R.string.touch_tools_enum_controlshape_circle)
        ControlShape.RECTANGLE -> s(R.string.touch_tools_enum_controlshape_rectangle)
        GroupStyle.FACE -> s(R.string.touch_tools_enum_groupstyle_face)
        GroupStyle.DPAD -> s(R.string.touch_tools_enum_groupstyle_dpad)
        StickTarget.LEFT -> s(R.string.touch_tools_enum_sticktarget_left)
        StickTarget.RIGHT -> s(R.string.touch_tools_enum_sticktarget_right)
        AuxiliaryMode.PUSH -> s(R.string.touch_tools_enum_auxiliarymode_push)
        AuxiliaryMode.DOUBLE_TAP -> s(R.string.touch_tools_enum_auxiliarymode_double_tap)
        GestureTiming.IMMEDIATE -> s(R.string.touch_tools_enum_gesturetiming_immediate)
        GestureTiming.RELEASE -> s(R.string.touch_tools_enum_gesturetiming_release)
        MotionScale.DP -> s(R.string.touch_tools_enum_motionscale_dp)
        MotionScale.SHORT_EDGE -> s(R.string.touch_tools_enum_motionscale_short_edge)
        GestureDirection.TAP -> s(R.string.touch_tools_enum_gesturedirection_tap)
        GestureDirection.UP -> s(R.string.touch_tools_enum_gesturedirection_up)
        GestureDirection.UP_RIGHT -> s(R.string.touch_tools_enum_gesturedirection_up_right)
        GestureDirection.RIGHT -> s(R.string.touch_tools_enum_gesturedirection_right)
        GestureDirection.DOWN_RIGHT -> s(R.string.touch_tools_enum_gesturedirection_down_right)
        GestureDirection.DOWN -> s(R.string.touch_tools_enum_gesturedirection_down)
        GestureDirection.DOWN_LEFT -> s(R.string.touch_tools_enum_gesturedirection_down_left)
        GestureDirection.LEFT -> s(R.string.touch_tools_enum_gesturedirection_left)
        GestureDirection.UP_LEFT -> s(R.string.touch_tools_enum_gesturedirection_up_left)
        else -> value?.toString().orEmpty()
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(px(8), px(8), px(8), px(8)) }
    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun horizontal(view: View) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(view)
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }
    private fun card() = MaterialCardView(context).apply {
        radius = px(16f)
        cardElevation = 0f
        strokeWidth = px(1)
        strokeColor = themeColor(com.google.android.material.R.attr.colorOutline)
        setCardBackgroundColor(surfaceColor())
        isClickable = true
    }
    private fun surfaceColor(): Int {
        val color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
        return (color and 0x00ffffff) or ((panelAlpha * 255).toInt() shl 24)
    }
    private fun toolbarWidth() = min(px(680), max(px(200), workspace.width - px(24)))
    private fun panelWidth() = min(px(340), max(px(180), workspace.width - px(24)))
    private fun panelHeight() = min(px(480), (workspace.height - px(88)).coerceAtLeast(px(112)))
    private fun px(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun px(value: Float) = value * resources.displayMetrics.density
    private fun format(value: Float) = if (abs(value - value.roundToInt()) < 0.0001f) value.roundToInt().toString() else "%.3f".format(java.util.Locale.ROOT, value)
    private fun buttonName(id: Int) = mapOf(
        0 to "A", 1 to "B", 2 to "X", 3 to "Y", 4 to "L3", 5 to "R3", 6 to "L", 7 to "R",
        8 to "ZL", 9 to "ZR", 10 to "+", 11 to "−", 12 to s(R.string.touch_tools_dleft), 13 to s(R.string.touch_tools_dup), 14 to s(R.string.touch_tools_dright), 15 to s(R.string.touch_tools_ddown), 18 to s(R.string.touch_tools_home), 19 to s(R.string.touch_tools_capture)
    )[id] ?: id.toString()
}
