// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.ui

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.yuzu.yuzu_emu.overlay.touch.model.TouchDefaults

class TouchWorkspaceDraftTest {
    @Test
    fun snapshotRoundTripRetainsUnsavedDraftAndBaseline() {
        val saved = TouchDefaults.library().copy(revision = 12)
        val original = saved.templates.first()
        val draft = saved.copy(templates = listOf(original.copy(name = "Unsaved layout")))
        val state = TouchWorkspaceDraft(draft, saved, original.id, original.controls.first().id, true)
        val restored = Json.decodeFromString<TouchWorkspaceDraft>(Json.encodeToString(state))
        assertEquals(state, restored)
        assertEquals("Unsaved layout", restored.draft.templates.first().name)
        assertEquals(original.name, restored.saved.templates.first().name)
        restored.requireRestorable(12)
    }

    @Test
    fun staleBaselineIsRejectedBeforeRestore() {
        val saved = TouchDefaults.library().copy(revision = 4)
        val state = TouchWorkspaceDraft(saved, saved, saved.templates.first().id, null, true)
        assertThrows(IllegalArgumentException::class.java) { state.requireRestorable(5) }
    }

    @Test
    fun missingTemplateIsRejected() {
        val saved = TouchDefaults.library()
        val state = TouchWorkspaceDraft(saved, saved, "deleted", null, true)
        assertThrows(IllegalArgumentException::class.java) { state.requireRestorable(saved.revision) }
    }
}
