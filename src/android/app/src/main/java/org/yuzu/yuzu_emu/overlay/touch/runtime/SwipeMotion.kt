// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.runtime

import org.yuzu.yuzu_emu.overlay.touch.model.MotionScale
import org.yuzu.yuzu_emu.overlay.touch.model.SwipePreset
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

internal class SwipeMotion(
    var preset: SwipePreset,
    private val density: Float,
    private val shortEdge: Float,
    timeMs: Long
) {
    private data class Segment(var duration: Double, val vx: Double, val vy: Double, val preset: SwipePreset)

    private val pending = ArrayDeque<Segment>()
    private var lastEvent = timeMs
    private var lastMotion = timeMs
    private var output = StickVector()
    var saturated = false
        private set

    fun reset(timeMs: Long) {
        pending.clear()
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
        val retained = min(duration, preset.maxAccumulationMs.toDouble())
        pending.addLast(Segment(retained, x * 1000.0 / retained, y * 1000.0 / retained, preset))
        var excess = pending.sumOf { it.duration } - preset.maxAccumulationMs
        saturated = false
        while (excess > 0.000001 && pending.isNotEmpty()) {
            val first = pending.first()
            val removed = min(excess, first.duration)
            first.duration -= removed
            excess -= removed
            saturated = true
            if (first.duration <= 0.000001) pending.removeFirst()
        }
    }

    fun tick(timeMs: Long, elapsedMs: Long): StickVector {
        if (timeMs - lastMotion >= preset.stopTimeoutMs) {
            pending.clear()
            output = StickVector()
            return output
        }
        var remaining = elapsedMs.toDouble()
        var x = 0.0
        var y = 0.0
        val responses = mutableMapOf<SwipePreset, Double>()
        while (remaining > 0 && pending.isNotEmpty()) {
            val segment = pending.first()
            val duration = min(remaining, segment.duration)
            val vx = segment.vx / segment.preset.maximumSpeed
            val vy = segment.vy / segment.preset.maximumSpeed
            x += vx * duration / elapsedMs
            y += vy * duration / elapsedMs
            val weight = hypot(vx, vy) * duration
            responses[segment.preset] = (responses[segment.preset] ?: 0.0) + weight
            remaining -= duration
            segment.duration -= duration
            if (segment.duration <= 0.000001) pending.removeFirst()
        }
        val magnitude = hypot(x, y)
        saturated = saturated || magnitude > 1.0
        val target = if (magnitude == 0.0) {
            StickVector()
        } else {
            // Compensate after resampling, or short event fragments fall back below the game deadzone.
            val weight = responses.values.sum()
            val amplitude = responses.entries.sumOf { (snapshot, share) ->
                compensate(min(magnitude, 1.0).toFloat(), snapshot) * share / weight
            }
            StickVector((x / magnitude * amplitude).toFloat(), (y / magnitude * amplitude).toFloat())
        }
        val alpha = if (preset.smoothingMs == 0f) {
            1f
        } else {
            (1.0 - exp(-elapsedMs / preset.smoothingMs.toDouble())).toFloat()
        }
        output = StickVector(
            output.x + (target.x - output.x) * alpha,
            output.y + (target.y - output.y) * alpha
        )
        return output
    }

    fun update(updated: SwipePreset, timeMs: Long): Boolean {
        val incompatible = preset.scale != updated.scale || preset.invertX != updated.invertX ||
            preset.invertY != updated.invertY || preset.swapAxes != updated.swapAxes
        preset = updated
        val stationary = pending.isEmpty() && timeMs - lastMotion >= updated.stopTimeoutMs
        if (incompatible || stationary) reset(timeMs)
        return incompatible || stationary
    }

    private fun compensate(speed: Float, preset: SwipePreset): Float {
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
        fun valid(preset: SwipePreset): Boolean = with(preset) {
            sensitivityX.isFinite() && sensitivityY.isFinite() && sensitivityX > 0f && sensitivityY > 0f &&
                deadzone.isFinite() && deadzone >= 0f && deadzone < 1f &&
                exponent.isFinite() && exponent > 0f && maximumSpeed.isFinite() && maximumSpeed > 0f &&
                smoothingMs.isFinite() && smoothingMs in 0f..10000f && stopTimeoutMs in 1..10000 && maxAccumulationMs in 1..1000 &&
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
