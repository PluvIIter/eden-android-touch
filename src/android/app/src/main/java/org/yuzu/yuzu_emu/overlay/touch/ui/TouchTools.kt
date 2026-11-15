// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout
import org.yuzu.yuzu_emu.R
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
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
import org.yuzu.yuzu_emu.overlay.touch.model.ButtonAction
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
    private val actions = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private data class ToolWindow(
        var snapshot: TouchWindowSnapshot,
        val card: MaterialCardView,
        val body: LinearLayout,
        val footer: LinearLayout,
        val scroll: ScrollView,
        val invalid: MutableSet<TextInputLayout> = mutableSetOf()
    )
    private val windows = linkedMapOf<String, ToolWindow>()
    private val sizes = mutableMapOf<String, TouchToolSize>()
    private var current: ToolWindow? = null
    private val panel get() = current?.card
    private val panelFooter get() = current?.footer
    private val panelScroll get() = current?.scroll
    private var propertyTab: Int
        get() = current?.snapshot?.tab ?: 0
        set(value) { current?.let { it.snapshot = it.snapshot.copy(tab = value) } }
    private var childIndex: Int?
        get() = current?.snapshot?.childIndex
        set(value) { current?.let { it.snapshot = it.snapshot.copy(childIndex = value) } }
    private var propertyId: String? = null
    private var draggingTools = false
    private var toolbarPosition: TouchToolPosition? = null
    private var toolbarSignature: String? = null
    private var toolbarCollapsed = false
    private var relayoutQueued = false
    private var imeWindow: ToolWindow? = null
    private var imeBaseHeight = 0
    private val invalidFields get() = current?.invalid ?: mutableSetOf<TextInputLayout>()
    val hasPanel: Boolean get() = windows.isNotEmpty()
    var hasModalDialog: Boolean = false
        private set

    fun exportState(): TouchToolState = TouchToolState(
        toolbarPosition ?: positionOf(toolbar),
        panelAlpha,
        windows.values.map {
            it.snapshot.copy(
                position = positionOf(it.card),
                scroll = it.scroll.scrollY,
                width = it.card.width / resources.displayMetrics.density,
                height = it.card.height / resources.displayMetrics.density
            )
        },
        sizes.toMap()
    ).normalized()

    fun restoreState(value: TouchToolState) {
        val state = value.normalized()
        closeAll()
        sizes.clear()
        sizes.putAll(state.sizes)
        state.windows.forEach { saved ->
            if (saved.width > 0f && saved.height > 0f) sizes[saved.key] = TouchToolSize(saved.width, saved.height)
        }
        toolbarPosition = state.toolbar
        panelAlpha = state.opacity
        toolbar.setCardBackgroundColor(surfaceColor())
        post {
            state.windows.forEach { saved ->
                when {
                    saved.controlId != null -> showPropertiesFor(saved.controlId, saved)
                    saved.presetId != null -> showTuning(saved.presetId, false, saved.tab, restored = saved)
                    saved.key == "controls" -> showControls()
                    saved.key == "add" -> showAdd()
                    saved.key == "appearance" -> showAppearance()
                }
                windows[saved.key]?.let { window ->
                    window.snapshot = saved
                    window.scroll.post { window.scroll.scrollTo(0, saved.scroll); applyPosition(window.card, saved.position) }
                }
            }
            toolbarPosition?.let { applyPosition(toolbar, it) }
        }
    }

    private inline fun <T> within(window: ToolWindow?, action: () -> T): T {
        val previous = current
        current = window
        return try { action() } finally { current = previous?.takeIf { windows[it.snapshot.key] === it } }
    }

    fun onControlSelected(id: String) {
        if (workspace.isEditing) showPropertiesFor(id)
    }

    /** After placing or duplicating a control, leave the add flow and edit the new control. */
    fun onControlPlaced(id: String) {
        closeAll()
        showPropertiesFor(id)
    }

    fun validateAndCompleteAll(): Boolean {
        val invalid = windows.values.firstOrNull { it.invalid.isNotEmpty() }
        if (invalid != null) {
            invalid.card.bringToFront()
            invalid.invalid.first().requestFocus()
            workspace.message(s(R.string.touch_tools_fix_fields))
            return false
        }
        windows.values.forEach { window ->
            workspace.checkpointWindow(
                window.snapshot,
                capture(window.snapshot.key, window.snapshot.controlId, window.snapshot.presetId)
            )
        }
        windows.values.forEach { window ->
            window.snapshot = capture(window.snapshot.key, window.snapshot.controlId, window.snapshot.presetId)
                .copy(position = positionOf(window.card), tab = window.snapshot.tab, childIndex = window.snapshot.childIndex)
        }
        return true
    }

    fun closeAll(cancel: Boolean = false) {
        windows.values.toList().forEach { closeWindow(it, cancel, force = true) }
    }

    fun closeTopWindow(cancel: Boolean): Boolean {
        val window = (childCount - 1 downTo 0).firstNotNullOfOrNull { index ->
            windows.values.firstOrNull { it.card === getChildAt(index) }
        } ?: return false
        closeWindow(window, cancel)
        return true
    }

    private fun closeWindow(window: ToolWindow, cancel: Boolean, force: Boolean = false) {
        if (!cancel && !force && window.invalid.isNotEmpty()) {
            window.invalid.first().requestFocus()
            workspace.message(s(R.string.touch_tools_fix_fields))
            return
        }
        if (cancel) {
            workspace.replaceDraft(window.snapshot.restore(workspace.draft, workspace.templateId))
        } else if (!force) {
            workspace.checkpointWindow(
                window.snapshot,
                capture(window.snapshot.key, window.snapshot.controlId, window.snapshot.presetId)
            )
        }
        removeView(window.card)
        windows.remove(window.snapshot.key)
        if (current === window) current = null
    }

    fun rebaseGeometry(id: String) {
        windows.values.filter { it.snapshot.controlId == id }.toList().forEach { window ->
            window.snapshot = window.snapshot.copy(beforePlacements = capture(window.snapshot.key, id).beforePlacements)
            if (window.snapshot.tab == 2) within(window) { showProperties() }
        }
    }

    fun rebaseControlFlags(id: String) {
        val control = workspace.template.controls.firstOrNull { it.id == id } ?: return
        windows.values.filter { it.snapshot.controlId == id }.forEach { window ->
            window.snapshot.beforeControl?.let { old ->
                window.snapshot = window.snapshot.copy(
                    beforeControl = old.copy(enabled = control.enabled, locked = control.locked)
                )
            }
        }
    }

    fun historyApplied(change: TouchWindowChange?, undo: Boolean) {
        windows.values.toList().forEach { window ->
            if (change != null) {
                val baseline = window.snapshot.restore(workspace.draft, workspace.templateId)
                val patched = if (undo) change.undo(baseline) else change.redo(baseline)
                window.snapshot = capture(window.snapshot.key, window.snapshot.controlId, window.snapshot.presetId, patched)
                    .copy(position = positionOf(window.card), tab = window.snapshot.tab, childIndex = window.snapshot.childIndex)
            }
            if (window.snapshot.controlId != null) within(window) { showProperties() }
        }
    }

    private fun capture(key: String, controlId: String? = null, presetId: String? = null, library: TouchLibrary = workspace.draft): TouchWindowSnapshot {
        val template = library.templates.first { it.id == workspace.templateId }
        val control = template.controls.firstOrNull { it.id == controlId }
        return TouchWindowSnapshot(
            key = key,
            width = sizes[key]?.width ?: 0f,
            height = sizes[key]?.height ?: 0f,
            controlId = controlId,
            presetId = presetId,
            beforeControl = control,
            beforePlacements = control?.let { c ->
                template.screens.associate { screen ->
                    screen.id to screen.placements[c.id]
                }
            } ?: emptyMap(),
            beforePreset = library.presets.firstOrNull { it.id == presetId }
        )
    }

    fun onImeInsets(bottom: Int) {
        val focused = findFocus()
        val focusedWindow = focused?.let { view ->
            windows.values.firstOrNull { view.isDescendantOf(it.card) }
        }
        if (bottom > 0) {
            val window = focusedWindow ?: current ?: return
            imeWindow = window
            imeBaseHeight = if (window.snapshot.height > 0f) px(window.snapshot.height).toInt() else panelHeight()
            val available = (height - bottom - window.card.y.toInt() - px(8)).coerceAtLeast(px(128))
            window.card.layoutParams = (window.card.layoutParams as LayoutParams).apply {
                height = min(imeBaseHeight, available)
            }
            focused?.post { focused.requestRectangleOnScreen(android.graphics.Rect(0, 0, focused.width, focused.height), false) }
        } else {
            val window = imeWindow ?: focusedWindow ?: current ?: return
            val base = imeBaseHeight.takeIf { it > 0 } ?: if (window.snapshot.height > 0f) {
                px(window.snapshot.height).toInt()
            } else {
                panelHeight()
            }
            window.card.layoutParams = (window.card.layoutParams as LayoutParams).apply { height = base }
            imeWindow = null
            imeBaseHeight = 0
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

    init {
        clipChildren = false
        isMotionEventSplittingEnabled = true
        toolbar.radius = px(16f)
        toolbarContent.setPadding(px(4), 0, px(4), 0)
        toolbarContent.addView(actions)
        toolbar.addView(toolbarContent)
        toolbar.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                windows.values.forEach { window ->
                    window.card.layoutParams = (window.card.layoutParams as LayoutParams).apply { height = panelHeight() }
                }
            }
        }
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
        refreshToolbar()
        windows.values.forEach { window ->
            val width = if (window.snapshot.width > 0f) px(window.snapshot.width).toInt() else panelWidth()
            val height = if (window.snapshot.height > 0f) px(window.snapshot.height).toInt() else panelHeight()
            window.card.layoutParams = (window.card.layoutParams as LayoutParams).apply {
                this.width = width.coerceIn(px(200), max(px(200), w))
                this.height = height.coerceIn(px(160), max(px(160), h))
            }
        }
        post {
            applyPosition(toolbar, toolbarPosition ?: positionOf(toolbar))
            windows.values.forEach { window -> applyPosition(window.card, window.snapshot.position) }
        }
    }

    fun refreshToolbar() {
        val headingText = workspace.standalonePresetId?.let { id ->
            workspace.draft.presets.firstOrNull { it.id == id }?.name
        } ?: workspace.template.name
        val signature = "$headingText|${workspace.testing}|${workspace.gameAvailable}|${workspace.snapToGrid}|${toolbarCollapsed}|${toolbarWidth()}"
        if (signature == toolbarSignature && toolbar.childCount > 0) {
            relayout()
            return
        }
        toolbarSignature = signature
        val previousWindow = current
        current = null
        toolbar.layoutParams?.let { params ->
            if (params.width != toolbarWidth()) { params.width = toolbarWidth(); toolbar.layoutParams = params }
        }
        actions.removeAllViews()
        val head = row()
        val collapseLabel = s(if (toolbarCollapsed) R.string.touch_tools_expand else R.string.touch_tools_collapse)
        val handle = iconButton(
            if (toolbarCollapsed) R.drawable.touch_tools_more else R.drawable.touch_tools_fold,
            collapseLabel
        ) {
            toolbarCollapsed = !toolbarCollapsed
            refreshToolbar()
        }
        draggable(handle, toolbar, clickOnTap = true)
        head.addView(handle)
        val heading = label(headingText, 13f)
        draggable(heading, toolbar)
        head.addView(heading, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        head.addView(
            label(s(if (workspace.testing) R.string.touch_tools_test_draft else R.string.touch_tools_draft), 10f).apply {
                visibility = if (toolbarCollapsed) GONE else VISIBLE
            }
        )
        head.addView(
            iconButton(
                R.drawable.ic_exit,
                s(if (workspace.gameAvailable) R.string.touch_tools_exit_game else R.string.touch_tools_exit_edit)
            ) { workspace.requestExit() }
        )
        actions.addView(head)
        if (toolbarCollapsed) {
            current = previousWindow
            relayout()
            return
        }
        val buttons = buildList {
            add(button(s(R.string.touch_tools_add)) { showAdd() })
            add(button(s(R.string.touch_tools_undo)) { if (validateAndCompleteAll()) workspace.undo() })
            add(button(s(R.string.touch_tools_redo)) { if (validateAndCompleteAll()) workspace.redo() })
            add(button(s(R.string.touch_tools_controls)) { showControls() })
            // Whole-layout testing resumes emulation and is only meaningful for the offline editor.
            // In-game parameter work uses single-control live debug from a swipe area instead.
            if (!workspace.gameAvailable) {
                add(
                    button(s(if (workspace.testing) R.string.touch_tools_return_edit else R.string.touch_tools_test)) {
                        if (workspace.testing) workspace.finishTest(false)
                        else if (validateAndCompleteAll()) workspace.startTest(false)
                    }
                )
            }
            add(
                primaryButton(s(R.string.touch_tools_save)) {
                    if (validateAndCompleteAll()) {
                        if (workspace.testing) workspace.finishTest(false)
                        workspace.save()
                    }
                }.apply {
                    icon = ContextCompat.getDrawable(context, R.drawable.touch_tools_save)
                    iconSize = px(18)
                    iconPadding = px(4)
                    iconTint = ColorStateList.valueOf(readable(themeColor(com.google.android.material.R.attr.colorOnPrimary), themeColor(com.google.android.material.R.attr.colorPrimary)))
                }
            )
            add(button(s(R.string.touch_tools_toolbar_base)) { if (validateAndCompleteAll()) workspace.setCurrentScreenAsBase() }.apply { contentDescription = s(R.string.touch_tools_set_base) })
            add(button(s(R.string.touch_tools_appearance)) { showAppearance() }.apply { contentDescription = s(R.string.touch_tools_appearance) })
            add(
                button(s(if (workspace.snapToGrid) R.string.touch_tools_grid_on else R.string.touch_tools_grid_off)) {
                    workspace.toggleSnapToGrid()
                }.apply {
                    contentDescription = s(R.string.touch_tools_grid_toggle)
                }
            )
        }
        val columns = when {
            toolbarWidth() >= px(520) -> 4
            toolbarWidth() >= px(240) -> 3
            else -> 2
        }
        buttons.forEach { action -> action.maxLines = 2 }
        buttons.chunked(columns).forEach { group ->
            val line = actionRow()
            group.forEachIndexed { index, button ->
                val params = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                if (index > 0) params.marginStart = px(4)
                line.addView(button, params)
            }
            actions.addView(line, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(4)
            })
        }
        current = previousWindow
        toolbar.requestLayout()
        requestLayout()
    }

    /**
     * The first size pass builds the toolbar before the workspace has a width, so the card can be
     * measured with an empty body. Ask for one more pass once the real size is known.
     */
    fun relayout() {
        toolbar.layoutParams?.let { params ->
            if (params.width != toolbarWidth()) { params.width = toolbarWidth(); toolbar.layoutParams = params }
        }
        toolbar.requestLayout()
        requestLayout()
        invalidate()
        toolbar.post {
            toolbar.x = toolbar.x.coerceIn(0f, max(0f, width - toolbar.width.toFloat()))
            toolbar.y = toolbar.y.coerceIn(0f, max(0f, height - toolbar.height.toFloat()))
        }
    }

    /** Coalesce geometry-driven relayout requests into a single next-frame pass. */
    fun scheduleRelayout() {
        if (relayoutQueued) return
        relayoutQueued = true
        post {
            relayoutQueued = false
            relayout()
        }
    }

    fun closePanel() = closeAll()
    fun cancelPanel() { current?.let { closeWindow(it, true) } ?: closeTopWindow(true) }
    private fun completePanel() { current?.let { closeWindow(it, false) } }

    private fun openPanel(
        name: String,
        key: String = "utility",
        restored: TouchWindowSnapshot? = null,
        avoid: TouchRect? = null
    ): LinearLayout? {
        // A single tool window is shown at a time; finish the previous one before replacing it.
        val others = windows.keys.filter { it != key }
        for (other in others) {
            val window = windows[other] ?: continue
            if (window.invalid.isNotEmpty()) {
                window.card.bringToFront()
                window.invalid.first().requestFocus()
                workspace.message(s(R.string.touch_tools_fix_fields))
                return null
            }
        }
        for (other in others) windows[other]?.let { closeWindow(it, false) }
        val old = windows[key]
        val snapshot = restored ?: old?.let { it.snapshot.copy(position = positionOf(it.card), scroll = it.scroll.scrollY) } ?: capture(key)
        if (old != null) removeView(old.card)
        val surface = card()
        val content = column()
        val header = row()
        header.addView(
            label(name, 14f).apply {
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary))
            },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        val body = column()
        val scroll = ScrollView(context).apply { isFillViewport = false; addView(body) }
        val footer = actionRow()
        val window = ToolWindow(snapshot, surface, body, footer, scroll)
        windows[key] = window
        current = window
        val resizeRow = row().apply { gravity = Gravity.END }
        val grip = ImageView(context).apply {
            setImageResource(R.drawable.touch_tools_drag)
            imageTintList = ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            contentDescription = s(R.string.touch_tools_resize)
            alpha = 0.7f
        }
        resizeRow.addView(grip, LinearLayout.LayoutParams(px(20), px(20)))
        header.addView(
            MaterialButton(
                androidx.appcompat.view.ContextThemeWrapper(context, R.style.TouchButton_Theme_Icon),
                null,
                com.google.android.material.R.attr.materialButtonStyle
            ).apply {
                text = "×"
                contentDescription = s(R.string.touch_tools_close)
                minWidth = px(36)
                setOnClickListener { within(window) { closeWindow(window, false) } }
            }
        )
        draggable(header, surface)
        content.addView(header)
        content.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(footer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(resizeRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        surface.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val cardWidth = (if (snapshot.width > 0f) px(snapshot.width).toInt() else panelWidth())
            .coerceIn(px(200), max(px(200), width))
        val cardHeight = (if (snapshot.height > 0f) px(snapshot.height).toInt() else panelHeight())
            .coerceIn(px(160), max(px(160), height))
        addView(
            surface,
            LayoutParams(cardWidth, cardHeight).apply {
                leftMargin = if (width > height) max(0, width - cardWidth - px(12)) else min(px(8 + windows.size * 12), max(0, width - cardWidth))
                topMargin = if (width > height) px(16) else min(toolbar.bottom + px(8), max(0, height - cardHeight))
            }
        )
        resizable(grip, surface, window)
        surface.post {
            val hadPosition = old != null || (restored != null && restored.position != TouchToolPosition())
            when {
                hadPosition -> applyPosition(surface, snapshot.position)
                avoid != null -> placeAvoiding(surface, avoid)
                snapshot.position != TouchToolPosition() -> applyPosition(surface, snapshot.position)
                else -> {
                    surface.x = if (width > height) max(0f, width - surface.width - px(12f)) else min(px(8f + windows.size * 8), max(0f, width - surface.width.toFloat()))
                    surface.y = if (width > height) px(16f) else min(toolbar.y + toolbar.height + px(8), max(0f, height - surface.height - px(8f)))
                    window.snapshot = window.snapshot.copy(position = positionOf(surface))
                }
            }
            scroll.scrollTo(0, snapshot.scroll)
        }
        return body
    }

    /** Put a new panel beside the control instead of on top of the area the user just touched. */
    private fun placeAvoiding(surface: View, avoid: TouchRect) {
        val w = surface.width.toFloat()
        val h = surface.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val margin = px(12f)
        val maxX = max(margin, width - w - margin)
        val maxY = max(margin, height - h - margin)
        fun clamp(x: Float, y: Float): Pair<Float, Float> =
            x.coerceIn(margin, maxX) to y.coerceIn(margin, maxY)
        // Keep the properties card away from the selected control instead of hugging its edge.
        // Opposite corners and edge midpoints give the panel the clearest work area.
        val candidates = listOf(
            clamp(margin, margin),
            clamp(maxX, margin),
            clamp(margin, maxY),
            clamp(maxX, maxY),
            clamp((margin + maxX) / 2f, margin),
            clamp((margin + maxX) / 2f, maxY),
            clamp(margin, (margin + maxY) / 2f),
            clamp(maxX, (margin + maxY) / 2f)
        )
        val controlX = avoid.x + avoid.width / 2f
        val controlY = avoid.y + avoid.height / 2f
        val (x, y) = candidates.maxByOrNull { (cx, cy) ->
            val dx = cx + w / 2f - controlX
            val dy = cy + h / 2f - controlY
            dx * dx + dy * dy
        } ?: (margin to margin)
        surface.x = x
        surface.y = y
        windows.values.firstOrNull { it.card === surface }?.let {
            it.snapshot = it.snapshot.copy(position = positionOf(surface))
        }
    }

    /** After a drag, move the open properties card away if the control slid underneath it. */
    fun avoidSelectedControl(id: String) {
        val window = windows["control:$id"] ?: return
        val rect = workspace.placements[id] ?: return
        val avoid = TouchRect(rect.x + workspace.safe.left, rect.y + workspace.safe.top, rect.width, rect.height)
        val surface = window.card
        if (overlapArea(surface.x, surface.y, surface.width.toFloat(), surface.height.toFloat(), avoid) <= 0f) return
        placeAvoiding(surface, avoid)
    }

    private fun overlapArea(x: Float, y: Float, w: Float, h: Float, rect: TouchRect): Float {
        val left = max(x, rect.x)
        val top = max(y, rect.y)
        val right = min(x + w, rect.right)
        val bottom = min(y + h, rect.bottom)
        return max(0f, right - left) * max(0f, bottom - top)
    }

    private fun showAppearance() {
        val body = openPanel(s(R.string.touch_tools_appearance), key = "appearance") ?: return
        val template = workspace.template
        val commonColor = template.globalColor
            ?: template.controls.mapNotNull { it.color }.distinct().singleOrNull()
        body.addView(button(s(R.string.touch_tools_global_color, colorLabel(commonColor))) {
            chooseColor(commonColor) { color ->
                workspace.applyAllColors(color)
                showAppearance()
            }
        })
        body.addView(button(s(R.string.touch_tools_global_color_default)) {
            workspace.applyAllColors(null)
            showAppearance()
        })
        number(body, s(R.string.touch_tools_global_opacity), template.globalOpacity * 100f, 0f, 100f, true) {
            workspace.updateGlobalOpacity(it / 100f)
        }
        body.addView(button(s(R.string.touch_tools_override_opacity)) {
            workspace.overrideGlobalOpacity()
            showAppearance()
        })
        body.addView(label(s(R.string.touch_tools_appearance_hint)))
        body.addView(button(s(R.string.touch_tools_done)) { completePanel() })
    }

    private fun colorLabel(color: Int?): String =
        color?.let { String.format(java.util.Locale.ROOT, "#%08X", it) } ?: s(R.string.touch_tools_color_default)

    private fun chooseColor(currentColor: Int?, onPick: (Int?) -> Unit) {
        val owner = current
        val content = column()
        val input = TextInputEditText(context).apply {
            hint = s(R.string.touch_tools_color_hex)
            setText(currentColor?.let { String.format(java.util.Locale.ROOT, "%08X", it) }.orEmpty())
            setSingleLine()
        }
        content.addView(input, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val palette = listOf(
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFF44336.toInt(), 0xFF4CAF50.toInt(),
            0xFF2196F3.toInt(), 0xFFFFEB3B.toInt(), 0xFF00BCD4.toInt(), 0xFFE91E63.toInt(),
            0xFFFF9800.toInt(), 0xFF9C27B0.toInt(), 0xFF9E9E9E.toInt(), 0xFF00E6E6.toInt()
        )
        palette.chunked(6).forEach { colors ->
            val line = row()
            colors.forEach { value ->
                line.addView(
                    android.view.View(context).apply {
                        setBackgroundColor(value)
                        contentDescription = String.format(java.util.Locale.ROOT, "#%08X", value)
                        setOnClickListener {
                            input.setText(String.format(java.util.Locale.ROOT, "%08X", value))
                        }
                    },
                    LinearLayout.LayoutParams(0, px(34), 1f).apply {
                        marginStart = px(3)
                        marginEnd = px(3)
                        topMargin = px(3)
                        bottomMargin = px(3)
                    }
                )
            }
            content.addView(line)
        }
        fun parse(): Int? {
            val text = input.text?.toString()?.trim()?.removePrefix("#").orEmpty()
            val normalized = if (text.length == 6) "FF$text" else text
            return if (normalized.length == 8) normalized.toLongOrNull(16)?.toInt() else null
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.touch_tools_color_pick)
            .setView(content)
            .setNeutralButton(R.string.touch_tools_color_default) { _, _ -> within(owner) { onPick(null) } }
            .setNegativeButton(R.string.touch_tools_cancel, null)
            .setPositiveButton(R.string.touch_tools_done) { _, _ ->
                parse()?.let { value -> within(owner) { onPick(value) } }
            }
            .show()
    }

    private fun showControls() {
        val body = openPanel(s(R.string.touch_tools_controls_title), key = "controls") ?: return
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
            }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
            }
            container.addView(item, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            toggle(container, s(R.string.touch_tools_enabled), control.enabled) {
                val before = capture("control:${control.id}", control.id)
                workspace.template.controls.firstOrNull { c -> c.id == control.id }?.let { c -> workspace.updateControl(c.copy(enabled = it)); rebaseControlFlags(c.id) }
                workspace.checkpointWindow(before, capture(before.key, control.id))
            }
            toggle(container, s(R.string.touch_tools_lock_geometry), control.locked) {
                val before = capture("control:${control.id}", control.id)
                workspace.template.controls.firstOrNull { c -> c.id == control.id }?.let { c -> workspace.updateControl(c.copy(locked = it)); rebaseControlFlags(c.id) }
                workspace.checkpointWindow(before, capture(before.key, control.id))
            }
            body.addView(container)
            body.addView(
                View(context).apply { setBackgroundColor(themeColor(com.google.android.material.R.attr.colorOutlineVariant)) },
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, px(1))
            )
        }
    }

    private fun showAdd() {
        val body = openPanel(s(R.string.touch_tools_add_title), key = "add") ?: return
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
        val faceKeys = listOf(
            GroupKey(text = "X", binding = ButtonBinding(setOf(2))),
            GroupKey(text = "A", binding = ButtonBinding(setOf(0))),
            GroupKey(text = "B", binding = ButtonBinding(setOf(1))),
            GroupKey(text = "Y", binding = ButtonBinding(setOf(3)))
        )
        val dpadKeys = listOf(
            GroupKey(text = "↑", binding = ButtonBinding(setOf(13))),
            GroupKey(text = "→", binding = ButtonBinding(setOf(14))),
            GroupKey(text = "↓", binding = ButtonBinding(setOf(15))),
            GroupKey(text = "←", binding = ButtonBinding(setOf(12)))
        )
        val previous = workspace.lastGeometry(type)
        var control = TouchControl(
            name = "",
            text = "",
            type = type,
            opacity = workspace.template.globalOpacity.coerceIn(0f, 1f),
            color = workspace.template.globalColor,
            bounds = previous?.bounds ?: if (type == ControlType.STICK) {
                TouchRect(0f, 0f, 96f * resources.displayMetrics.density, 96f * resources.displayMetrics.density)
            } else {
                TouchRect(0f, 0f, 72f * resources.displayMetrics.density, 72f * resources.displayMetrics.density)
            },
            shape = previous?.shape ?: if (type == ControlType.SWIPE || type == ControlType.GESTURE) ControlShape.RECTANGLE else ControlShape.CIRCLE,
            binding = if (type == ControlType.BUTTON || type == ControlType.VISIBILITY) ButtonBinding(emptySet()) else ButtonBinding(setOf(0)),
            groupKeys = if (type == ControlType.BUTTON_GROUP) faceKeys else TouchControl().groupKeys,
            stickTarget = if (type == ControlType.STICK) StickTarget.LEFT else StickTarget.RIGHT,
            stickDiameter = previous?.stickDiameter ?: 96f,
            swipePresetId = workspace.draft.presets.firstOrNull()?.id,
            gestures = emptyMap()
        )
        fun autoName(): String = when (type) {
            ControlType.BUTTON -> if (control.buttonAction != ButtonAction.NONE) {
                buttonActionName(control.buttonAction)
            } else if (control.binding.buttons.isEmpty()) {
                ""
            } else {
                mappingLabel(control.binding.buttons)
            }
            ControlType.BUTTON_GROUP -> if (control.groupStyle == GroupStyle.DPAD) {
                s(R.string.touch_workspace_dpad)
            } else {
                "ABXY"
            }
            ControlType.STICK -> stickName(control.stickTarget)
            ControlType.SWIPE -> s(R.string.touch_tools_default_swipe)
            ControlType.GESTURE -> s(R.string.touch_tools_default_gesture)
            ControlType.VISIBILITY -> s(R.string.touch_tools_default_visibility)
        }
        control = control.copy(name = autoName())
        if (type == ControlType.STICK) control = control.copy(bounds = control.stickBounds(density = resources.displayMetrics.density))
        val body = openPanel(s(R.string.touch_tools_new_control, control.name), key = "add") ?: return
        var nameEdited = false
        var updatingName = false
        val nameField = textField(body, s(R.string.touch_tools_name), control.name, true) {
            if (!updatingName) {
                nameEdited = true
                control = control.copy(name = it)
            }
        }
        fun renameIfAuto() {
            if (nameEdited) return
            control = control.copy(name = autoName())
            updatingName = true
            nameField.editText?.setText(control.name)
            updatingName = false
        }
        when (type) {
            ControlType.BUTTON -> {
                body.addView(label(s(R.string.touch_tools_select_mapping)))
                lateinit var mappingButton: MaterialButton
                fun updateMappingLabel() {
                    mappingButton.text = if (control.buttonAction == ButtonAction.NONE) {
                        if (control.binding.buttons.isEmpty()) s(R.string.touch_tools_select_mapping) else mappingLabel(control.binding.buttons)
                    } else {
                        s(R.string.touch_tools_action_value, buttonActionName(control.buttonAction))
                    }
                }
                mappingButton = button(
                    if (control.binding.buttons.isEmpty()) s(R.string.touch_tools_select_mapping) else mappingLabel(control.binding.buttons)
                ) {
                    mapping(control.binding.buttons, false, special = { action ->
                        control = control.copy(
                            buttonAction = action,
                            binding = ButtonBinding(emptySet()),
                            targetTemplateId = null,
                            hideSelf = false,
                            text = buttonActionName(action)
                        )
                        updateMappingLabel()
                        renameIfAuto()
                        if (action == ButtonAction.SWITCH_TEMPLATE) {
                            chooseNewButtonTemplate { target ->
                                control = control.copy(targetTemplateId = target)
                                updateMappingLabel()
                                renameIfAuto()
                            }
                        }
                    }) {
                        control = control.copy(
                            binding = control.binding.copy(buttons = it),
                            buttonAction = ButtonAction.NONE,
                            targetTemplateId = null,
                            hideSelf = false,
                            shape = defaultButtonShape(it),
                            text = mappingLabel(it)
                        )
                        updateMappingLabel()
                        renameIfAuto()
                    }
                }
                body.addView(mappingButton)
            }
            ControlType.BUTTON_GROUP -> choice(body, s(R.string.touch_tools_group_style), GroupStyle.values().toList(), control.groupStyle) {
                control = control.copy(
                    groupStyle = it,
                    groupKeys = if (it == GroupStyle.DPAD) dpadKeys else faceKeys
                )
                renameIfAuto()
            }
            ControlType.STICK -> {
                choice(body, s(R.string.touch_tools_target_stick), StickTarget.values().toList(), control.stickTarget) {
                    control = control.copy(stickTarget = it)
                    renameIfAuto()
                }
                stickModeButton(body, control.freeStick) { free -> control = control.copy(freeStick = free) }
                toggle(body, s(R.string.touch_tools_trigger_only), control.triggerOnly) { value ->
                    control = control.copy(triggerOnly = value)
                    if (!value) control = control.copy(bounds = control.stickBounds(density = resources.displayMetrics.density))
                }
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
            ControlType.VISIBILITY -> toggle(body, s(R.string.touch_tools_hide_self), control.hideSelf) {
                control = control.copy(hideSelf = it)
            }
        }
        body.addView(label(s(R.string.touch_tools_place_hint)))
        body.addView(
            button(s(R.string.touch_tools_place)) {
                if (!validFields()) return@button
                if (control.name.isBlank()) { workspace.message(s(R.string.touch_tools_enter_name)); return@button }
                if (control.type == ControlType.BUTTON) {
                    if (control.buttonAction == ButtonAction.NONE && control.binding.buttons.isEmpty()) {
                        workspace.message(s(R.string.touch_tools_mapping_missing)); return@button
                    }
                    if (control.buttonAction == ButtonAction.SWITCH_TEMPLATE && control.targetTemplateId == null) {
                        workspace.message(s(R.string.touch_tools_missing_template)); return@button
                    }
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

    private fun showProperties() {
        val id = current?.snapshot?.controlId ?: workspace.selectedId ?: return
        showPropertiesFor(id, rebuild = true)
    }

    private fun showPropertiesFor(id: String, restored: TouchWindowSnapshot? = null, rebuild: Boolean = false) {
        val key = "control:$id"
        val existing = windows[key]
        if (existing != null && !rebuild && restored == null) { existing.card.bringToFront(); return }
        val control = workspace.template.controls.firstOrNull { it.id == id } ?: return
        val snapshot = restored ?: existing?.let { it.snapshot.copy(position = positionOf(it.card), scroll = it.scroll.scrollY) } ?: capture(key, controlId = id)
        val avoid = workspace.placements[control.id]?.let { rect ->
            TouchRect(rect.x + workspace.safe.left, rect.y + workspace.safe.top, rect.width, rect.height)
        }
        val body = openPanel(
            s(R.string.touch_tools_control_properties, control.name),
            key = key,
            restored = snapshot,
            avoid = avoid
        ) ?: return
        propertyId = id
        val before = workspace.draft
        materialTabs(body, listOf(s(R.string.touch_tools_input), s(R.string.touch_tools_appearance), s(R.string.touch_tools_geometry)), propertyTab) { i ->
            if (validFields()) { propertyTab = i; showProperties() }
        }
        if (control.type == ControlType.BUTTON_GROUP) {
            val children = row()
            children.addView(
                button(s(R.string.touch_tools_whole_group)) {
                    childIndex = null; showProperties()
                }
            )
            control.groupKeys.forEachIndexed { index, key ->
                children.addView(
                    button(if (childIndex == index) "• ${key.text}" else key.text) {
                        childIndex = index; showProperties()
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
            visibility = VISIBLE
            addView(
                secondaryButton(s(R.string.touch_tools_cancel)) { cancelPanel() },
                LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                primaryButton(s(R.string.touch_tools_done)) { if (validFields()) { workspace.hideKeyboard(); completePanel() } },
                LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            )
        }

        val controlWindow = current
        val actions = actionRow()
        actions.addView(secondaryButton(s(R.string.touch_tools_duplicate)) { workspace.duplicateSelected(control.id) }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(
            secondaryButton(s(R.string.touch_tools_delete)) {
                MaterialAlertDialogBuilder(context).setTitle(s(R.string.touch_tools_delete_confirm, control.name))
                    .setNegativeButton(s(R.string.touch_tools_cancel), null).setPositiveButton(s(R.string.touch_tools_delete)) { _, _ ->
                        workspace.deleteSelected(control.id)
                        controlWindow?.let { closeWindow(it, false, true) }
                    }.show()
            },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        body.addView(actions)
        val layerActions = actionRow()
        layerActions.addView(secondaryButton(s(R.string.touch_tools_bring_forward)) { workspace.reorderSelected(true, control.id) },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        layerActions.addView(secondaryButton(s(R.string.touch_tools_send_backward)) { workspace.reorderSelected(false, control.id) },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        body.addView(layerActions)
    }

    private fun inputProperties(body: LinearLayout, control: TouchControl, before: TouchLibrary) {
        if (control.type != ControlType.BUTTON_GROUP || childIndex == null) {
            toggle(body, s(R.string.touch_tools_haptics), control.haptics) { value ->
                change { it.copy(haptics = value) }
            }
        }
        when (control.type) {
            ControlType.BUTTON -> {
                when (control.buttonAction) {
                    ButtonAction.NONE -> bindingFields(
                        body,
                        control.binding,
                        update = { value -> change { it.copy(binding = value) } },
                        mappingUpdate = { value ->
                            change { c ->
                                c.copy(
                                    binding = value,
                                    buttonAction = ButtonAction.NONE,
                                    targetTemplateId = null,
                                    hideSelf = false,
                                    name = renamedForMapping(c.name, c.binding.buttons, value.buttons),
                                    text = if (c.text.isBlank() || c.text == mappingLabel(c.binding.buttons)) {
                                        mappingLabel(value.buttons)
                                    } else {
                                        c.text
                                    }
                                )
                            }
                        },
                        special = { action ->
                            change { c ->
                                c.copy(
                                    buttonAction = action,
                                    binding = ButtonBinding(emptySet()),
                                    targetTemplateId = null,
                                    hideSelf = if (action == ButtonAction.TOGGLE_VISIBILITY) c.hideSelf else false
                                )
                            }
                            if (action == ButtonAction.SWITCH_TEMPLATE) chooseButtonTemplate(control.id) else showProperties()
                        }
                    )
                    ButtonAction.TOGGLE_VISIBILITY -> {
                        body.addView(button(s(R.string.touch_tools_action_value, s(R.string.touch_tools_action_toggle_visibility))) {
                            change { it.copy(buttonAction = ButtonAction.NONE, hideSelf = false) }
                            showProperties()
                        })
                        toggle(body, s(R.string.touch_tools_hide_self), control.hideSelf) { value ->
                            change { it.copy(hideSelf = value) }
                        }
                    }
                    ButtonAction.SWITCH_TEMPLATE -> {
                        val target = workspace.draft.templates.firstOrNull { it.id == control.targetTemplateId }
                        body.addView(button(s(R.string.touch_tools_target_template, target?.name ?: s(R.string.touch_tools_missing_template))) {
                            chooseButtonTemplate(control.id)
                        })
                        body.addView(button(s(R.string.touch_tools_action_value, s(R.string.touch_tools_action_switch_template))) {
                            change { it.copy(buttonAction = ButtonAction.NONE, targetTemplateId = null) }
                            showProperties()
                        })
                    }
                }
            }
            ControlType.BUTTON_GROUP -> {
                val index = childIndex
                if (index == null) {
                    body.addView(label(s(R.string.touch_tools_group_hint, enumLabel(control.groupStyle))))
                } else {
                    val key = control.groupKeys[index]
                    body.addView(label(s(R.string.touch_tools_child_scope, key.text, listOf(s(R.string.touch_tools_top), s(R.string.touch_tools_right), s(R.string.touch_tools_bottom), s(R.string.touch_tools_left))[index])))
                    bindingFields(body, key.binding, update = { binding ->
                        change { c ->
                            c.copy(groupKeys = c.groupKeys.mapIndexed { i, k -> if (i == index) k.copy(binding = binding) else k })
                        }
                    })
                }
            }
            ControlType.STICK -> {
                choice(body, s(R.string.touch_tools_target_stick), StickTarget.values().toList(), control.stickTarget) {
                    change { c -> c.copy(stickTarget = it, name = renamedForStick(c.name, c.stickTarget, it)) }
                }
                stickModeButton(body, control.freeStick) { free ->
                    change { c -> c.copy(freeStick = free) }
                    showProperties()
                }
                toggle(body, s(R.string.touch_tools_trigger_only), control.triggerOnly) {
                    change { c -> c.copy(triggerOnly = it) }
                    showProperties()
                }
                toggle(body, s(R.string.touch_tools_max_deflection), control.maximumDeflection) { change { c -> c.copy(maximumDeflection = it) } }
                number(body, s(R.string.touch_tools_stick_deadzone), control.stickDeadzone, 0f, 0.95f, true) { change { c -> c.copy(stickDeadzone = it) } }
                body.addView(
                    button(s(R.string.touch_tools_auxiliary, control.auxiliaryButton?.let { buttonName(it) } ?: s(R.string.touch_tools_none))) {
                        mapping(control.auxiliaryButton?.let { setOf(it) } ?: emptySet(), true, true) { values ->
                            change { it.copy(auxiliaryButton = values.firstOrNull()) }; showProperties()
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
                presetChoice(body, control.swipePresetId) { change { c -> c.copy(swipePresetId = it) }; showProperties() }
                body.addView(
                    button(s(R.string.touch_tools_tune_shared)) {
                        if (validFields()) { editingControl?.swipePresetId?.let { showTuning(it, false) } }
                    }
                )
                body.addView(
                    button(s(R.string.touch_tools_copy_tune)) {
                        if (!validFields()) return@button
                        val p = workspace.draft.presets.firstOrNull { it.id == editingControl?.swipePresetId } ?: return@button
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
                            if (validFields()) { workspace.select(control.id); workspace.startTest(true) }
                        }
                    )
                }
            }
            ControlType.GESTURE -> {
                body.addView(label(s(R.string.touch_tools_recognition_hint)))
                compass(
                    body,
                    true,
                    true,
                    null,
                    { direction -> gestureSummary(control.gestures[direction]) }
                ) { direction ->
                    gestureTarget(control.gestures[direction]) { value ->
                        change { c -> c.copy(gestures = if (value == null) c.gestures - direction else c.gestures + (direction to value)) }
                        showProperties()
                    }
                }
                number(body, s(R.string.touch_tools_recognition_distance), control.gestureDistance, 1f, 1000f) { change { c -> c.copy(gestureDistance = it) } }
                choice(body, s(R.string.touch_tools_swipe_timing), GestureTiming.values().toList(), control.gestureTiming) {
                    change { c -> c.copy(gestureTiming = it) }
                    showProperties()
                }
                if (control.gestureTiming == GestureTiming.IMMEDIATE) {
                    toggle(body, s(R.string.touch_tools_gesture_hold), control.gestureHold) { value ->
                        change { c -> c.copy(gestureHold = value) }
                    }
                    body.addView(label(s(R.string.touch_tools_gesture_hold_hint)))
                }
                body.addView(label(s(R.string.touch_tools_gesture_timing_hint)))
                number(body, s(R.string.touch_tools_gesture_pulse), control.gesturePulseMs.toFloat(), 1f, 10000f) { change { c -> c.copy(gesturePulseMs = it.toLong()) } }
                number(body, s(R.string.touch_tools_tap_timeout), control.tapTimeoutMs.toFloat(), 1f, 10000f) { change { c -> c.copy(tapTimeoutMs = it.toLong()) } }
                number(body, s(R.string.touch_tools_tap_tolerance), control.tapSlop, 0f, 500f) { change { c -> c.copy(tapSlop = it) } }
            }
            ControlType.VISIBILITY -> Unit
        }
    }

    private fun appearanceProperties(body: LinearLayout, control: TouchControl) {
        if (control.type != ControlType.BUTTON_GROUP || childIndex == null) {
            textField(body, s(R.string.touch_tools_control_name), control.name, false) { value -> change { it.copy(name = value) } }
        }
        if (control.type == ControlType.BUTTON || control.type == ControlType.VISIBILITY ||
            (control.type == ControlType.BUTTON_GROUP && childIndex != null)
        ) {
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
        if (control.type == ControlType.BUTTON || control.type == ControlType.VISIBILITY) {
            body.addView(
                button(s(R.string.touch_tools_shape_value, enumLabel(control.shape))) {
                    val next = if (control.shape == ControlShape.CIRCLE) ControlShape.RECTANGLE else ControlShape.CIRCLE
                    if (next == ControlShape.CIRCLE) {
                        val rect = workspace.placements[control.id] ?: control.bounds
                        workspace.setGeometry(control.id, rect.copy(height = rect.width))
                    }
                    if (change { c -> c.copy(shape = next) }) showProperties()
                }
            )
        }
        if (control.type != ControlType.BUTTON_GROUP || childIndex == null) {
            body.addView(button(s(R.string.touch_tools_color, colorLabel(control.color))) {
                chooseColor(control.color) { color ->
                    change { it.copy(color = color) }
                    showProperties()
                }
            })
            number(body, s(R.string.touch_tools_opacity), control.opacity * 100, 0f, 100f, true) { value -> change { it.copy(opacity = value / 100) } }
        }
        if (control.isArea) {
            toggle(body, s(R.string.touch_tools_area_border), control.showBorder) { value ->
                change { c -> c.copy(showBorder = value, showText = if (value) c.showText else false, showFill = if (value) c.showFill else false) }
                showProperties()
            }
            if (control.showBorder) {
                toggle(body, s(R.string.touch_tools_area_fill), control.showFill) { value ->
                    change { c -> c.copy(showFill = value) }
                    showProperties()
                }
                toggle(body, s(R.string.touch_tools_area_text), control.showText) { value ->
                    change { c -> c.copy(showText = value) }
                    showProperties()
                }
                body.addView(label(s(R.string.touch_tools_area_overlay_hint)))
                body.addView(label(s(R.string.touch_tools_area_fill_hint)))
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
        val circle = (control.type == ControlType.BUTTON || control.type == ControlType.VISIBILITY) &&
            control.shape == ControlShape.CIRCLE
        if (!control.isVisibleStick) {
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
        }
        if (control.type == ControlType.STICK) {
            number(
                body,
                s(R.string.touch_tools_stick_size),
                control.stickDiameter,
                24f,
                400f,
                true,
                helper = s(
                    if (control.isVisibleStick) R.string.touch_tools_hint_stick_visible
                    else R.string.touch_tools_hint_stick_size
                )
            ) { value -> change { c -> c.copy(stickDiameter = value) } }
        }
        val steps = row()
        listOf("←" to (-1f to 0f), "↑" to (0f to -1f), "↓" to (0f to 1f), "→" to (1f to 0f)).forEach { (label, d) ->
            steps.addView(
                button(s(R.string.touch_tools_step, label)) {
                    workspace.placements[control.id]?.let {
                        if (workspace.setGeometry(control.id, it.copy(x = it.x + d.first, y = it.y + d.second))) showProperties()
                    }
                }
            )
        }
        body.addView(horizontal(steps))
    }

    private fun bindingFields(
        body: LinearLayout,
        binding: ButtonBinding,
        update: (ButtonBinding) -> Unit,
        mappingUpdate: ((ButtonBinding) -> Unit)? = null,
        special: ((ButtonAction) -> Unit)? = null
    ) {
        var current = binding
        val mappingOwner = this.current
        val applyMapping = mappingUpdate ?: update
        val mappingButton = button(s(R.string.touch_tools_simultaneous, mappingLabel(binding.buttons))) {}
        mappingButton.setOnClickListener {
            within(mappingOwner) {
                mapping(current.buttons, false, special = special) { buttons ->
                    current = current.copy(buttons = buttons)
                    applyMapping(current)
                    mappingButton.text = s(R.string.touch_tools_simultaneous, mappingLabel(buttons))
                }
            }
        }
        body.addView(mappingButton, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val frequency = column().apply { visibility = if (binding.turbo) VISIBLE else GONE }
        toggle(body, s(R.string.touch_tools_turbo), binding.turbo) {
            current = current.copy(turbo = it); update(current)
            frequency.visibility = if (it) VISIBLE else GONE
        }
        number(
            frequency,
            s(R.string.touch_tools_turbo_frequency),
            binding.frequencyHz,
            TouchValidator.MIN_FREQUENCY_HZ,
            TouchValidator.MAX_FREQUENCY_HZ,
            true
        ) {
            current = current.copy(frequencyHz = it); update(current)
        }
        frequency.addView(label(s(R.string.touch_tools_turbo_hint)))
        body.addView(frequency)
    }

    private fun buttonActionName(action: ButtonAction): String = when (action) {
        ButtonAction.NONE -> s(R.string.touch_tools_action_normal)
        ButtonAction.TOGGLE_VISIBILITY -> s(R.string.touch_tools_action_toggle_visibility)
        ButtonAction.SWITCH_TEMPLATE -> s(R.string.touch_tools_action_switch_template)
    }

    private fun chooseNewButtonTemplate(onPick: (String) -> Unit) {
        val templates = workspace.draft.templates.filter { it.id != workspace.templateId }
        if (templates.isEmpty()) {
            workspace.message(s(R.string.touch_tools_no_other_templates))
            return
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.touch_tools_choose_template)
            .setItems(templates.map { it.name }.toTypedArray()) { _, index ->
                onPick(templates[index].id)
            }
            .setNegativeButton(R.string.touch_tools_cancel, null)
            .show()
    }

    private fun chooseButtonTemplate(controlId: String) {
        val templates = workspace.draft.templates.filter { it.id != workspace.templateId }
        if (templates.isEmpty()) {
            workspace.message(s(R.string.touch_tools_no_other_templates))
            change { it.copy(buttonAction = ButtonAction.NONE, targetTemplateId = null) }
            showProperties()
            return
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.touch_tools_choose_template)
            .setItems(templates.map { it.name }.toTypedArray()) { _, index ->
                val target = templates[index]
                change { it.copy(targetTemplateId = target.id) }
                showProperties()
            }
            .setNegativeButton(R.string.touch_tools_cancel, null)
            .show()
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

    fun showTuning(id: String, live: Boolean, tab: Int = 0, before: TouchLibrary? = null, restored: TouchWindowSnapshot? = null) {
        val preset = workspace.draft.presets.firstOrNull { it.id == id } ?: return
        val snapshot = before ?: workspace.draft
        val refs = workspace.draft.templates.sumOf { t -> t.controls.count { it.swipePresetId == id } }
        val avoid = workspace.selected?.let { control ->
            workspace.placements[control.id]?.let { rect ->
                TouchRect(rect.x + workspace.safe.left, rect.y + workspace.safe.top, rect.width, rect.height)
            }
        }
        val body = openPanel(
            if (live) s(R.string.touch_tools_live_title, editingControl?.name.orEmpty(), enumLabel(editingControl?.stickTarget)) else s(R.string.touch_tools_tune_title, preset.name),
            key = "preset:$id",
            restored = restored ?: windows["preset:$id"]?.snapshot ?: capture("preset:$id", presetId = id),
            avoid = avoid
        ) ?: return
        body.addView(label(s(R.string.touch_tools_tuning_summary, preset.name, refs, s(if (live) R.string.touch_tools_live_preview else R.string.touch_tools_draft_only))))
        current?.let { it.snapshot = it.snapshot.copy(tab = tab) }
        materialTabs(body, listOf(s(R.string.touch_tools_basic), s(R.string.touch_tools_advanced), s(R.string.touch_tools_notes)), tab) { i ->
            if (validFields()) showTuning(id, live, i, snapshot)
        }
        fun update(block: (SwipePreset) -> SwipePreset) {
            workspace.draft.presets.firstOrNull { it.id == id }?.let { workspace.updatePreset(block(it)) }
        }
        val shortEdgeDp = min(workspace.safe.width(), workspace.safe.height()) / resources.displayMetrics.density
        val dpScale = preset.scale == MotionScale.DP
        if (tab == 0) {
            body.addView(label(s(R.string.touch_tools_basic_intro)))
            number(
                body,
                s(R.string.touch_tools_sensitivity_x),
                preset.sensitivityX,
                0.01f,
                10f,
                true,
                helper = s(R.string.touch_tools_hint_sensitivity_x)
            ) { update { p -> p.copy(sensitivityX = it) } }
            number(
                body,
                s(R.string.touch_tools_sensitivity_y),
                preset.sensitivityY,
                0.01f,
                10f,
                true,
                helper = s(R.string.touch_tools_hint_sensitivity_y)
            ) { update { p -> p.copy(sensitivityY = it) } }
            number(
                body,
                if (dpScale) s(R.string.touch_tools_speed_dp) else s(R.string.touch_tools_speed_edge),
                preset.maximumSpeed,
                if (dpScale) 0.01f else 0.001f,
                if (dpScale) 10000f else 20f,
                true,
                helper = s(if (dpScale) R.string.touch_tools_hint_max_speed_dp else R.string.touch_tools_hint_max_speed_edge)
            ) { update { p -> p.copy(maximumSpeed = it) } }
            number(
                body,
                s(R.string.touch_tools_compensation),
                preset.deadzone,
                0f,
                0.95f,
                true,
                helper = s(R.string.touch_tools_hint_deadzone)
            ) { update { p -> p.copy(deadzone = it) } }
            number(
                body,
                s(R.string.touch_tools_smoothing),
                preset.smoothingMs,
                0f,
                10000f,
                helper = s(R.string.touch_tools_hint_smoothing)
            ) { update { p -> p.copy(smoothingMs = it) } }
            number(
                body,
                s(R.string.touch_tools_turn_smoothing),
                preset.turnSmoothingMs,
                0f,
                10000f,
                helper = s(R.string.touch_tools_hint_turn_smoothing)
            ) { update { p -> p.copy(turnSmoothingMs = it) } }
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
                            deadzone = 0.15f,
                            smoothingMs = 0f,
                            turnSmoothingMs = 24f,
                            maximumSpeed = if (p.scale == MotionScale.DP) 2f * shortEdgeDp else 2f
                        )
                    }
                    showTuning(id, live, tab, snapshot)
                }
            )
        } else if (tab == 1) {
            expandable(body, s(R.string.touch_tools_response_curve)) { section ->
                toggle(section, s(R.string.touch_tools_invert_x), preset.invertX) { update { p -> p.copy(invertX = it) } }
                toggle(section, s(R.string.touch_tools_invert_y), preset.invertY) { update { p -> p.copy(invertY = it) } }
                toggle(section, s(R.string.touch_tools_swap_axes), preset.swapAxes) { update { p -> p.copy(swapAxes = it) } }
                val scales = row()
                MotionScale.values().forEach { next ->
                    scales.addView(
                        button(s(R.string.touch_tools_scale_value, enumLabel(next))) {
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
                section.addView(scales)
                section.addView(label(s(R.string.touch_tools_hint_scale)))
                number(
                    section,
                    s(R.string.touch_tools_exponent),
                    preset.exponent,
                    0.1f,
                    5f,
                    true,
                    helper = s(R.string.touch_tools_hint_exponent)
                ) { update { p -> p.copy(exponent = it, curve = emptyList()) } }
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
                section.addView(label(s(R.string.touch_tools_hint_curve)))
                section.addView(
                    button(s(R.string.touch_tools_reset_response)) {
                        update { it.copy(invertX = false, invertY = false, swapAxes = false, exponent = 1f, curve = emptyList()) }
                        showTuning(id, live, tab, snapshot)
                    }
                )
            }
            expandable(body, s(R.string.touch_tools_timing_saturation)) { section ->
                number(
                    section,
                    s(R.string.touch_tools_stop_timeout),
                    preset.stopTimeoutMs.toFloat(),
                    1f,
                    10000f,
                    helper = s(R.string.touch_tools_hint_stop_timeout)
                ) { update { p -> p.copy(stopTimeoutMs = it.toLong()) } }
                section.addView(label(s(R.string.touch_tools_release_hint)))
                section.addView(
                    button(s(R.string.touch_tools_reset_timing)) {
                        update {
                            it.copy(stopTimeoutMs = 40)
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
        } else {
            body.addView(button(s(R.string.touch_tools_done)) { if (validFields()) completePanel() })
            body.addView(button(s(R.string.touch_tools_cancel_tuning)) { cancelPanel() })
            // In-game whole-layout testing is intentionally unavailable; single-control live debug
            // from a swipe area is the supported in-game tuning path.
            if (!workspace.gameAvailable) {
                body.addView(
                    button(s(R.string.touch_tools_fullscreen_local_test)) {
                        if (validFields()) { completePanel(); workspace.startTest(false) }
                    }
                )
            }
        }
    }

    private fun gestureTarget(current: GestureBinding?, onUpdate: (GestureBinding?) -> Unit) {
        val owner = this.current
        val update: (GestureBinding?) -> Unit = { value -> within(owner) { onUpdate(value) } }
        var selected = current
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(10), px(12), px(8))
            setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))
        }
        root.addView(label(s(R.string.touch_tools_gesture_output), 16f))
        val summary = label(gestureSummary(selected), 12f)
        root.addView(summary)
        val content = column()
        val scroll = ScrollView(context).apply { addView(content) }

        val controllerKeys = mutableMapOf<Int, MaterialButton>()
        val directionKeys = mutableMapOf<Pair<StickTarget, GestureDirection>, MaterialButton>()
        fun refresh() {
            summary.text = gestureSummary(selected)
            controllerKeys.forEach { (id, key) -> styleSelection(key, selected?.buttons?.contains(id) == true) }
            directionKeys.forEach { (pair, key) ->
                styleSelection(key, selected?.stick == pair.first && selected?.direction == pair.second)
            }
        }

        val picker = GamepadPicker(context, mappingSlots, 36f, 4.4f)
        for (slot in mappingSlots) {
            val key = mappingKey(slot.id) {
                val buttons = selected?.buttons.orEmpty().toMutableSet()
                if (!buttons.add(slot.id)) buttons.remove(slot.id)
                selected = if (buttons.isEmpty()) null else GestureBinding(buttons = buttons)
                refresh()
            }
            controllerKeys[slot.id] = key
            picker.addView(key)
        }

        fun directionPad(target: StickTarget, keySize: Int): View {
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            column.addView(label(stickName(target), 11f))
            val pad = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            listOf(
                listOf(GestureDirection.UP_LEFT, GestureDirection.UP, GestureDirection.UP_RIGHT),
                listOf(GestureDirection.LEFT, null, GestureDirection.RIGHT),
                listOf(GestureDirection.DOWN_LEFT, GestureDirection.DOWN, GestureDirection.DOWN_RIGHT)
            ).forEach { rowDirections ->
                val line = row()
                rowDirections.forEach { direction ->
                    if (direction == null) {
                        line.addView(label("·", 12f), LinearLayout.LayoutParams(0, px(keySize), 1f))
                    } else {
                        val arrow = directionArrow(direction)
                        val key = directionKey(arrow) {
                            selected = GestureBinding(stick = target, direction = direction)
                            refresh()
                        }
                        directionKeys[target to direction] = key
                        line.addView(key, LinearLayout.LayoutParams(0, px(keySize), 1f).apply {
                            marginStart = px(2)
                            marginEnd = px(2)
                        })
                    }
                }
                pad.addView(line)
            }
            column.addView(pad)
            return column
        }

        if (workspace.width > workspace.height) {
            val columns = row()
            val controller = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(s(R.string.touch_tools_controller_button), 12f))
                addView(picker, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            columns.addView(controller, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.35f))
            val sticks = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(s(R.string.touch_tools_direction_pad), 12f))
                addView(directionPad(StickTarget.LEFT, 28))
                addView(directionPad(StickTarget.RIGHT, 28))
            }
            columns.addView(sticks, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            content.addView(columns)
        } else {
            content.addView(label(s(R.string.touch_tools_controller_button), 12f))
            content.addView(picker, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            content.addView(label(s(R.string.touch_tools_direction_pad), 12f))
            val pads = row()
            for (target in listOf(StickTarget.LEFT, StickTarget.RIGHT)) {
                pads.addView(directionPad(target, 32), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            content.addView(pads)
        }
        root.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val actions = actionRow()
        actions.addView(
            secondaryButton(s(R.string.touch_tools_clear)) { selected = null; refresh() },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        actions.addView(
            secondaryButton(s(R.string.touch_tools_cancel)) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        actions.addView(
            primaryButton(s(R.string.touch_tools_done)) { update(selected); dialog.dismiss() },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(actions)
        dialog.setContentView(root, ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(themeColor(com.google.android.material.R.attr.colorSurface)))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        refresh()
        dialog.show()
    }

    private fun styleSelection(button: MaterialButton, selected: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(
            if (selected) themeColor(com.google.android.material.R.attr.colorPrimary) else Color.TRANSPARENT
        )
        button.setTextColor(
            if (selected) themeColor(com.google.android.material.R.attr.colorOnPrimary) else themeColor(com.google.android.material.R.attr.colorOnSurface)
        )
    }

    private fun mappingKey(id: Int, onClick: () -> Unit): MaterialButton =
        mappingKey(pickerLabel(id), s(R.string.touch_tools_mapping_button, buttonName(id)), onClick)

    private fun mappingKey(label: String, description: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(
            androidx.appcompat.view.ContextThemeWrapper(context, R.style.TouchButton_Theme_Text),
            null,
            com.google.android.material.R.attr.materialButtonStyle
        ).apply {
            text = label
            contentDescription = description
            isAllCaps = false
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
        }

    private fun directionKey(arrow: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(
            androidx.appcompat.view.ContextThemeWrapper(context, R.style.TouchButton_Theme_Text),
            null,
            com.google.android.material.R.attr.materialButtonStyle
        ).apply {
            text = arrow
            contentDescription = arrow
            isAllCaps = false
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
        }

    private fun directionArrow(direction: GestureDirection): String = when (direction) {
        GestureDirection.UP_LEFT -> "↖"
        GestureDirection.UP -> "↑"
        GestureDirection.UP_RIGHT -> "↗"
        GestureDirection.LEFT -> "←"
        GestureDirection.RIGHT -> "→"
        GestureDirection.DOWN_LEFT -> "↙"
        GestureDirection.DOWN -> "↓"
        GestureDirection.DOWN_RIGHT -> "↘"
        GestureDirection.TAP -> "●"
    }

    private fun mapping(
        initial: Set<Int>,
        single: Boolean,
        allowEmpty: Boolean = false,
        special: ((ButtonAction) -> Unit)? = null,
        onUpdate: (Set<Int>) -> Unit
    ) {
        val owner = current
        val update: (Set<Int>) -> Unit = { value -> within(owner) { onUpdate(value) } }
        val values = initial.filter { it in TouchValidator.buttonTargets }.toMutableSet()
        val dialog = Dialog(context)
        val summary = ChipGroup(context).apply { chipSpacingHorizontal = px(4); chipSpacingVertical = px(4) }
        val specialSlots = if (special == null) {
            emptyList()
        } else {
            listOf(
                MappingSlot(SPECIAL_VISIBILITY, 0.42f, 0.40f),
                MappingSlot(SPECIAL_SWITCH_TEMPLATE, 0.58f, 0.40f)
            )
        }
        val picker = GamepadPicker(context, mappingSlots + specialSlots)
        val keys = mutableMapOf<Int, MaterialButton>()
        fun refresh() {
            summary.removeAllViews()
            if (values.isEmpty()) {
                summary.addView(label(if (allowEmpty) s(R.string.touch_tools_no_auxiliary) else s(R.string.touch_tools_select_output)))
            }
            for (id in values.sorted()) {
                summary.addView(
                    Chip(context).apply {
                        text = buttonName(id)
                        isCloseIconVisible = true
                        minHeight = px(34)
                        setEnsureMinTouchTargetSize(false)
                        contentDescription = s(R.string.touch_tools_remove_output, buttonName(id))
                        setOnCloseIconClickListener { values -= id; refresh() }
                    }
                )
            }
            keys.forEach { (id, key) -> styleSelection(key, id in values) }
        }
        for (slot in mappingSlots) {
            val key = mappingKey(slot.id) {
                if (slot.id in values) {
                    values -= slot.id
                } else {
                    if (single) values.clear()
                    values += slot.id
                }
                refresh()
            }
            keys[slot.id] = key
            picker.addView(key)
        }
        if (special != null) {
            picker.addView(mappingKey(
                s(R.string.touch_tools_action_toggle_short),
                s(R.string.touch_tools_action_value, s(R.string.touch_tools_action_toggle_visibility))
            ) {
                dialog.dismiss()
                within(owner) { special(ButtonAction.TOGGLE_VISIBILITY) }
            })
            picker.addView(mappingKey(
                s(R.string.touch_tools_action_switch_short),
                s(R.string.touch_tools_action_value, s(R.string.touch_tools_action_switch_template))
            ) {
                dialog.dismiss()
                within(owner) { special(ButtonAction.SWITCH_TEMPLATE) }
            })
        }
        refresh()
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(10), px(12), px(8))
            setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))
        }
        root.addView(label(s(if (single) R.string.touch_tools_button_output else R.string.touch_tools_button_combination), 16f))
        root.addView(label(s(if (single) R.string.touch_tools_mapping_hint_single else R.string.touch_tools_mapping_hint), 12f))
        root.addView(summary)
        root.addView(picker, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        fun applySelection() {
            if (values.isEmpty() && !allowEmpty) {
                workspace.message(s(R.string.touch_tools_select_button))
            } else if (values.any { it == 18 || it == 19 }) {
                MaterialAlertDialogBuilder(context)
                    .setTitle(s(R.string.touch_tools_system_mapping)).setMessage(s(R.string.touch_tools_system_warning))
                    .setPositiveButton(s(R.string.touch_tools_use_mapping)) { _, _ -> update(values.toSet()); dialog.dismiss() }
                    .setNegativeButton(s(R.string.touch_tools_cancel), null).show()
            } else {
                update(values.toSet())
                dialog.dismiss()
            }
        }
        val actions = actionRow()
        actions.addView(secondaryButton(s(R.string.touch_tools_clear)) { values.clear(); refresh() }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(secondaryButton(s(R.string.touch_tools_cancel)) { dialog.dismiss() }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(primaryButton(s(R.string.touch_tools_done)) { applySelection() }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        dialog.setContentView(root, ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(themeColor(com.google.android.material.R.attr.colorSurface)))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.show()
    }

    private fun pickerLabel(id: Int): String = when (id) {
        12 -> "←"
        13 -> "↑"
        14 -> "→"
        15 -> "↓"
        19 -> "Cap"
        else -> buttonName(id)
    }

    private data class MappingSlot(val id: Int, val x: Float, val y: Float)

    private val SPECIAL_VISIBILITY = -101
    private val SPECIAL_SWITCH_TEMPLATE = -102

    private val mappingSlots = listOf(
        MappingSlot(8, 0.07f, 0.14f),
        MappingSlot(6, 0.18f, 0.14f),
        MappingSlot(11, 0.36f, 0.14f),
        MappingSlot(10, 0.46f, 0.14f),
        MappingSlot(18, 0.57f, 0.14f),
        MappingSlot(19, 0.69f, 0.14f),
        MappingSlot(7, 0.82f, 0.14f),
        MappingSlot(9, 0.93f, 0.14f),
        MappingSlot(13, 0.18f, 0.40f),
        MappingSlot(2, 0.82f, 0.40f),
        MappingSlot(12, 0.08f, 0.66f),
        MappingSlot(14, 0.28f, 0.66f),
        MappingSlot(4, 0.40f, 0.66f),
        MappingSlot(5, 0.60f, 0.66f),
        MappingSlot(3, 0.72f, 0.66f),
        MappingSlot(0, 0.92f, 0.66f),
        MappingSlot(15, 0.18f, 0.88f),
        MappingSlot(1, 0.82f, 0.88f)
    )

    private inner class GamepadPicker(
        context: Context,
        private val slots: List<MappingSlot>,
        private val maxChild: Float = 44f,
        private val rows: Float = 5f
    ) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
            val limit = if (availableHeight > 0) availableHeight * 0.19f else px(320f)
            val child = min(px(maxChild), min(width * 0.14f, limit)).toInt().coerceAtLeast(px(30f).toInt())
            for (index in slots.indices) {
                getChildAt(index).measure(
                    MeasureSpec.makeMeasureSpec(child, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(child, MeasureSpec.EXACTLY)
                )
            }
            val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                (child * rows).toInt()
            } else {
                resolveSize((child * rows).toInt(), heightMeasureSpec)
            }
            setMeasuredDimension(width, height)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val width = r - l
            val height = b - t
            for (index in slots.indices) {
                val slot = slots[index]
                val child = getChildAt(index)
                val centerX = (slot.x * width).toInt()
                val centerY = (slot.y * height).toInt()
                val left = (centerX - child.measuredWidth / 2).coerceIn(0, (width - child.measuredWidth).coerceAtLeast(0))
                val top = (centerY - child.measuredHeight / 2).coerceIn(0, (height - child.measuredHeight).coerceAtLeast(0))
                child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
            }
        }
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
        value.buttons.isNotEmpty() -> mappingLabel(value.buttons)
        else -> s(R.string.touch_tools_stick_direction, enumLabel(value.stick), enumLabel(value.direction))
    }

    private fun mappingLabel(buttons: Set<Int>): String =
        buttons.sorted().joinToString(" + ") { buttonName(it) }.ifEmpty { s(R.string.touch_tools_none) }

    /** Shoulders, triggers and system keys are wide; face and stick buttons stay round. */
    private fun defaultButtonShape(buttons: Set<Int>): ControlShape =
        if (buttons.any { it in setOf(6, 7, 8, 9, 10, 11, 18, 19) }) ControlShape.RECTANGLE else ControlShape.CIRCLE

    private fun stickName(target: StickTarget): String = s(
        if (target == StickTarget.LEFT) R.string.touch_canvas_left_stick else R.string.touch_canvas_right_stick
    )

    /** Keep a user-edited name, but follow the mapping while the name is still the automatic one. */
    private fun renamedForMapping(currentName: String, oldButtons: Set<Int>, newButtons: Set<Int>): String =
        if (currentName.isBlank() || currentName == mappingLabel(oldButtons)) mappingLabel(newButtons) else currentName

    private fun renamedForStick(currentName: String, oldTarget: StickTarget, newTarget: StickTarget): String =
        if (currentName.isBlank() || currentName == stickName(oldTarget)) stickName(newTarget) else currentName

    private val editingControl: TouchControl? get() = workspace.template.controls.firstOrNull {
        it.id == (current?.snapshot?.controlId ?: workspace.selectedId)
    }

    private fun change(block: (TouchControl) -> TouchControl): Boolean = editingControl?.let { workspace.updateControl(block(it)) } ?: false

    private fun <T> choice(body: LinearLayout, label: String, values: List<T>, selected: T, onUpdate: (T) -> Unit) {
        if (values.size == 2) {
            val owner = current
            var current = selected
            fun text(value: T) = s(R.string.touch_tools_label_value, label, enumLabel(value))
            val view = button(text(current)) {}
            view.setOnClickListener {
                current = values.firstOrNull { it != current } ?: values.first()
                within(owner) { onUpdate(current) }
                view.text = text(current)
            }
            body.addView(view)
            return
        }
        val owner = current
        val update: (T) -> Unit = { value -> within(owner) { onUpdate(value) } }
        val item = row().apply {
            minimumHeight = px(44)
            isClickable = true
            isFocusable = true
            setBackgroundResource(selectableBackground())
        }
        item.addView(label(label), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val value = label(enumLabel(selected)).apply {
            gravity = Gravity.END
            setTextColor(readable(themeColor(com.google.android.material.R.attr.colorSecondary), surfaceColor()))
        }
        item.addView(value, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        item.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_dropdown_arrow)
                imageTintList = ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                contentDescription = null
            },
            LinearLayout.LayoutParams(px(24), px(24)).apply { marginStart = px(8) }
        )
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
        val owner = current
        val tabs = TabLayout(androidx.appcompat.view.ContextThemeWrapper(context, R.style.TouchTabLayout)).apply {
            tabMode = TabLayout.MODE_FIXED
            setBackgroundColor(Color.TRANSPARENT)
            setTabTextColors(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant), themeColor(com.google.android.material.R.attr.colorPrimary))
            setSelectedTabIndicatorColor(themeColor(com.google.android.material.R.attr.colorPrimary))
        }
        labels.forEachIndexed { index, text -> tabs.addTab(tabs.newTab().setText(text), index == selected) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { within(owner) { change(tab.position) } }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        body.addView(tabs, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, px(38)))
    }

    private fun toggle(body: LinearLayout, title: String, checked: Boolean, onUpdate: (Boolean) -> Unit) {
        val owner = current
        val update: (Boolean) -> Unit = { value -> within(owner) { onUpdate(value) } }
        body.addView(
            MaterialSwitch(context).apply {
                text = title; isChecked = checked; minHeight = px(40)
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, px(4), 0, px(4))
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                setOnCheckedChangeListener { _, value -> update(value) }
            }
        )
    }

    private fun stickModeButton(body: LinearLayout, freeStick: Boolean, onChange: (Boolean) -> Unit) {
        var free = freeStick
        val owner = current
        fun text(value: Boolean) = s(
            R.string.touch_tools_stick_mode_value,
            s(if (value) R.string.touch_tools_stick_free else R.string.touch_tools_stick_fixed)
        )
        val view = button(text(free)) {}
        view.setOnClickListener {
            free = !free
            view.text = text(free)
            within(owner) { onChange(free) }
        }
        body.addView(view)
    }

    private fun number(
        body: LinearLayout,
        title: String,
        value: Float,
        min: Float,
        max: Float,
        slider: Boolean = false,
        validate: ((Float) -> String?)? = null,
        helper: String? = null,
        onUpdate: (Float) -> Unit
    ): TextInputEditText {
        val owner = current
        val update: (Float) -> Unit = { value -> within(owner) { onUpdate(value) } }
        val input = TextInputLayout(context).apply {
            hint = title
            if (helper != null) helperText = helper
        }
        val edit = TextInputEditText(input.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(format(value)); setSingleLine()
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            minHeight = px(40)
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
                val error = within(owner) { validate?.invoke(number) }
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
        return edit
    }

    private fun textField(
        body: LinearLayout,
        title: String,
        value: String,
        allowEmpty: Boolean,
        multiline: Boolean = false,
        onUpdate: (String) -> Unit
    ): TextInputLayout {
        val owner = current
        val update: (String) -> Unit = { value -> within(owner) { onUpdate(value) } }
        val input = TextInputLayout(context).apply { hint = title }
        val edit = TextInputEditText(input.context).apply {
            setText(value)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            if (!multiline) setSingleLine() else minLines = 2
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

    private fun View.isDescendantOf(ancestor: View): Boolean {
        var view: android.view.ViewParent? = parent
        while (view != null) { if (view === ancestor) return true; view = view.parent }
        return false
    }

    private fun setError(input: TextInputLayout, error: String?) {
        input.error = error
        val owner = windows.values.firstOrNull { window -> input.isDescendantOf(window.card) } ?: current
        if (error == null) owner?.invalid?.remove(input) else owner?.invalid?.add(input)
    }

    private fun validFields(): Boolean {
        if (invalidFields.isEmpty()) return true
        invalidFields.first().requestFocus()
        workspace.message(s(R.string.touch_tools_fix_fields))
        return false
    }

    private fun expandable(body: LinearLayout, title: String, build: (LinearLayout) -> Unit) {
        val section = column()
        body.addView(label(title, 12f))
        build(section)
        body.addView(section)
    }

    private fun resizable(handle: View, target: View, window: ToolWindow) {
        var startX = 0f
        var startY = 0f
        var startLeft = 0f
        var startTop = 0f
        var startWidth = 0
        var startHeight = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    startLeft = target.x
                    startTop = target.y
                    startWidth = target.width
                    startHeight = target.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val nextWidth = (startWidth + event.rawX - startX).toInt().coerceIn(px(200), max(px(200), width - px(8)))
                    val nextHeight = (startHeight + event.rawY - startY).toInt().coerceIn(px(160), max(px(160), height - px(8)))
                    target.layoutParams = (target.layoutParams as LayoutParams).apply {
                        width = nextWidth
                        height = nextHeight
                    }
                    target.x = startLeft.coerceAtMost((width - px(8) - nextWidth).toFloat().coerceAtLeast(0f))
                    target.y = startTop.coerceAtMost((height - px(8) - nextHeight).toFloat().coerceAtLeast(0f))
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val params = target.layoutParams as LayoutParams
                    val size = TouchToolSize(
                        params.width / resources.displayMetrics.density,
                        params.height / resources.displayMetrics.density
                    )
                    sizes[window.snapshot.key] = size
                    window.snapshot = window.snapshot.copy(
                        width = size.width,
                        height = size.height,
                        position = TouchToolPosition(
                            target.x / max(1f, width - params.width.toFloat()),
                            target.y / max(1f, height - params.height.toFloat())
                        ).normalized()
                    )
                    true
                }
                else -> true
            }
        }
    }

    private fun draggable(handle: View, target: View, clickOnTap: Boolean = false) {
        var startX = 0f
        var startY = 0f
        var originalX = 0f
        var originalY = 0f
        var pointer = -1
        if (handle.contentDescription.isNullOrBlank()) handle.contentDescription = s(R.string.touch_tools_drag)
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
                        target.y = (originalY + event.rawY - startY).coerceIn(0f, max(0f, height - target.height.toFloat()))
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
                        windows.values.firstOrNull { it.card === target }?.let { it.snapshot = it.snapshot.copy(position = positionOf(target)) }
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun button(text: String, click: () -> Unit): MaterialButton = themedButton(
        text, R.style.TouchButton_Text, click
    )

    private fun secondaryButton(text: String, click: () -> Unit): MaterialButton = themedButton(
        text, R.style.TouchButton_Secondary, click
    )

    private fun primaryButton(text: String, click: () -> Unit): MaterialButton = themedButton(
        text, R.style.TouchButton_Primary, click
    )

    private fun themedButton(text: String, style: Int, click: () -> Unit): MaterialButton {
        val owner = current
        return MaterialButton(androidx.appcompat.view.ContextThemeWrapper(context, when (style) {
            R.style.TouchButton_Primary -> R.style.TouchButton_Theme_Primary
            R.style.TouchButton_Secondary -> R.style.TouchButton_Theme_Secondary
            else -> R.style.TouchButton_Theme_Text
        }), null, com.google.android.material.R.attr.materialButtonStyle).apply {
            this.text = text
            contentDescription = text
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            letterSpacing = 0.01f
            isAllCaps = false
            minHeight = px(40)
            minimumHeight = px(40)
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(px(10), px(4), px(10), px(4))
            setOnClickListener { within(owner) { click() } }
        }
    }

    private fun iconButton(drawable: Int, description: String, click: (View) -> Unit): MaterialButton =
        themedButton("", R.style.TouchButton_Text) {}.apply {
            icon = ContextCompat.getDrawable(context, drawable)
            iconSize = px(20)
            iconPadding = 0
            iconTint = ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorOnSurface))
            contentDescription = description
            tooltipText = description
            setPadding(px(8), 0, px(8), 0)
            layoutParams = LinearLayout.LayoutParams(px(40), px(40))
            setOnClickListener { click(this) }
        }

    private fun label(text: String, size: Float = 12f): TextView = TextView(context).apply {
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        this.text = text
        textSize = size
        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
        setPadding(0, px(2), 0, px(2))
    }

    private fun s(id: Int, vararg args: Any): String = if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
    private fun readable(preferred: Int, background: Int): Int {
        val opaque = background or (0xff shl 24)
        return if (androidx.core.graphics.ColorUtils.calculateContrast(preferred or (0xff shl 24), opaque) >= 4.5) {
            preferred
        } else if (androidx.core.graphics.ColorUtils.calculateContrast(Color.BLACK, opaque) >= 4.5) Color.BLACK else Color.WHITE
    }

    private fun themeColor(attr: Int): Int = MaterialColors.getColor(this, attr)

    private fun selectableBackground(): Int {
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return value.resourceId
    }

    private fun enumLabel(value: Any?): String = when (value) {
        ControlType.BUTTON -> s(R.string.touch_tools_enum_controltype_button)
        ControlType.BUTTON_GROUP -> s(R.string.touch_tools_enum_controltype_button_group)
        ControlType.STICK -> s(R.string.touch_tools_enum_controltype_stick)
        ControlType.SWIPE -> s(R.string.touch_tools_enum_controltype_swipe)
        ControlType.GESTURE -> s(R.string.touch_tools_enum_controltype_gesture)
        ControlType.VISIBILITY -> s(R.string.touch_tools_enum_controltype_visibility)
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

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(px(6), px(6), px(6), px(6)) }
    private inner class ActionRow : LinearLayout(context) {
        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            for (index in 0 until childCount) {
                getChildAt(index).layoutParams = (getChildAt(index).layoutParams as LinearLayout.LayoutParams).apply {
                    height = LayoutParams.WRAP_CONTENT
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val childHeight = (0 until childCount).maxOfOrNull { getChildAt(it).measuredHeight } ?: 0
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                child.measure(MeasureSpec.makeMeasureSpec(child.measuredWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY))
            }
            setMeasuredDimension(measuredWidth, resolveSize(childHeight + paddingTop + paddingBottom, heightMeasureSpec))
        }
    }

    private fun actionRow() = ActionRow()

    private fun row() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isBaselineAligned = false
    }
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
    private fun expandedToolbarWidth() = if (workspace.width > workspace.height) {
        min(px(380), max(px(180), workspace.width / 2 - px(12)))
    } else {
        min(px(320), max(px(180), workspace.width - px(16)))
    }
    private fun toolbarWidth() = if (toolbarCollapsed) min(px(190), expandedToolbarWidth()) else expandedToolbarWidth()
    private fun panelWidth() = if (workspace.width > workspace.height) {
        min(px(280), max(px(180), workspace.width / 2 - px(12)))
    } else {
        min(px(280), max(px(180), workspace.width - px(16)))
    }
    private fun panelHeight() = if (workspace.width > workspace.height) {
        min(px(300), max(px(180), (workspace.height * 0.68f).toInt()))
    } else {
        min(px(300), (workspace.height - max(toolbar.height, px(40)) - px(16)).coerceAtLeast(px(112)))
    }
    private fun px(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun px(value: Float) = value * resources.displayMetrics.density
    private fun format(value: Float) = if (abs(value - value.roundToInt()) < 0.0001f) value.roundToInt().toString() else "%.3f".format(java.util.Locale.ROOT, value)
    private fun buttonName(id: Int) = mapOf(
        0 to "A", 1 to "B", 2 to "X", 3 to "Y", 4 to "L3", 5 to "R3", 6 to "L", 7 to "R",
        8 to "ZL", 9 to "ZR", 10 to "+", 11 to "−", 12 to s(R.string.touch_tools_dleft), 13 to s(R.string.touch_tools_dup), 14 to s(R.string.touch_tools_dright), 15 to s(R.string.touch_tools_ddown), 18 to s(R.string.touch_tools_home), 19 to s(R.string.touch_tools_capture)
    )[id] ?: id.toString()
}
