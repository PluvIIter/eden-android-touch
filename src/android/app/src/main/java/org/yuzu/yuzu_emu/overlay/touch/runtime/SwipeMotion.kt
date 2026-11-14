// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.runtime

import org.yuzu.yuzu_emu.overlay.touch.model.MotionScale
import org.yuzu.yuzu_emu.overlay.touch.model.SwipePreset
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

/**
 * Continuous-time swipe response.
 *
 * Touch samples establish a target velocity; ticks smooth that velocity in real time. This keeps
 * the output independent of the Android touch sample rate (60/90/120 Hz or jittered batches)
 * instead of consuming discrete per-event segments on a fixed 8 ms grid.
 */
internal class SwipeMotion(
    var preset: SwipePreset,
    private val density: Float,
    private val shortEdge: Float,
    timeMs: Long
) {
    private var targetX = 0.0
    private var targetY = 0.0
    private var velocityX = 0.0
    private var velocityY = 0.0
    private var lastEvent = timeMs
    private var lastMotion = timeMs
    private var output = StickVector()
    var saturated = false
        private set

    fun reset(timeMs: Long) {
        targetX = 0.0
        targetY = 0.0
        velocityX = 0.0
        velocityY = 0.0
        lastEvent = timeMs
        lastMotion = timeMs
        output = StickVector()
        saturated = false
    }

    fun move(dx: Float, dy: Float, timeMs: Long) {
        val duration = (timeMs - lastEvent).coerceAtLeast(1).toDouble()
        lastEvent = timeMs
        if (dx == 0f && dy == 0f) return
        lastMotion = timeMs
        val scale = if (preset.scale == MotionScale.DP) density else shortEdge
        var x = dx.toDouble() / scale * preset.sensitivityX * if (preset.invertX) -1 else 1
        var y = -dy.toDouble() / scale * preset.sensitivityY * if (preset.invertY) -1 else 1
        if (preset.swapAxes) {
            val swap = x
            x = y
            y = swap
        }
        var vx = x * 1000.0 / duration / preset.maximumSpeed
        var vy = y * 1000.0 / duration / preset.maximumSpeed
        val speed = hypot(vx, vy)
        saturated = speed > 1.0
        if (speed > 1.0) {
            vx /= speed
            vy /= speed
        }
        targetX = vx
        targetY = vy
    }

    fun tick(timeMs: Long, elapsedMs: Long): StickVector {
        require(elapsedMs > 0)
        if (timeMs - lastMotion >= preset.stopTimeoutMs) {
            targetX = 0.0
            targetY = 0.0
            velocityX = 0.0
            velocityY = 0.0
            saturated = false
            output = ZERO
            return output
        }
        val alpha = if (preset.smoothingMs <= 0f) {
            1.0
        } else {
            1.0 - exp(-elapsedMs / preset.smoothingMs.toDouble())
        }
        velocityX += (targetX - velocityX) * alpha
        velocityY += (targetY - velocityY) * alpha
        val magnitude = hypot(velocityX, velocityY)
        if (magnitude <= EPSILON) {
            output = ZERO
            return output
        }
        val compensated = compensate(min(magnitude, 1.0).toFloat(), preset)
        output = StickVector(
            (velocityX / magnitude * compensated).toFloat(),
            (velocityY / magnitude * compensated).toFloat()
        )
        return output
    }

    fun update(updated: SwipePreset, timeMs: Long): Boolean {
        val incompatible = preset.scale != updated.scale || preset.invertX != updated.invertX ||
            preset.invertY != updated.invertY || preset.swapAxes != updated.swapAxes
        preset = updated
        val needsReset = incompatible || timeMs - lastMotion >= updated.stopTimeoutMs
        if (needsReset) reset(timeMs)
        return needsReset
    }

    private fun compensate(speed: Float, preset: SwipePreset): Float {
        if (speed <= 0f) return 0f
        val response = if (preset.curve.isEmpty()) {
            speed.pow(1f / preset.exponent)
        } else {
            val points = preset.curve
            val upper = points.indexOfFirst { it.output >= speed }
            when {
                upper < 0 -> 1f
                upper == 0 -> points[0].input
                else -> {
                    val a = points[upper - 1]
                    val b = points[upper]
                    val t = if (b.output == a.output) 0f else (speed - a.output) / (b.output - a.output)
                    a.input + t * (b.input - a.input)
                }
            }
        }
        return preset.deadzone + (1f - preset.deadzone) * response
    }

    companion object {
        private val ZERO = StickVector()
        private const val EPSILON = 0.000000001
        fun valid(preset: SwipePreset): Boolean = with(preset) {
            sensitivityX.isFinite() && sensitivityY.isFinite() && sensitivityX > 0f && sensitivityY > 0f &&
                deadzone.isFinite() && deadzone >= 0f && deadzone < 1f &&
                exponent.isFinite() && exponent > 0f && maximumSpeed.isFinite() && maximumSpeed > 0f &&
                smoothingMs.isFinite() && smoothingMs in 0f..10000f && stopTimeoutMs in 1..10000 &&
                curve.all { it.input.isFinite() && it.output.isFinite() && it.input in 0f..1f && it.output in 0f..1f } &&
                (
                    curve.isEmpty() || (
                        curve.size >= 2 && curve.first().input == 0f && curve.first().output == 0f &&
                            curve.last().input == 1f && curve.last().output == 1f && curve.zipWithNext().all {
                                (a, b) ->
                            a.input < b.input && a.output <= b.output
                        }
                        )
                    )
        }
    }
}
