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

    @Test fun lowRateSampleKeepsItsTrueVelocityRatherThanCompressingHistoryIntoBacklogCap() {
        val motion = SwipeMotion(preset, 1f, 1000f, 0)
        motion.move(8f, 0f, 32)
        var integral = 0f
        for (time in listOf(32L, 40L, 48L, 56L)) {
            val output = motion.tick(time, 8).x
            assertEquals(0.25f, output, 0.0001f)
            integral += output * 8f
        }
        assertEquals(8f, integral, 0.0001f)
        assertEquals(0f, motion.tick(64, 8).x)
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

    private fun responseIntegral(interval: Int, speed: Float, smoothing: Float, exponent: Float, duration: Int): Double {
        val motion = SwipeMotion(preset.copy(smoothingMs = smoothing, exponent = exponent), 1f, 1000f, 0)
        var integral = 0.0
        for (time in 1..duration + 128) {
            if (time <= duration && time % interval == 0) motion.move(interval * speed, 0f, time.toLong())
            if (time % 8 == 0) integral += Math.pow(motion.tick(time.toLong(), 8).x.toDouble(), exponent.toDouble()) * 8
        }
        return integral
    }

    @Test fun nearMaximumUnsaturatedMotionDoesNotLoseDistanceAtLowSamplingRates() {
        for (interval in listOf(1, 4, 8, 16, 32)) {
            for (smoothing in listOf(0f, 32f, 100f)) {
                assertEquals(96.0, responseIntegral(interval, 0.75f, smoothing, 1f, 128), 0.0001)
            }
        }
    }

    @Test fun smoothingConservesRequestedDistanceBeforeNonlinearCompensation() {
        for (smoothing in listOf(0f, 32f, 100f)) {
            for (exponent in listOf(0.5f, 1f, 2f, 3f)) {
                assertEquals(8.0, responseIntegral(8, 0.5f, smoothing, exponent, 16), 0.0001)
                assertEquals(8.0, responseIntegral(8, 0.0625f, smoothing, exponent, 128), 0.0001)
            }
        }
    }

    @Test fun smoothedCustomResponseConservesDistanceWithoutAxisRotation() {
        val points = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.25f), CurvePoint(1f, 1f))
        val motion = SwipeMotion(preset.copy(smoothingMs = 32f, deadzone = 0.2f, curve = points), 1f, 1000f, 0)
        var integral = 0.0
        for (time in 1..160) {
            if (time <= 16 && time % 8 == 0) motion.move(0f, -4f, time.toLong())
            if (time % 8 == 0) {
                val vector = motion.tick(time.toLong(), 8)
                assertEquals(0f, vector.x)
                assertTrue(vector.y >= 0f)
                val native = ((vector.y - 0.2f) / 0.8f).coerceAtLeast(0f)
                val forward = if (native <= 0.5f) native * 0.5f else 0.25f + (native - 0.5f) * 1.5f
                integral += forward * 8
            }
        }
        assertEquals(8.0, integral, 0.0001)
    }

    @Test fun finiteStopDeadlineDiscardsPhysicallyImpossibleTailInsteadOfRunningForever() {
        val motion = SwipeMotion(preset.copy(smoothingMs = 10000f, stopTimeoutMs = 16, maxAccumulationMs = 16), 1f, 1000f, 0)
        motion.move(10000f, 0f, 8)
        for (time in 8L..16L step 8) assertTrue(motion.tick(time, 8).x in 0f..1f)
        assertEquals(StickVector(), motion.tick(24, 8))
        assertEquals(StickVector(), motion.tick(32, 8))
    }

    @Test fun updateUsesNewSnapshotOnlyForSubsequentMotionAndKeepsHistoricalResponse() {
        val motion = SwipeMotion(preset, 1f, 1000f, 0)
        motion.move(8f, 0f, 16)
        assertEquals(0.5f, motion.tick(16, 8).x)
        motion.update(preset.copy(sensitivityX = 2f, exponent = 2f), 16)
        assertEquals(0.5f, motion.tick(24, 8).x)
        motion.move(4f, 0f, 24)
        assertEquals(1f, motion.tick(32, 8).x)
    }

    @Test fun illegalCurvesAndNonFiniteParametersAreRejected() {
        assertFalse(SwipeMotion.valid(preset.copy(sensitivityX = Float.POSITIVE_INFINITY)))
        assertFalse(SwipeMotion.valid(preset.copy(curve = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 0.5f)))))
        assertFalse(SwipeMotion.valid(preset.copy(curve = listOf(CurvePoint(0f, 0f), CurvePoint(0f, 0.5f), CurvePoint(1f, 1f)))))
        assertFalse(SwipeMotion.valid(preset.copy(maxAccumulationMs = 0)))
        assertTrue(SwipeMotion.valid(preset))
    }
}
