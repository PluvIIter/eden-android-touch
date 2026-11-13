// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.ui

import kotlinx.serialization.Serializable
import org.yuzu.yuzu_emu.overlay.touch.model.*

@Serializable
internal data class TouchToolPosition(val x: Float = 0f, val y: Float = 0f) {
    fun normalized() = TouchToolPosition(
        if (x.isFinite()) x.coerceIn(0f, 1f) else 0f,
        if (y.isFinite()) y.coerceIn(0f, 1f) else 0f
    )
}

@Serializable
internal data class TouchWindowSnapshot(
    val key: String,
    val position: TouchToolPosition = TouchToolPosition(),
    val tab: Int = 0,
    val childIndex: Int? = null,
    val scroll: Int = 0,
    val controlId: String? = null,
    val presetId: String? = null,
    val beforeControl: TouchControl? = null,
    val beforePlacements: Map<String, TouchRect> = emptyMap(),
    val beforePreset: SwipePreset? = null
) {
    fun restore(library: TouchLibrary, templateId: String): TouchLibrary {
        if (beforePreset != null) {
            return library.copy(
                presets = library.presets.map {
                    if (it.id == beforePreset.id) beforePreset else it
                }
            )
        }
        val control = beforeControl ?: return library
        return library.copy(
            templates = library.templates.map { template ->
                if (template.id != templateId) {
                    template
                } else {
                    template.copy(
                        controls = template.controls.map { if (it.id == control.id) control else it },
                        screens = template.screens.map { screen ->
                            val rect = beforePlacements[screen.id]
                            screen.copy(
                                placements = if (rect == null) {
                                    screen.placements - control.id
                                } else {
                                    screen.placements + (control.id to rect)
                                }
                            )
                        }
                    )
                }
            }
        )
    }
}

@Serializable
internal data class TouchToolState(
    val toolbar: TouchToolPosition? = null,
    val opacity: Float = 0.94f,
    val windows: List<TouchWindowSnapshot> = emptyList()
) {
    fun normalized() = copy(
        toolbar = toolbar?.normalized(),
        opacity = if (opacity.isFinite()) opacity.coerceIn(0.4f, 1f) else 0.94f,
        windows = windows.distinctBy { it.key }.map {
            it.copy(
                position = it.position.normalized(),
                tab = it.tab.coerceIn(0, 2),
                childIndex = it.childIndex?.takeIf { index -> index in 0..3 },
                scroll = it.scroll.coerceAtLeast(0)
            )
        }
    )
}
