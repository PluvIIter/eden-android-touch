// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.ui

import org.yuzu.yuzu_emu.overlay.touch.model.TouchControl
import org.yuzu.yuzu_emu.overlay.touch.model.TouchRect
import kotlin.math.min

internal object TouchEditorGeometry {
    fun fits(rect: TouchRect, width: Float, height: Float): Boolean =
        listOf(rect.x, rect.y, rect.width, rect.height).all { it.isFinite() } &&
            rect.x >= 0 && rect.y >= 0 && rect.width > 0 && rect.height > 0 &&
            rect.right <= width + 0.01f && rect.bottom <= height + 0.01f

    fun conflict(
        control: TouchControl,
        rect: TouchRect,
        controls: List<TouchControl>,
        placements: Map<String, TouchRect>
    ): TouchControl? = controls.firstOrNull {
        it.id != control.id && it.isArea && control.isArea &&
            (placements[it.id] ?: it.bounds).intersects(rect)
    }

    fun map(
        rect: TouchRect,
        sourceWidth: Float,
        sourceHeight: Float,
        targetWidth: Float,
        targetHeight: Float
    ): TouchRect {
        val scale = min(targetWidth / sourceWidth, targetHeight / sourceHeight)
        return TouchRect(
            (targetWidth - sourceWidth * scale) / 2 + rect.x * scale,
            (targetHeight - sourceHeight * scale) / 2 + rect.y * scale,
            rect.width * scale,
            rect.height * scale
        )
    }
}
