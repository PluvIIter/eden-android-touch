// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.os.Bundle
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.google.android.material.color.MaterialColors
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.overlay.touch.model.ControlShape
import org.yuzu.yuzu_emu.overlay.touch.model.ControlType
import org.yuzu.yuzu_emu.overlay.touch.model.GroupStyle
import org.yuzu.yuzu_emu.overlay.touch.model.StickTarget
import org.yuzu.yuzu_emu.overlay.touch.model.SwipePreset
import org.yuzu.yuzu_emu.overlay.touch.model.TouchControl
import org.yuzu.yuzu_emu.overlay.touch.model.TouchRect
import org.yuzu.yuzu_emu.overlay.touch.runtime.PointerSnapshot
import org.yuzu.yuzu_emu.overlay.touch.runtime.RuntimeSnapshot
import org.yuzu.yuzu_emu.overlay.touch.runtime.TouchHitGeometry
import kotlin.math.min
import kotlin.math.pow

internal class TouchCanvas(context: Context, private val workspace: TouchWorkspace) : View(context) {
    var preview: TouchControl? = null
    var feedback: String? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val primary = role(com.google.android.material.R.attr.colorPrimary)
    private val secondary = role(com.google.android.material.R.attr.colorSecondary)
    private val ink = role(com.google.android.material.R.attr.colorOnSurface)
    private val surface = role(com.google.android.material.R.attr.colorSurface)
    private val variant = role(com.google.android.material.R.attr.colorSurfaceVariant)
    private val outline = role(com.google.android.material.R.attr.colorOutline)
    private val onPrimary = role(com.google.android.material.R.attr.colorOnPrimary).let {
        if (ColorUtils.calculateContrast(it, primary) >= 4.5) {
            it
        } else {
            listOf(ink, surface).maxByOrNull { candidate ->
                ColorUtils.calculateContrast(candidate, primary)
            } ?: it
        }
    }
    private val error = role(com.google.android.material.R.attr.colorError)
    private val dash = DashPathEffect(floatArrayOf(dp(7), dp(5)), 0f)
    private val labelTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val accessibility = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            if (!workspace.isEditing) return INVALID_ID
            return workspace.template.controls.indexOfLast { control ->
                TouchHitGeometry.contains(control, workspace.placements[control.id] ?: control.bounds, x - workspace.safe.left, y - workspace.safe.top)
            }.takeIf { it >= 0 } ?: INVALID_ID
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            if (workspace.isEditing) ids.addAll(workspace.template.controls.indices)
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val control = workspace.template.controls.getOrNull(id)
            node.className = "android.widget.Button"
            node.contentDescription = control?.let(::controlDescription) ?: context.getString(R.string.touch_canvas_accessibility)
            val bounds = control?.let { workspace.placements[it.id] ?: it.bounds }
            node.setBoundsInParent(
                if (bounds == null) {
                    Rect(0, 0, 1, 1)
                } else {
                    Rect(
                        (bounds.x + workspace.safe.left).toInt(),
                        (bounds.y + workspace.safe.top).toInt(),
                        (bounds.right + workspace.safe.left).toInt(),
                        (bounds.bottom + workspace.safe.top).toInt()
                    )
                }
            )
            node.isClickable = control != null
            node.isSelected = control?.id == workspace.selectedId
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            val control = workspace.template.controls.getOrNull(id) ?: return false
            if (!workspace.isEditing || action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            workspace.select(control.id)
            return true
        }
    }

    init {
        isClickable = true
        isFocusable = true
        ViewCompat.setAccessibilityDelegate(this, accessibility)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    private fun controlDescription(control: TouchControl): String {
        val type = context.getString(
            when (control.type) {
                ControlType.BUTTON -> R.string.touch_tools_enum_controltype_button
                ControlType.BUTTON_GROUP -> R.string.touch_tools_enum_controltype_button_group
                ControlType.STICK -> R.string.touch_tools_enum_controltype_stick
                ControlType.SWIPE -> R.string.touch_tools_enum_controltype_swipe
                ControlType.GESTURE -> R.string.touch_tools_enum_controltype_gesture
                ControlType.VISIBILITY -> R.string.touch_tools_enum_controltype_visibility
            }
        )
        val mapping = if (control.type == ControlType.STICK || control.type == ControlType.SWIPE) {
            stickName(control.stickTarget)
        } else {
            when (control.type) {
                ControlType.BUTTON_GROUP -> control.groupKeys.flatMap { it.binding.buttons }.toSet()
                ControlType.GESTURE -> control.gestures.values.flatMap { it.buttons }.toSet()
                else -> control.binding.buttons
            }.joinToString(" + ") { buttonName(it) }
        }
        return context.getString(
            R.string.touch_canvas_control_accessibility,
            control.name,
            type,
            mapping,
            context.getString(if (control.enabled) R.string.touch_canvas_enabled else R.string.touch_canvas_disabled)
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = workspace.onCanvasTouch(event)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val safe = workspace.safe
        val w = safe.width()
        val h = safe.height()
        canvas.save()
        canvas.translate(safe.left, safe.top)
        if (workspace.isEditing) drawGrid(canvas, w, h, overlay = workspace.gameAvailable)
        val snapshot = workspace.runtime.snapshot()
        for (control in workspace.template.controls.sortedBy { if (it.isArea) 0 else 1 }) {
            if (!workspace.isControlVisible(control)) continue
            if (workspace.isolatedId != null && workspace.isolatedId != control.id) continue
            if (!workspace.isEditing && !control.enabled) continue
            val rect = workspace.placements[control.id] ?: control.bounds
            val pointers = snapshot.pointers.filter { it.controlId == control.id }
            val active = pointers.isNotEmpty()
            val opacity = (control.opacity * workspace.template.globalOpacity).coerceIn(0f, 1f)
            when (control.type) {
                ControlType.BUTTON -> drawButton(canvas, rect, control.text, control.shape == ControlShape.CIRCLE, active, opacity, control.color)
                ControlType.VISIBILITY -> drawButton(canvas, rect, control.text.ifBlank { control.name }, control.shape == ControlShape.CIRCLE, active, opacity, control.color)
                ControlType.BUTTON_GROUP -> drawGroup(canvas, control, rect, pointers, opacity)
                ControlType.STICK -> drawStick(canvas, control, rect, pointers, snapshot, opacity, w, h)
                ControlType.SWIPE, ControlType.GESTURE -> {
                    if (workspace.isEditing || workspace.testing) {
                        drawArea(canvas, control, rect, active)
                    } else if (control.showBorder) {
                        drawAreaOverlay(canvas, control, rect)
                    }
                }
            }
            if (workspace.isEditing && !control.enabled) {
                stroke(error, dp(1.5f))
                canvas.drawLine(rect.x + dp(6), rect.bottom - dp(6), rect.right - dp(6), rect.y + dp(6), paint)
                chip(canvas, context.getString(R.string.touch_canvas_disabled), rect.x + rect.width / 2, rect.bottom - dp(10), rect.width, error)
            }
        }
        preview?.let { control ->
            fill(primary, 0.12f)
            shape(canvas, control.bounds, !control.isArea && control.shape == ControlShape.CIRCLE)
            stroke(primary, dp(2))
            paint.pathEffect = dash
            shape(canvas, control.bounds, !control.isArea && control.shape == ControlShape.CIRCLE)
            paint.pathEffect = null
        }
        if (workspace.isEditing) drawSelection(canvas, w, h)
        if (workspace.testing) drawHud(canvas, snapshot, w, h)
        feedback?.let {
            chip(canvas, it, w / 2, dp(26), (w - dp(24)).coerceAtLeast(1f), error)
        }
        canvas.restore()
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float, overlay: Boolean = false) {
        if (!overlay) canvas.drawColor(surface)
        stroke(ink, dp(0.5f), if (overlay) 0.06f else 0.035f)
        val step = workspace.gridStep()
        var x = 0f
        while (x < w) { canvas.drawLine(x, 0f, x, h, paint); x += step }
        var y = 0f
        while (y < h) { canvas.drawLine(0f, y, w, y, paint); y += step }
        fill(ink, if (overlay) 0.1f else 0.12f)
        x = 0f
        while (x < w) {
            y = 0f
            while (y < h) { canvas.drawCircle(x, y, dp(0.7f), paint); y += step * 4 }
            x += step * 4
        }
    }

    private fun drawButton(canvas: Canvas, rect: TouchRect, label: String, circle: Boolean, active: Boolean, opacity: Float, color: Int? = null) {
        val base = if (active) primary else ColorUtils.blendARGB(surface, variant, 0.68f)
        fill(base, opacity * 0.94f)
        shape(canvas, rect, circle)
        stroke(color ?: if (active) primary else outline, dp(if (active) 2f else 1.4f), opacity)
        shape(canvas, rect, circle)
        if (min(rect.width, rect.height) > dp(28)) {
            val inset = dp(3)
            stroke(color ?: if (active) onPrimary else ink, dp(0.6f), opacity * 0.2f)
            shape(canvas, TouchRect(rect.x + inset, rect.y + inset, rect.width - inset * 2, rect.height - inset * 2), circle)
        }
        fill(color ?: if (active) onPrimary else ink, opacity)
        text(
            canvas,
            label,
            rect.x + rect.width / 2,
            rect.y + rect.height / 2,
            min(dp(20), rect.height * 0.36f),
            (rect.width - dp(12)).coerceAtLeast(1f)
        )
    }

    private fun drawGroup(canvas: Canvas, control: TouchControl, rect: TouchRect, pointers: List<PointerSnapshot>, opacity: Float) {
        val keys = TouchHitGeometry.faceKeys(control, rect)
        if (control.groupStyle == GroupStyle.DPAD) {
            val size = min(rect.width, rect.height) / 3f
            fill(ColorUtils.blendARGB(surface, variant, 0.68f), opacity * 0.94f)
            canvas.drawRoundRect(
                RectF(
                    rect.x + rect.width / 2 - size / 2,
                    rect.y,
                    rect.x + rect.width / 2 + size / 2,
                    rect.bottom
                ),
                dp(5),
                dp(5),
                paint
            )
            canvas.drawRoundRect(
                RectF(
                    rect.x,
                    rect.y + rect.height / 2 - size / 2,
                    rect.right,
                    rect.y + rect.height / 2 + size / 2
                ),
                dp(5),
                dp(5),
                paint
            )
        }
        for ((index, key) in keys.withIndex()) {
            drawButton(
                canvas,
                key,
                control.groupKeys.getOrNull(index)?.text.orEmpty(),
                control.groupStyle == GroupStyle.FACE,
                pointers.any { index in it.activeKeys },
                opacity,
                control.color
            )
        }
    }

    private fun drawArea(canvas: Canvas, control: TouchControl, rect: TouchRect, active: Boolean) {
        val accent = if (control.id == workspace.selectedId || active) primary else secondary
        fill(accent, if (active) 0.12f else 0.055f)
        shape(canvas, rect, false)
        stroke(accent, dp(if (active) 1.8f else 1.2f), 0.8f)
        paint.pathEffect = dash
        shape(canvas, rect, false)
        paint.pathEffect = null
        val type = when (control.type) {
            ControlType.STICK -> context.getString(R.string.touch_canvas_free_stick)
            ControlType.SWIPE -> context.getString(R.string.touch_canvas_swipe)
            else -> context.getString(R.string.touch_canvas_gesture)
        }
        val detail = if (control.type == ControlType.GESTURE) {
            type
        } else {
            context.getString(
                R.string.touch_canvas_area_label,
                type,
                stickName(control.stickTarget)
            )
        }
        val label = context.getString(R.string.touch_canvas_area_detail, control.name.ifBlank { type }, detail)
        chip(canvas, label, rect.x + rect.width / 2, rect.y + rect.height / 2, (rect.width - dp(12)).coerceAtLeast(1f), accent)
    }

    private fun drawAreaOverlay(canvas: Canvas, control: TouchControl, rect: TouchRect) {
        val opacity = (control.opacity * workspace.template.globalOpacity).coerceIn(0f, 1f)
        val color = control.color ?: ink
        if (control.showFill) {
            fill(color, opacity * 0.1f)
            shape(canvas, rect, false)
        }
        stroke(color, dp(1.5f), opacity)
        shape(canvas, rect, false)
        if (!control.showText) return
        val label = control.name.ifBlank {
            context.getString(
                when (control.type) {
                    ControlType.STICK -> R.string.touch_canvas_free_stick
                    ControlType.SWIPE -> R.string.touch_canvas_swipe
                    else -> R.string.touch_canvas_gesture
                }
            )
        }
        fill(color, opacity * 0.5f)
        text(canvas, label, rect.x + rect.width / 2, rect.y + rect.height / 2, sp(14), (rect.width - dp(16)).coerceAtLeast(1f))
    }

    private fun drawStick(
        canvas: Canvas,
        control: TouchControl,
        rect: TouchRect,
        pointers: List<PointerSnapshot>,
        snapshot: RuntimeSnapshot,
        opacity: Float,
        w: Float,
        h: Float
    ) {
        val p = pointers.firstOrNull { it.controlId == control.id }
        if (control.triggerOnly && (workspace.isEditing || workspace.testing)) drawArea(canvas, control, rect, p != null)
        if (control.triggerOnly && !workspace.isEditing && !workspace.testing && control.showBorder) {
            drawAreaOverlay(canvas, control, rect)
        }
        if (control.triggerOnly && p == null) return
        val radius = min(control.stickDiameter * resources.displayMetrics.density / 2f, min(w, h) / 2f)
        val vector = snapshot.sticks[control.stickTarget]
        val owns = snapshot.stickOwners[control.stickTarget] == control.id
        val vx = if (owns) vector?.x ?: 0f else 0f
        val vy = if (owns) vector?.y ?: 0f else 0f
        val knobOffsetX = vx * radius * 0.55f
        val knobOffsetY = -vy * radius * 0.55f
        val centerX = if (p != null) p.centerX else rect.x + rect.width / 2
        val centerY = if (p != null) p.centerY else rect.y + rect.height / 2
        val cx = centerX.coerceIn(min(radius, w / 2), (w - radius).coerceAtLeast(w / 2))
        val cy = centerY.coerceIn(min(radius, h / 2), (h - radius).coerceAtLeast(h / 2))
        val tint = control.color
        fill(tint?.let { ColorUtils.blendARGB(variant, it, 0.4f) } ?: variant, opacity * 0.7f)
        canvas.drawCircle(cx, cy, radius, paint)
        stroke(tint ?: outline, dp(1.5f), opacity)
        canvas.drawCircle(cx, cy, radius, paint)
        stroke(tint ?: ink, dp(0.8f), opacity * 0.17f)
        canvas.drawCircle(cx, cy, radius * 0.72f, paint)
        canvas.drawLine(cx - radius * 0.3f, cy, cx + radius * 0.3f, cy, paint)
        canvas.drawLine(cx, cy - radius * 0.3f, cx, cy + radius * 0.3f, paint)
        val knobRadius = radius * 0.34f
        val knobX = cx + knobOffsetX
        val knobY = cy + knobOffsetY
        fill(tint ?: if (p != null) primary else surface, opacity)
        canvas.drawCircle(knobX, knobY, knobRadius, paint)
        stroke(tint ?: if (p != null) primary else outline, dp(1.4f), opacity)
        canvas.drawCircle(knobX, knobY, knobRadius, paint)
        if (workspace.isEditing && !control.triggerOnly) {
            chip(canvas, stickName(control.stickTarget), cx, rect.bottom - dp(12), rect.width, outline)
        }
    }

    private fun drawSelection(canvas: Canvas, w: Float, h: Float) {
        val control = workspace.selected ?: return
        val rect = workspace.placements[control.id] ?: return
        val circle = control.type == ControlType.BUTTON && control.shape == ControlShape.CIRCLE ||
            control.type == ControlType.STICK && !control.isArea
        stroke(primary, dp(2.5f))
        shape(canvas, rect, circle)
        if (!control.locked && !control.isVisibleStick) {
            val corners = listOf(rect.x to rect.y, rect.right to rect.y, rect.right to rect.bottom, rect.x to rect.bottom)
            corners.forEachIndexed { index, (x, y) ->
                fill(surface)
                canvas.drawRoundRect(RectF(x - dp(6), y - dp(6), x + dp(6), y + dp(6)), dp(3), dp(3), paint)
                stroke(primary, dp(2))
                canvas.drawRoundRect(RectF(x - dp(6), y - dp(6), x + dp(6), y + dp(6)), dp(3), dp(3), paint)
                val sx = if (index == 0 || index == 3) 1 else -1
                val sy = if (index < 2) 1 else -1
                canvas.drawLine(x - sx * dp(2), y + sy * dp(2), x + sx * dp(2), y - sy * dp(2), paint)
            }
        }
        val label = context.getString(
            if (control.locked) R.string.touch_canvas_selected_locked else R.string.touch_canvas_selected,
            control.name.ifBlank { control.text }
        )
        val y = if (rect.bottom + dp(30) < h) rect.bottom + dp(20) else (rect.y - dp(20)).coerceAtLeast(dp(18))
        chip(canvas, label, (rect.x + rect.width / 2).coerceIn(dp(1), w.coerceAtLeast(dp(1))), y, (w - dp(24)).coerceAtLeast(1f), primary)
    }

    private fun drawHud(canvas: Canvas, snapshot: RuntimeSnapshot, w: Float, h: Float) {
        for (pointer in snapshot.pointers) {
            stroke(secondary, dp(1.5f))
            canvas.drawCircle(pointer.x, pointer.y, dp(11), paint)
            canvas.drawLine(pointer.centerX, pointer.centerY, pointer.x, pointer.y, paint)
            fill(secondary)
            canvas.drawCircle(pointer.x, pointer.y, dp(3), paint)
        }
        val state = context.getString(if (workspace.gameAvailable) R.string.touch_canvas_live else R.string.touch_canvas_local)
        val activity = if (snapshot.pointers.isEmpty()) {
            context.getString(R.string.touch_canvas_idle)
        } else {
            context.getString(R.string.touch_canvas_captured, snapshot.pointers.size)
        }
        val vectors = snapshot.sticks.entries.joinToString("   ") { (target, vector) ->
            context.getString(R.string.touch_canvas_vector, stickName(target), vector.x, vector.y)
        }
        val status = if (snapshot.saturatedControls.isNotEmpty()) {
            context.getString(R.string.touch_canvas_area_label, activity, context.getString(R.string.touch_canvas_saturated))
        } else {
            activity
        }
        chip(canvas, context.getString(R.string.touch_canvas_area_label, state, status), w / 2, h - dp(47), (w - dp(24)).coerceAtLeast(1f), secondary)
        chip(canvas, vectors, w / 2, h - dp(22), (w - dp(24)).coerceAtLeast(1f), outline)
        snapshot.effectiveTurboHz.values.minOrNull()?.let { frequency ->
            chip(canvas, context.getString(R.string.touch_canvas_turbo_effective, frequency), w / 2, h - dp(76), (w - dp(24)).coerceAtLeast(1f), outline)
        }
    }

    private fun chip(canvas: Canvas, value: String, x: Float, y: Float, maxWidth: Float, accent: Int) {
        if (maxWidth < dp(28)) return
        paint.typeface = labelTypeface
        paint.textSize = sp(12)
        val contentWidth = min(paint.measureText(value), (maxWidth - dp(18)).coerceAtLeast(1f))
        val width = min(contentWidth + dp(18), maxWidth)
        val height = maxOf(dp(25), paint.fontSpacing + dp(8))
        val cx = x.coerceIn(width / 2, (workspace.safe.width() - width / 2).coerceAtLeast(width / 2))
        val cy = y.coerceIn(height / 2, (workspace.safe.height() - height / 2).coerceAtLeast(height / 2))
        val box = RectF(cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2)
        fill(surface, 0.96f)
        canvas.drawRoundRect(box, dp(7), dp(7), paint)
        stroke(accent, dp(0.8f), 0.7f)
        canvas.drawRoundRect(box, dp(7), dp(7), paint)
        fill(ink)
        text(canvas, value, cx, cy, sp(12), (width - dp(14)).coerceAtLeast(1f))
    }

    private fun shape(canvas: Canvas, rect: TouchRect, circle: Boolean) {
        if (circle) {
            canvas.drawCircle(rect.x + rect.width / 2, rect.y + rect.height / 2, min(rect.width, rect.height) / 2, paint)
        } else {
            canvas.drawRoundRect(RectF(rect.x, rect.y, rect.right, rect.bottom), dp(8), dp(8), paint)
        }
    }

    private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float, maxWidth: Float) {
        paint.style = Paint.Style.FILL
        paint.pathEffect = null
        paint.typeface = labelTypeface
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = size
        val fitted = android.text.TextUtils.ellipsize(
            value,
            android.text.TextPaint(paint),
            maxWidth,
            android.text.TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(fitted, x, y - (paint.ascent() + paint.descent()) / 2, paint)
    }

    private fun buttonName(id: Int): String = listOf("A", "B", "X", "Y", "L3", "R3", "L", "R", "ZL", "ZR", "+", "−", "←", "↑", "→", "↓", "SL", "SR", "Home", "Capture", "SL", "SR").getOrNull(id) ?: id.toString()

    private fun stickName(target: StickTarget): String = context.getString(
        if (target == StickTarget.LEFT) R.string.touch_canvas_left_stick else R.string.touch_canvas_right_stick
    )

    private fun fill(color: Int, opacity: Float = 1f) {
        paint.style = Paint.Style.FILL
        paint.pathEffect = null
        paint.color = color
        paint.alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
    }

    private fun stroke(color: Int, width: Float, opacity: Float = 1f) {
        fill(color, opacity)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
    }

    private fun role(attribute: Int): Int = MaterialColors.getColor(this, attribute)
    private fun sp(value: Int): Float = value * resources.displayMetrics.scaledDensity
    private fun dp(value: Int): Float = workspace.dp(value)
    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

internal class SwipeCurveView(context: Context, private val preset: () -> SwipePreset) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        contentDescription = context.getString(R.string.touch_canvas_curve_accessibility)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = preset()
        val inset = 16f * resources.displayMetrics.density
        val w = width - inset * 2
        val h = height - inset * 2
        paint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density
        canvas.drawRect(inset, inset, inset + w, inset + h, paint)
        paint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        paint.strokeWidth *= 2
        val path = Path()
        for (step in 0..100) {
            val x = step / 100f
            val y = if (p.curve.isEmpty()) {
                x.pow(p.exponent)
            } else {
                val next = p.curve.indexOfFirst { it.input >= x }.coerceAtLeast(1)
                val a = p.curve[next - 1]
                val b = p.curve[next]
                a.output + (b.output - a.output) * ((x - a.input) / (b.input - a.input))
            }
            if (step == 0) path.moveTo(inset, inset + h) else path.lineTo(inset + x * w, inset + (1 - y) * h)
        }
        canvas.drawPath(path, paint)
    }
}
