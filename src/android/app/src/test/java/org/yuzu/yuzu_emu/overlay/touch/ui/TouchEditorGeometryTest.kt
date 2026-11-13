// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yuzu.yuzu_emu.overlay.touch.model.ControlType
import org.yuzu.yuzu_emu.overlay.touch.model.ScreenLayout
import org.yuzu.yuzu_emu.overlay.touch.model.TouchControl
import org.yuzu.yuzu_emu.overlay.touch.model.TouchGeometry
import org.yuzu.yuzu_emu.overlay.touch.model.TouchRect
import org.yuzu.yuzu_emu.overlay.touch.model.TouchTemplate

class TouchEditorGeometryTest {
    private val a = TouchControl(
        name = "A",
        type = ControlType.SWIPE,
        bounds = TouchRect(0f, 0f, 50f, 100f)
    )
    private val b = TouchControl(
        name = "B",
        type = ControlType.GESTURE,
        bounds = TouchRect(50f, 0f, 50f, 100f)
    )

    @Test
    fun finitePositiveBoundsRequired() {
        assertTrue(TouchEditorGeometry.fits(TouchRect(0f, 0f, 100f, 100f), 100f, 100f))
        assertFalse(TouchEditorGeometry.fits(TouchRect(Float.NaN, 0f, 1f, 1f), 100f, 100f))
        assertFalse(TouchEditorGeometry.fits(TouchRect(0f, 0f, 0f, 1f), 100f, 100f))
        assertFalse(TouchEditorGeometry.fits(TouchRect(-1f, 0f, 1f, 1f), 100f, 100f))
        assertFalse(TouchEditorGeometry.fits(TouchRect(99f, 0f, 2f, 1f), 100f, 100f))
    }

    @Test
    fun subpixelRoundingToleranceDoesNotPermitVisibleOverflow() {
        assertTrue(TouchEditorGeometry.fits(TouchRect(0f, 0f, 100.005f, 100f), 100f, 100f))
        assertFalse(TouchEditorGeometry.fits(TouchRect(0f, 0f, 100.1f, 100f), 100f, 100f))
    }

    @Test
    fun sharedAreaEdgesAreAllowed() {
        assertNull(TouchEditorGeometry.conflict(a, a.bounds, listOf(a, b), emptyMap()))
    }

    @Test
    fun disabledTransparentAreasStillConflict() {
        val hidden = b.copy(enabled = false, opacity = 0f)
        assertEquals(
            b.id,
            TouchEditorGeometry.conflict(
                a,
                a.bounds.copy(width = 51f),
                listOf(a, hidden),
                emptyMap()
            )?.id
        )
    }

    @Test
    fun buttonsCanOverlayAreas() {
        val button = b.copy(type = ControlType.BUTTON)
        assertNull(TouchEditorGeometry.conflict(button, a.bounds, listOf(a, button), emptyMap()))
    }

    @Test
    fun currentPlacementWinsOverBaseBounds() {
        assertEquals(
            b.id,
            TouchEditorGeometry.conflict(
                a,
                a.bounds,
                listOf(a, b),
                mapOf(b.id to TouchRect(25f, 0f, 50f, 100f))
            )?.id
        )
    }

    @Test
    fun letterboxMappingPreservesCirclesAndContainment() {
        val mapped = TouchEditorGeometry.map(TouchRect(0f, 0f, 100f, 100f), 100f, 100f, 200f, 100f)
        assertEquals(TouchRect(50f, 0f, 100f, 100f), mapped)
        assertTrue(TouchEditorGeometry.fits(mapped, 200f, 100f))
    }

    @Test
    fun repeatedScreenRoundTripsDoNotDrift() {
        val template = TouchTemplate(
            controls = listOf(a, b),
            screens = listOf(ScreenLayout(width = 100f, height = 100f))
        )
        val initial = TouchGeometry.placements(template, 100f, 100f)
        repeat(20) {
            assertTrue(
                TouchGeometry.placements(template, 200f, 100f).values.all {
                    TouchEditorGeometry.fits(it, 200f, 100f)
                }
            )
            assertEquals(initial, TouchGeometry.placements(template, 100f, 100f))
        }
    }
}
