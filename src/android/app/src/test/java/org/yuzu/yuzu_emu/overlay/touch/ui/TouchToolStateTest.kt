// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.ui

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.yuzu.yuzu_emu.overlay.touch.model.TouchDefaults

class TouchToolStateTest {
    @Test
    fun positionsOpacityFoldingAndScrollRoundTrip() {
        val state = TouchToolState(
            TouchToolPosition(0.3f, 0.7f), TouchToolPosition(0.9f, 0.1f),
            0.65f, true, true, 2, 3, "control", mapOf("control:2:3" to 180), "control:control:2:3"
        )
        val restored = Json.decodeFromString<TouchToolState>(Json.encodeToString(state))
        assertEquals(state, restored)
        assertEquals(state, restored.normalized())
    }

    @Test
    fun untrustedToolCoordinatesAndOpacityAreNormalized() {
        val state = TouchToolState(
            TouchToolPosition(-2f, Float.NaN),
            TouchToolPosition(2f, 0.5f),
            Float.NaN,
            propertyTab = 99,
            childIndex = 8,
            scrollPositions = mapOf("a" to -1)
        ).normalized()
        assertEquals(TouchToolPosition(0f, 0f), state.toolbar)
        assertEquals(TouchToolPosition(1f, 0.5f), state.panel)
        assertEquals(0.94f, state.opacity, 0f)
        assertEquals(2, state.propertyTab)
        assertNull(state.childIndex)
        assertEquals(0, state.scrollPositions["a"])
    }

    @Test
    fun workspaceSnapshotIncludesToolPreferencesWithoutChangingLibrary() {
        val saved = TouchDefaults.library()
        val tools = TouchToolState(toolbar = TouchToolPosition(0.6f, 0.2f), toolbarCollapsed = true)
        val snapshot = TouchWorkspaceDraft(saved, saved, saved.templates.first().id, null, true, tools)
        val restored = Json.decodeFromString<TouchWorkspaceDraft>(Json.encodeToString(snapshot))
        assertEquals(tools, restored.tools)
        assertEquals(saved, restored.draft)
        assertEquals(saved, restored.saved)
    }

    @Test
    fun olderSnapshotWithoutToolPreferencesUsesDefaults() {
        val saved = TouchDefaults.library()
        val snapshot = TouchWorkspaceDraft(saved, saved, saved.templates.first().id, null, true)
        val text = Json.encodeToString(snapshot)
        assertFalse(text.contains("\"tools\""))
        assertEquals(TouchToolState(), Json.decodeFromString<TouchWorkspaceDraft>(text).tools)
    }
}
