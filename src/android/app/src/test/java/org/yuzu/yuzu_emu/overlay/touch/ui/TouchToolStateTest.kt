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
    fun scopedUndoDoesNotResurrectConcurrentWindowEdits() {
        val original = TouchDefaults.library()
        val template = original.templates.first()
        val a = template.controls[0]
        val b = template.controls[1]
        val beforeA = TouchWindowSnapshot("A", controlId = a.id, beforeControl = a)
        val beforeB = TouchWindowSnapshot("B", controlId = b.id, beforeControl = b)
        val afterA = beforeA.copy(beforeControl = a.copy(name = "Changed A"))
        val afterB = beforeB.copy(beforeControl = b.copy(name = "Changed B"))
        val changeA = TouchWindowChange(template.id, beforeA, afterA)
        val changeB = TouchWindowChange(template.id, beforeB, afterB)
        val changed = changeB.redo(changeA.redo(original))
        assertEquals(original, changeA.undo(changeB.undo(changed)))
        assertEquals(changed, changeB.redo(changeA.redo(original)))
    }

    @Test
    fun scopedUndoPreservesOtherFieldsOnTheSameControl() {
        val original = TouchDefaults.library()
        val template = original.templates.first()
        val control = template.controls.first()
        val before = TouchWindowSnapshot("A", controlId = control.id, beforeControl = control)
        val change = TouchWindowChange(template.id, before, before.copy(beforeControl = control.copy(name = "Edited")))
        val externallyLocked = original.copy(
            templates = listOf(
                template.copy(
                    controls = template.controls.map {
                        if (it.id == control.id) it.copy(locked = true) else it
                    }
                )
            )
        )
        val restored = change.undo(change.redo(externallyLocked))
        assertEquals(externallyLocked, restored)
    }

    @Test
    fun noEditSnapshotPreservesSparsePlacements() {
        val original = TouchDefaults.library()
        val template = original.templates.first()
        val control = template.controls.first()
        val snapshot = TouchWindowSnapshot(
            "A",
            controlId = control.id,
            beforeControl = control,
            beforePlacements = template.screens.associate { it.id to it.placements[control.id] }
        )
        assertEquals(original, snapshot.restore(original, template.id))
        assertFalse(TouchWindowChange(template.id, snapshot, snapshot).changes(original))
    }

    @Test
    fun consecutiveGeometryUndoPreservesAttributesAndIndividualSteps() {
        val original = TouchDefaults.library()
        val template = original.templates.first()
        val control = template.controls.first()
        val screen = template.screens.first()
        val before = TouchWindowSnapshot(
            "geometry",
            controlId = control.id,
            beforeControl = control,
            beforePlacements = mapOf(screen.id to null),
            placementsOnly = true
        )
        val first = before.copy(beforePlacements = mapOf(screen.id to control.bounds.copy(x = 100f)))
        val second = before.copy(beforePlacements = mapOf(screen.id to control.bounds.copy(x = 120f)))
        val step1 = TouchWindowChange(template.id, before, first)
        val step2 = TouchWindowChange(template.id, first, second)
        val edited = original.copy(
            templates = listOf(
                template.copy(
                    controls = template.controls.map {
                        if (it.id == control.id) it.copy(name = "Unsaved field") else it
                    }
                )
            )
        )
        val moved = step2.redo(step1.redo(edited))
        assertEquals(first.beforePlacements[screen.id], step2.undo(moved).templates.first().screens.first().placements[control.id])
        assertEquals(edited, step1.undo(step2.undo(moved)))
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
