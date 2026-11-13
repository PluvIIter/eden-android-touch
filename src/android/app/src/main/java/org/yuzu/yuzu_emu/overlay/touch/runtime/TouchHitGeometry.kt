// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.runtime

import org.yuzu.yuzu_emu.overlay.touch.model.ControlShape
import org.yuzu.yuzu_emu.overlay.touch.model.ControlType
import org.yuzu.yuzu_emu.overlay.touch.model.GestureDirection
import org.yuzu.yuzu_emu.overlay.touch.model.GroupStyle
import org.yuzu.yuzu_emu.overlay.touch.model.TouchControl
import org.yuzu.yuzu_emu.overlay.touch.model.TouchRect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min

object TouchHitGeometry {
    fun faceKeys(control: TouchControl, bounds: TouchRect): List<TouchRect> {
        val size = min(bounds.width, bounds.height) / 3f
        val cx = bounds.x + bounds.width / 2f
        val cy = bounds.y + bounds.height / 2f
        return listOf(
            TouchRect(cx - size / 2f, bounds.y, size, size),
            TouchRect(bounds.right - size, cy - size / 2f, size, size),
            TouchRect(cx - size / 2f, bounds.bottom - size, size, size),
            TouchRect(bounds.x, cy - size / 2f, size, size)
        ).take(control.groupKeys.size)
    }

    fun circleContains(bounds: TouchRect, x: Float, y: Float): Boolean {
        val radius = min(bounds.width, bounds.height) / 2f
        val dx = x - bounds.x - bounds.width / 2f
        val dy = y - bounds.y - bounds.height / 2f
        return dx * dx + dy * dy <= radius * radius
    }

    fun keyAt(control: TouchControl, bounds: TouchRect, x: Float, y: Float): Int? =
        faceKeys(control, bounds).indexOfFirst { circleContains(it, x, y) }.takeIf { it >= 0 }

    fun contains(control: TouchControl, bounds: TouchRect, x: Float, y: Float): Boolean = when {
        control.isArea -> bounds.contains(x, y)
        control.type == ControlType.BUTTON_GROUP && control.groupStyle == GroupStyle.FACE ->
            keyAt(control, bounds, x, y) != null
        control.type == ControlType.BUTTON_GROUP -> bounds.contains(x, y)
        control.type == ControlType.STICK || control.shape == ControlShape.CIRCLE -> circleContains(bounds, x, y)
        else -> bounds.contains(x, y)
    }

    fun dpadKeys(bounds: TouchRect, x: Float, y: Float): Set<Int> {
        val dx = (x - bounds.x - bounds.width / 2f) / (bounds.width / 2f)
        val dy = (y - bounds.y - bounds.height / 2f) / (bounds.height / 2f)
        if (dx * dx + dy * dy <= 0.04f) return emptySet()
        return buildSet {
            if (abs(dy) >= abs(dx) * 0.41421357f) add(if (dy < 0f) 0 else 2)
            if (abs(dx) >= abs(dy) * 0.41421357f) add(if (dx > 0f) 1 else 3)
        }
    }

    internal fun direction(dx: Float, dy: Float, diagonals: Boolean): GestureDirection {
        val directions = if (diagonals) {
            listOf(
                GestureDirection.RIGHT,
                GestureDirection.DOWN_RIGHT,
                GestureDirection.DOWN,
                GestureDirection.DOWN_LEFT,
                GestureDirection.LEFT,
                GestureDirection.UP_LEFT,
                GestureDirection.UP,
                GestureDirection.UP_RIGHT
            )
        } else {
            listOf(GestureDirection.RIGHT, GestureDirection.DOWN, GestureDirection.LEFT, GestureDirection.UP)
        }
        val step = 2.0 * PI / directions.size
        val index = kotlin.math.floor((atan2(dy.toDouble(), dx.toDouble()) + step / 2) / step).toInt()
        return directions[(index % directions.size + directions.size) % directions.size]
    }

    internal fun vector(direction: GestureDirection): StickVector = when (direction) {
        GestureDirection.UP -> StickVector(0f, 1f)
        GestureDirection.UP_RIGHT -> StickVector(0.70710677f, 0.70710677f)
        GestureDirection.RIGHT -> StickVector(1f, 0f)
        GestureDirection.DOWN_RIGHT -> StickVector(0.70710677f, -0.70710677f)
        GestureDirection.DOWN -> StickVector(0f, -1f)
        GestureDirection.DOWN_LEFT -> StickVector(-0.70710677f, -0.70710677f)
        GestureDirection.LEFT -> StickVector(-1f, 0f)
        GestureDirection.UP_LEFT -> StickVector(-0.70710677f, 0.70710677f)
        GestureDirection.TAP -> StickVector()
    }
}
