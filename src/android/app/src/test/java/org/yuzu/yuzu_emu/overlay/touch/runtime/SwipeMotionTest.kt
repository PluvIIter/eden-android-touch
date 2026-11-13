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
    private val preset = SwipePreset(deadzone = 0f, maximumSpeed = 1f)

    @Test fun customCurveIsInvertedBeforeDeadzoneCompensation() {
        val motion = SwipeMotion(
            preset.copy(
                deadzone = 0.2f,
                curve = listOf(
                    CurvePoint(0f, 0f),
                    CurvePoint(0.5f, 0.25f),
                    CurvePoint(1f, 1f)
                )
            ),
            1f,
            1000f,
            0
        )
        motion.move(2f, 0f, 8)
        assertEquals(0.6f, motion.tick(8, 8).x, 0.0001f)
    }

    @Test fun largeFlingHasBoundedQueueAndNoStationaryDrift() {
        val motion = SwipeMotion(preset.copy(maxAccumulationMs = 16), 1f, 1000f, 0)
        motion.move(10000f, 0f, 8)
        assertTrue(motion.tick(8, 8).x <= 1f)
        assertTrue(motion.saturated)
        assertEquals(0f, motion.tick(16, 8).x)
        assertEquals(0f, motion.tick(24, 8).x)
    }

    @Test fun smoothingStopsAtHardTimeoutAndResetAlwaysClears() {
        val motion = SwipeMotion(preset.copy(smoothingMs = 100f, stopTimeoutMs = 24), 1f, 1000f, 0)
        motion.move(8f, 0f, 8)
        val first = motion.tick(8, 8).x
        assertTrue(first > 0f && first < 1f)
        assertTrue(motion.tick(16, 8).x < first)
        assertEquals(StickVector(), motion.tick(32, 8))
        motion.move(8f, 0f, 40)
        motion.reset(40)
        assertEquals(StickVector(), motion.tick(48, 8))
    }

    @Test fun normalizedDiagonalsNeverExceedUnitMagnitude() {
        val motion = SwipeMotion(preset, 1f, 1000f, 0)
        motion.move(800f, -800f, 8)
        val output = motion.tick(8, 8)
        assertEquals(0.70710677f, output.x, 0.0001f)
        assertEquals(0.70710677f, output.y, 0.0001f)
    }

    @Test fun positiveUpAndAllAxisTransformsAreDeterministic() {
        val motion = SwipeMotion(preset.copy(swapAxes = true, invertY = true), 1f, 1000f, 0)
        motion.move(0f, -4f, 8)
        assertEquals(StickVector(-0.5f, 0f), motion.tick(8, 8))
    }

    @Test fun lowRateEventRetainsItsDistanceWithinBoundedConsumptionWindow() {
        val motion = SwipeMotion(preset, 1f, 1000f, 0)
        motion.move(8f, 0f, 32)
        val first = motion.tick(32, 8).x
        val second = motion.tick(40, 8).x
        assertEquals(8f, (first + second) * 8f, 0.0001f)
        assertEquals(0f, motion.tick(48, 8).x)
    }

    @Test fun partialTickMotionStillCompensatesAboveGameDeadzone() {
        val motion = SwipeMotion(preset.copy(deadzone = 0.2f, exponent = 2f), 1f, 1000f, 0)
        motion.move(0.5f, 0f, 1)
        val output = motion.tick(8, 8).x
        assertTrue(output > 0.2f)
        assertEquals(0.0625f, ((output - 0.2f) / 0.8f).let { it * it }, 0.00001f)
    }

    @Test fun calibratedResponseIntegralIsInvariantAcrossInterleavedSamplingRates() {
        for (exponent in listOf(1f, 2f, 0.5f)) {
            val integrals = listOf(1, 4, 8, 16, 32).map { interval ->
                val motion = SwipeMotion(preset.copy(deadzone = 0.2f, exponent = exponent, stopTimeoutMs = 80), 1f, 1000f, 0)
                var integral = 0.0
                for (time in 1..192) {
                    if (time <= 128 && time % interval == 0) motion.move(interval * 0.125f, 0f, time.toLong())
                    if (time % 8 == 0) {
                        val output = motion.tick(time.toLong(), 8).x
                        if (output > 0.2f) integral += Math.pow(((output - 0.2f) / 0.8f).toDouble(), exponent.toDouble()) * 8.0
                    }
                }
                integral
            }
            for (integral in integrals) assertEquals(16.0, integral, 0.0001)
        }
    }

    @Test fun oppositeSubframeMotionCancelsWithoutDeadzonePhantomVector() {
        val motion = SwipeMotion(preset.copy(deadzone = 0.2f), 1f, 1000f, 0)
        motion.move(2f, 0f, 4)
        motion.move(-2f, 0f, 8)
        assertEquals(StickVector(), motion.tick(8, 8))
    }

    @Test fun illegalCurvesAndNonFiniteParametersAreRejected() {
        assertFalse(SwipeMotion.valid(preset.copy(sensitivityX = Float.POSITIVE_INFINITY)))
        assertFalse(SwipeMotion.valid(preset.copy(curve = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 0.5f)))))
        assertFalse(SwipeMotion.valid(preset.copy(curve = listOf(CurvePoint(0f, 0f), CurvePoint(0f, 0.5f), CurvePoint(1f, 1f)))))
        assertFalse(SwipeMotion.valid(preset.copy(maxAccumulationMs = 0)))
        assertTrue(SwipeMotion.valid(preset))
    }
}
