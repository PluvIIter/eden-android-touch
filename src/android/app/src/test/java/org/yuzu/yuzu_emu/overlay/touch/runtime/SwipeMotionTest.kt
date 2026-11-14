// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yuzu.yuzu_emu.overlay.touch.model.CurvePoint
import org.yuzu.yuzu_emu.overlay.touch.model.SwipePreset

class SwipeMotionTest {
    private val preset = SwipePreset(deadzone = 0f, maximumSpeed = 1000f)

    @Test fun normalizedDiagonalsNeverExceedUnitMagnitude() {
        val motion = SwipeMotion(preset.copy(maximumSpeed = 1f), 1f, 1000f, 0)
        motion.move(800f, -800f, 8)
        val output = motion.tick(8, 8)
        assertEquals(0.70710677f, output.x, 0.0001f)
        assertEquals(0.70710677f, output.y, 0.0001f)
    }

    @Test fun diagonalMagnitudeIsStableAcrossTouchSampleRates() {
        for (step in listOf(8L, 9L, 11L, 16L, 17L)) {
            val motion = SwipeMotion(preset.copy(maximumSpeed = 1f), 1f, 1000f, 0)
            var nextTick = 8L
            var lastTick = 0L
            var minimum = 2.0
            var time = step
            while (time <= 400) {
                motion.move(8000f, -8000f, time)
                while (nextTick <= time) {
                    val output = motion.tick(nextTick, nextTick - lastTick)
                    val magnitude = kotlin.math.hypot(output.x, output.y)
                    if (magnitude > 0.1f) minimum = minOf(minimum, magnitude.toDouble())
                    lastTick = nextTick
                    nextTick += 8
                }
                time += step
            }
            assertTrue("step=$step minimum=$minimum", minimum > 0.65)
        }
    }

    @Test fun lowRateSampleKeepsItsVelocityInsteadOfDroppingToZero() {
        val motion = SwipeMotion(preset, 1f, 1000f, 0)
        motion.move(8000f, 0f, 32)
        for (time in listOf(32L, 40L, 48L, 56L)) {
            assertEquals(0.25f, motion.tick(time, 8).x, 0.0001f)
        }
    }

    @Test fun stopTimeoutEndsHeldMotion() {
        val motion = SwipeMotion(preset, 1f, 1000f, 0)
        motion.move(8000f, 0f, 8)
        assertEquals(1f, motion.tick(8, 8).x, 0.0001f)
        assertEquals(1f, motion.tick(16, 8).x, 0.0001f)
        assertEquals(0f, motion.tick(48, 8).x, 0.0001f)
    }

    @Test fun smoothingApproachesTargetOverTime() {
        val motion = SwipeMotion(preset.copy(smoothingMs = 100f), 1f, 1000f, 0)
        motion.move(800f, 0f, 8)
        val first = motion.tick(8, 8).x
        val second = motion.tick(16, 8).x
        assertTrue(first > 0f && first < 0.1f)
        assertTrue(second > first)
    }

    @Test fun turnSmoothingPreservesMagnitudeAcrossAxisSwitch() {
        val motion = SwipeMotion(preset.copy(smoothingMs = 0f, turnSmoothingMs = 30f), 1f, 1000f, 0)
        motion.move(8000f, 0f, 8)
        assertEquals(1f, motion.tick(8, 8).x, 0.0001f)
        motion.move(0f, -8000f, 16)
        var minimum = 2f
        for (time in 16L..48L step 8) {
            val output = motion.tick(time, 8)
            minimum = minOf(minimum, kotlin.math.hypot(output.x, output.y))
        }
        assertTrue("minimum=$minimum", minimum > 0.95f)
    }

    @Test fun partialTickStillAppliesDeadzoneAndCurve() {
        val motion = SwipeMotion(preset.copy(deadzone = 0.2f, exponent = 2f), 1f, 1000f, 0)
        motion.move(500f, 0f, 1)
        val output = motion.tick(8, 8).x
        assertTrue(output > 0.2f)
        assertEquals(0.76568544f, output, 0.0001f)
    }

    @Test fun axisTransformsAreAppliedToVelocity() {
        val motion = SwipeMotion(preset.copy(swapAxes = true, invertY = true), 1f, 1000f, 0)
        motion.move(0f, -4000f, 8)
        assertEquals(-0.5f, motion.tick(8, 8).x, 0.0001f)
        assertEquals(0f, motion.tick(8, 8).y, 0.0001f)
    }

    @Test fun customCurveIsAppliedToTheVectorMagnitude() {
        val curve = listOf(
            CurvePoint(0f, 0f),
            CurvePoint(0.5f, 0.25f),
            CurvePoint(1f, 1f)
        )
        val motion = SwipeMotion(preset.copy(deadzone = 0.2f, curve = curve), 1f, 1000f, 0)
        motion.move(20000f, 0f, 100)
        assertEquals(0.52f, motion.tick(100, 8).x, 0.0001f)
    }

    @Test fun resetAndUpdateClearHeldMotion() {
        val motion = SwipeMotion(preset, 1f, 1000f, 0)
        motion.move(8000f, 0f, 8)
        assertEquals(1f, motion.tick(8, 8).x, 0.0001f)
        motion.reset(8)
        assertEquals(0f, motion.tick(16, 8).x, 0.0001f)
        motion.move(8000f, 0f, 16)
        motion.update(preset.copy(sensitivityX = 2f), 16)
        assertEquals(1f, motion.tick(24, 8).x, 0.0001f)
        motion.move(16000f, 0f, 32)
        assertEquals(1f, motion.tick(40, 8).x, 0.0001f)
    }

    @Test fun illegalCurvesAndNonFiniteParametersAreRejected() {
        assertFalse(SwipeMotion.valid(preset.copy(sensitivityX = Float.POSITIVE_INFINITY)))
        assertFalse(SwipeMotion.valid(preset.copy(curve = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 0.5f)))))
        assertFalse(SwipeMotion.valid(preset.copy(stopTimeoutMs = 0)))
        assertFalse(SwipeMotion.valid(preset.copy(turnSmoothingMs = -1f)))
        assertTrue(SwipeMotion.valid(preset))
    }
}
