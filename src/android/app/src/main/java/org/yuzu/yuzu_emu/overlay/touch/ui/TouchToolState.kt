// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
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
    val beforePlacements: Map<String, TouchRect?> = emptyMap(),
    val beforePreset: SwipePreset? = null,
    val placementsOnly: Boolean = false
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
                        controls = if (placementsOnly) template.controls else template.controls.map { if (it.id == control.id) control else it },
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

internal data class TouchWindowChange(
    val templateId: String,
    val before: TouchWindowSnapshot,
    val after: TouchWindowSnapshot
) {
    private fun apply(library: TouchLibrary, from: TouchWindowSnapshot, to: TouchWindowSnapshot): TouchLibrary {
        val json = Json { encodeDefaults = true }
        fun control(current: TouchControl): TouchControl {
            val first = from.beforeControl ?: return current
            val target = to.beforeControl ?: return current
            if (to.placementsOnly) return current
            val a = json.encodeToJsonElement(first).jsonObject
            val b = json.encodeToJsonElement(target).jsonObject
            val value = json.encodeToJsonElement(current).jsonObject.toMutableMap()
            b.forEach { (key, next) -> if (a[key] != next) value[key] = next }
            return json.decodeFromJsonElement(JsonObject(value))
        }
        fun preset(current: SwipePreset): SwipePreset {
            val first = from.beforePreset ?: return current
            val target = to.beforePreset ?: return current
            val a = json.encodeToJsonElement(first).jsonObject
            val b = json.encodeToJsonElement(target).jsonObject
            val value = json.encodeToJsonElement(current).jsonObject.toMutableMap()
            b.forEach { (key, next) -> if (a[key] != next) value[key] = next }
            return json.decodeFromJsonElement(JsonObject(value))
        }
        return library.copy(
            presets = library.presets.map { if (it.id == to.presetId) preset(it) else it },
            templates = library.templates.map { template ->
                if (template.id != templateId) {
                    template
                } else {
                    template.copy(
                        controls = template.controls.map { if (it.id == to.controlId) control(it) else it },
                        screens = template.screens.map { screen ->
                            val id = to.controlId
                            val old = from.beforePlacements[screen.id]
                            val next = to.beforePlacements[screen.id]
                            if (id == null || old == next) {
                                screen
                            } else {
                                screen.copy(
                                    placements =
                                    if (next == null) screen.placements - id else screen.placements + (id to next)
                                )
                            }
                        }
                    )
                }
            }
        )
    }
    fun undo(library: TouchLibrary): TouchLibrary = apply(library, after, before)
    fun redo(library: TouchLibrary): TouchLibrary = apply(library, before, after)
    fun changes(library: TouchLibrary): Boolean = undo(library) != redo(library)
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
