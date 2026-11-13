// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import org.yuzu.yuzu_emu.overlay.touch.model.ControlShape
import org.yuzu.yuzu_emu.overlay.touch.model.ControlType
import org.yuzu.yuzu_emu.overlay.touch.model.GroupStyle
import org.yuzu.yuzu_emu.overlay.touch.model.SwipePreset
import org.yuzu.yuzu_emu.overlay.touch.model.TouchControl
import org.yuzu.yuzu_emu.overlay.touch.model.TouchRect
import org.yuzu.yuzu_emu.overlay.touch.runtime.TouchHitGeometry
import kotlin.math.min
import kotlin.math.pow

internal class TouchCanvas(context: Context, private val workspace: TouchWorkspace) : View(context) {
    var preview: TouchControl? = null
    var feedback: String? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val secondary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondary)
    private val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val error = MaterialColors.getColor(this, com.google.android.material.R.attr.colorError)

    init {
        isClickable = true
        contentDescription = "Touch layout canvas. Use the Controls list for precise accessible selection and geometry."
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = workspace.onCanvasTouch(event)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val safe = workspace.safe
        canvas.save()
        canvas.translate(safe.left, safe.top)
        val w = safe.width()
        val h = safe.height()
        if (!workspace.gameAvailable) {
            canvas.drawColor(surface)
            paint.color = ink
            paint.alpha = 22
            paint.strokeWidth = workspace.dp(1)
            var x = 0f
            while (x < w) { canvas.drawLine(x, 0f, x, h, paint); x += workspace.dp(24) }
            var y = 0f
            while (y < h) { canvas.drawLine(0f, y, w, y, paint); y += workspace.dp(24) }
        }
        val snapshot = workspace.runtime.snapshot()
        val controls = workspace.template.controls.sortedBy { if (it.isArea) 0 else 1 }
        for (control in controls) {
            if (!workspace.controlsVisible && !workspace.isEditing && !workspace.testing) continue
            if (workspace.isolatedId != null && workspace.isolatedId != control.id) continue
            if (!workspace.isEditing && !control.enabled) continue
            val rect = workspace.placements[control.id] ?: control.bounds
            val pointers = snapshot.pointers.filter { it.controlId == control.id }
            val active = pointers.isNotEmpty()
            paint.alpha = (control.opacity * 255).toInt()
            if (workspace.isEditing) paint.alpha = paint.alpha.coerceAtLeast(75)
            paint.color = if (active) primary else ink
            paint.alpha = (control.opacity * 255).toInt().let { if (workspace.isEditing) it.coerceAtLeast(75) else it }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = workspace.dp(2)
            when (control.type) {
                ControlType.BUTTON -> {
                    shape(canvas, rect, control.shape == ControlShape.CIRCLE)
                    text(
                        canvas,
                        control.text,
                        rect.x + rect.width / 2,
                        rect.y + rect.height / 2,
                        min(workspace.dp(18), rect.height / 2)
                    )
                }
                ControlType.BUTTON_GROUP -> {
                    val keys = TouchHitGeometry.faceKeys(control, rect)
                    for ((index, key) in keys.withIndex()) {
                        paint.style = if (pointers.any { index in it.activeKeys }) Paint.Style.FILL else Paint.Style.STROKE
                        shape(canvas, key, control.groupStyle == GroupStyle.FACE)
                        val color = paint.color
                        if (paint.style == Paint.Style.FILL) {
                            paint.color = MaterialColors.getColor(
                                this,
                                com.google.android.material.R.attr.colorOnPrimary
                            )
                        }
                        text(
                            canvas,
                            control.groupKeys.getOrNull(index)?.text.orEmpty(),
                            key.x + key.width / 2,
                            key.y + key.height / 2,
                            min(workspace.dp(16), key.height / 2)
                        )
                        paint.color = color
                    }
                    paint.style = Paint.Style.STROKE
                }
                ControlType.STICK -> {
                    if (control.triggerOnly && !active) {
                        if (workspace.isEditing) {
                            shape(canvas, rect, false)
                            text(
                                canvas,
                                "${control.name} · free stick area",
                                rect.x + rect.width / 2,
                                rect.y + rect.height / 2,
                                workspace.dp(13)
                            )
                        }
                    } else {
                        val p = pointers.firstOrNull()
                        val radius = if (control.triggerOnly) {
                            min(
                                control.stickDiameter * resources.displayMetrics.density / 2,
                                min(w, h) / 2
                            )
                        } else {
                            min(rect.width, rect.height) / 2
                        }
                        val logicalX = if (control.freeStick && p != null) p.centerX else rect.x + rect.width / 2
                        val logicalY = if (control.freeStick && p != null) p.centerY else rect.y + rect.height / 2
                        val cx = logicalX.coerceIn(min(radius, w / 2), (w - radius).coerceAtLeast(w / 2))
                        val cy = logicalY.coerceIn(min(radius, h / 2), (h - radius).coerceAtLeast(h / 2))
                        canvas.drawCircle(cx, cy, radius, paint)
                        val vector = snapshot.sticks[control.stickTarget]
                        val owns = snapshot.stickOwners[control.stickTarget] == control.id
                        val vx = if (owns) vector?.x ?: 0f else 0f
                        val vy = if (owns) vector?.y ?: 0f else 0f
                        canvas.drawCircle(
                            cx + vx * radius * 0.55f,
                            cy - vy * radius * 0.55f,
                            radius * 0.34f,
                            paint
                        )
                    }
                }
                ControlType.SWIPE, ControlType.GESTURE -> {
                    if (workspace.isEditing || workspace.testing) {
                        paint.color = if (control.id == workspace.selectedId) primary else secondary
                        paint.alpha = 180
                        shape(canvas, rect, false)
                        val suffix = if (control.type == ControlType.SWIPE) control.stickTarget.name else "gestures"
                        text(
                            canvas,
                            "${control.name} · $suffix",
                            rect.x + rect.width / 2,
                            rect.y + workspace.dp(20),
                            workspace.dp(13)
                        )
                    }
                }
            }
            if (workspace.isEditing && !control.enabled) {
                paint.color = error
                paint.alpha = 255
                canvas.drawLine(rect.x, rect.y, rect.right, rect.bottom, paint)
            }
        }
        preview?.let {
            paint.color = primary
            paint.alpha = 200
            paint.style = Paint.Style.STROKE
            shape(canvas, it.bounds, !it.isArea && it.shape == ControlShape.CIRCLE)
        }
        if (workspace.isEditing) {
            workspace.selected?.let { control ->
                workspace.placements[control.id]?.let { r ->
                    paint.color = primary
                    paint.alpha = 255
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = workspace.dp(2)
                    shape(canvas, r, false)
                    if (!control.locked) {
                        paint.style = Paint.Style.FILL
                        for ((x, y) in listOf(r.x to r.y, r.right to r.y, r.right to r.bottom, r.x to r.bottom)) {
                            canvas.drawCircle(x, y, workspace.dp(5), paint)
                        }
                    }
                }
            }
        }
        if (workspace.testing) {
            for (p in snapshot.pointers) {
                paint.color = secondary
                paint.alpha = 255
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(p.x, p.y, workspace.dp(12), paint)
                canvas.drawLine(p.centerX, p.centerY, p.x, p.y, paint)
            }
            val vectors = snapshot.sticks.entries.joinToString("  ") { (target, vector) ->
                "${target.name}: %.2f, %.2f".format(vector.x, vector.y)
            }
            val state = if (workspace.gameAvailable) "LIVE · draft not saved" else "LOCAL TEST · not game camera feedback"
            paint.color = ink
            paint.alpha = 255
            text(canvas, state, w / 2, h - workspace.dp(42), workspace.dp(13))
            text(
                canvas,
                vectors + if (snapshot.saturatedControls.isNotEmpty()) " · SATURATED" else "",
                w / 2,
                h - workspace.dp(22),
                workspace.dp(12)
            )
        }
        feedback?.let {
            paint.color = error
            paint.alpha = 255
            text(canvas, it, w / 2, workspace.dp(24), workspace.dp(14))
        }
        canvas.restore()
    }

    private fun shape(canvas: Canvas, rect: TouchRect, circle: Boolean) {
        if (circle) {
            canvas.drawCircle(
                rect.x + rect.width / 2,
                rect.y + rect.height / 2,
                min(rect.width, rect.height) / 2,
                paint
            )
        } else {
            canvas.drawRoundRect(
                RectF(rect.x, rect.y, rect.right, rect.bottom),
                workspace.dp(8),
                workspace.dp(8),
                paint
            )
        }
    }

    private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float) {
        val style = paint.style
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = size
        canvas.drawText(value, x, y - (paint.ascent() + paint.descent()) / 2, paint)
        paint.style = style
    }
}

internal class SwipeCurveView(context: Context, private val preset: () -> SwipePreset) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
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
