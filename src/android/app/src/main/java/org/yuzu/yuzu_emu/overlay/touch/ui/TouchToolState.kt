// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.ui

import kotlinx.serialization.Serializable

@Serializable
internal data class TouchToolPosition(val x: Float = 0f, val y: Float = 0f) {
    fun normalized(): TouchToolPosition = TouchToolPosition(
        if (x.isFinite()) x.coerceIn(0f, 1f) else 0f,
        if (y.isFinite()) y.coerceIn(0f, 1f) else 0f
    )
}

@Serializable
internal data class TouchToolState(
    val toolbar: TouchToolPosition? = null,
    val panel: TouchToolPosition? = null,
    val opacity: Float = 0.94f,
    val toolbarCollapsed: Boolean = false,
    val panelCollapsed: Boolean = false,
    val propertyTab: Int = 0,
    val childIndex: Int? = null,
    val propertyId: String? = null,
    val scrollPositions: Map<String, Int> = emptyMap(),
    val activePanel: String? = null
) {
    fun normalized(): TouchToolState = copy(
        toolbar = toolbar?.normalized(),
        panel = panel?.normalized(),
        opacity = if (opacity.isFinite()) opacity.coerceIn(0.4f, 1f) else 0.94f,
        propertyTab = propertyTab.coerceIn(0, 2),
        childIndex = childIndex?.takeIf { it in 0..3 },
        scrollPositions = scrollPositions.mapValues { it.value.coerceAtLeast(0) }
    )
}
