// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.ui

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.yuzu.yuzu_emu.overlay.touch.model.*

class TouchToolStateTest {
    @Test
    fun independentWindowsRoundTripTheirLocalSnapshots() {
        val library = TouchDefaults.library()
        val control = library.templates.first().controls.first()
        val state = TouchToolState(
            TouchToolPosition(0.3f, 0.7f),
            0.65f,
            listOf(
                TouchWindowSnapshot(
                    "control:${control.id}",
                    TouchToolPosition(0.9f, 0.1f),
                    2,
                    3,
                    180,
                    controlId = control.id,
                    beforeControl = control
                ),
                TouchWindowSnapshot("preset:camera", presetId = library.presets.first().id, beforePreset = library.presets.first())
            )
        )
        assertEquals(state, Json.decodeFromString<TouchToolState>(Json.encodeToString(state)))
        assertEquals(state, state.normalized())
    }

    @Test
    fun untrustedWindowGeometryIsNormalized() {
        val state = TouchToolState(
            TouchToolPosition(-2f, Float.NaN),
            Float.NaN,
            listOf(TouchWindowSnapshot("a", TouchToolPosition(2f, 0.5f), 99, 8, -1))
        ).normalized()
        assertEquals(TouchToolPosition(0f, 0f), state.toolbar)
        assertEquals(TouchToolPosition(1f, 0.5f), state.windows.first().position)
        assertEquals(0.94f, state.opacity, 0f)
        assertEquals(2, state.windows.first().tab)
        assertNull(state.windows.first().childIndex)
        assertEquals(0, state.windows.first().scroll)
    }

    @Test
    fun cancelControlDoesNotRollbackAnotherControlOrPreset() {
        val library = TouchDefaults.library()
        val template = library.templates.first()
        val first = template.controls[0]
        val second = template.controls[1]
        val snapshot = TouchWindowSnapshot("control:${first.id}", controlId = first.id, beforeControl = first)
        val changed = library.copy(
            templates = listOf(
                template.copy(
                    controls = template.controls.map {
                        when (it.id) { first.id -> it.copy(name = "Changed A"); second.id -> it.copy(name = "Changed B"); else -> it }
                    }
                )
            ),
            presets = library.presets.map { it.copy(sensitivityX = 3f) }
        )
        val restored = snapshot.restore(changed, template.id)
        assertEquals(first, restored.templates.first().controls[0])
        assertEquals("Changed B", restored.templates.first().controls[1].name)
        assertEquals(3f, restored.presets.first().sensitivityX, 0f)
    }

    @Test
    fun cancelPresetDoesNotRollbackLayoutAndOtherPreset() {
        val library = TouchDefaults.library()
        val template = library.templates.first()
        val preset = library.presets.first()
        val other = SwipePreset(name = "Other")
        val snapshot = TouchWindowSnapshot("preset:${preset.id}", presetId = preset.id, beforePreset = preset)
        val changed = library.copy(
            templates = listOf(template.copy(name = "Changed layout")),
            presets = listOf(preset.copy(sensitivityX = 4f), other)
        )
        val restored = snapshot.restore(changed, template.id)
        assertEquals(preset, restored.presets.first())
        assertEquals(other, restored.presets[1])
        assertEquals("Changed layout", restored.templates.first().name)
    }

    @Test
    fun localCancelAfterRecoveryUsesOriginalWindowSnapshot() {
        val library = TouchDefaults.library()
        val template = library.templates.first()
        val original = template.controls.first()
        val changed = library.copy(templates = listOf(template.copy(controls = template.controls.map { it.copy(opacity = 0.2f) })))
        val tools = TouchToolState(
            windows = listOf(
                TouchWindowSnapshot(
                    "control:${original.id}",
                    controlId = original.id,
                    beforeControl = original
                )
            )
        )
        val state = TouchWorkspaceDraft(changed, library, template.id, original.id, true, tools, library.presets.first().id)
        val restored = Json.decodeFromString<TouchWorkspaceDraft>(Json.encodeToString(state))
        assertEquals(original, restored.tools.windows.first().restore(restored.draft, template.id).templates.first().controls.first())
        assertEquals(library.presets.first().id, restored.standalonePresetId)
    }

    @Test
    fun snapshotWithoutToolPreferencesUsesDefaults() {
        val saved = TouchDefaults.library()
        val snapshot = TouchWorkspaceDraft(saved, saved, saved.templates.first().id, null, true)
        val text = Json.encodeToString(snapshot)
        assertFalse(text.contains("\"tools\""))
        assertEquals(TouchToolState(), Json.decodeFromString<TouchWorkspaceDraft>(text).tools)
    }
}
