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
    private class Response(val preset: SwipePreset) {
        var queued = 0
        var inputX = 0.0
        var inputY = 0.0
        var remainingX = 0.0
        var remainingY = 0.0
        var velocityX = 0.0
        var velocityY = 0.0
        var emittedX = 0.0
        var emittedY = 0.0
    }
    private data class Segment(var duration: Double, val vx: Double, val vy: Double, val response: Response)

    private val pending = ArrayDeque<Segment>()
    private val responses = ArrayList<Response>()
    private var response = Response(preset).also { responses.add(it) }
    private var pendingMs = 0.0
    private var lastEvent = timeMs
    private var lastMotion = timeMs
    private var output = StickVector()
    var saturated = false
        private set

    fun reset(timeMs: Long) {
        pending.clear()
        pendingMs = 0.0
        responses.clear()
        response = Response(preset).also { responses.add(it) }
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
        pending.addLast(Segment(duration, vx, vy, response))
        response.queued++
        pendingMs += duration
        // A sample's elapsed history is not future backlog and must not be compressed into the cap.
        var excess = pendingMs - maxOf(duration, preset.maxAccumulationMs.toDouble())
        while (excess > EPSILON && pending.isNotEmpty()) {
            val first = pending.first()
            val removed = min(excess, first.duration)
            first.duration -= removed
            pendingMs -= removed
            excess -= removed
            saturated = true
            if (first.duration <= EPSILON) pending.removeFirst().response.queued--
        }
    }

    fun tick(timeMs: Long, elapsedMs: Long): StickVector {
        require(elapsedMs > 0)
        if (pending.isEmpty() && responses.all {
            hypot(it.remainingX, it.remainingY) <= EPSILON &&
                hypot(it.velocityX, it.velocityY) <= EPSILON
        }
        ) {
            output = ZERO
            return output
        }
        if (timeMs - lastMotion >= preset.stopTimeoutMs) {
            clearMotion()
            return output
        }
        var remaining = elapsedMs.toDouble()
        while (remaining > EPSILON && pending.isNotEmpty()) {
            val segment = pending.first()
            val duration = min(remaining, segment.duration)
            segment.response.inputX += segment.vx * duration
            segment.response.inputY += segment.vy * duration
            remaining -= duration
            segment.duration -= duration
            pendingMs -= duration
            if (segment.duration <= EPSILON) pending.removeFirst().response.queued--
        }
        val finalFrame = timeMs + elapsedMs - lastMotion >= preset.stopTimeoutMs
        if (finalFrame) {
            while (pending.isNotEmpty()) {
                val segment = pending.removeFirst()
                segment.response.queued--
                segment.response.inputX += segment.vx * segment.duration
                segment.response.inputY += segment.vy * segment.duration
            }
            pendingMs = 0.0
        }
        var x = 0.0
        var y = 0.0
        for (state in responses) {
            state.remainingX += state.inputX
            state.remainingY += state.inputY
            val alpha = if (state.preset.smoothingMs == 0f) {
                1.0
            } else {
                1.0 - exp(-elapsedMs / state.preset.smoothingMs.toDouble())
            }
            state.velocityX += (state.inputX / elapsedMs - state.velocityX) * alpha
            state.velocityY += (state.inputY / elapsedMs - state.velocityY) * alpha
            // Limit filter latency without throwing away an otherwise achievable request.
            val horizon = maxOf(
                elapsedMs.toDouble(),
                minOf(
                    state.preset.maxAccumulationMs.toDouble(),
                    (lastMotion + preset.stopTimeoutMs - timeMs).toDouble()
                )
            )
            val release = elapsedMs / horizon
            state.emittedX = state.remainingX * release / elapsedMs
            state.emittedY = state.remainingY * release / elapsedMs
            if (!finalFrame && hypot(state.velocityX, state.velocityY) > hypot(state.emittedX, state.emittedY)) {
                state.emittedX = state.velocityX
                state.emittedY = state.velocityY
            }
            val debtLength = hypot(state.remainingX, state.remainingY)
            val emittedLength = hypot(state.emittedX, state.emittedY)
            if (finalFrame || emittedLength * elapsedMs >= debtLength ||
                state.emittedX * state.remainingX + state.emittedY * state.remainingY <= 0.0
            ) {
                state.emittedX = state.remainingX / elapsedMs
                state.emittedY = state.remainingY / elapsedMs
            }
            x += state.emittedX
            y += state.emittedY
            state.inputX = 0.0
            state.inputY = 0.0
        }
        val magnitude = hypot(x, y)
        saturated = saturated || magnitude > 1.0
        var amplitude = 0.0
        var weight = 0.0
        for (state in responses) {
            val share = hypot(state.emittedX, state.emittedY)
            amplitude += compensate(min(magnitude, 1.0).toFloat(), state.preset) * share
            weight += share
            // Saturated excess is discarded rather than returned to the smoothing accumulator.
            state.remainingX -= state.emittedX * elapsedMs
            state.remainingY -= state.emittedY * elapsedMs
            val debt = hypot(state.remainingX, state.remainingY)
            if (debt > state.preset.maxAccumulationMs) {
                val bounded = state.preset.maxAccumulationMs / debt
                state.remainingX *= bounded
                state.remainingY *= bounded
                saturated = true
            }
        }
        output = if (magnitude <= EPSILON || weight <= EPSILON) {
            ZERO
        } else {
            val compensated = amplitude / weight
            StickVector(
                (x / magnitude * compensated).toFloat(),
                (y / magnitude * compensated).toFloat()
            )
        }
        if (finalFrame) {
            responses.forEach {
                it.remainingX = 0.0
                it.remainingY = 0.0
                it.velocityX = 0.0
                it.velocityY = 0.0
            }
        }
        pruneResponses()
        return output
    }

    private fun pruneResponses() {
        val iterator = responses.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next()
            if (state !== response && state.queued == 0 &&
                hypot(state.remainingX, state.remainingY) <= EPSILON &&
                hypot(state.velocityX, state.velocityY) <= EPSILON
            ) {
                iterator.remove()
            }
        }
    }

    private fun clearMotion() {
        pending.clear()
        pendingMs = 0.0
        responses.forEach {
            it.queued = 0
            it.inputX = 0.0
            it.inputY = 0.0
            it.remainingX = 0.0
            it.remainingY = 0.0
            it.velocityX = 0.0
            it.velocityY = 0.0
        }
        pruneResponses()
        output = ZERO
    }

    fun update(updated: SwipePreset, timeMs: Long): Boolean {
        val incompatible = preset.scale != updated.scale || preset.invertX != updated.invertX ||
            preset.invertY != updated.invertY || preset.swapAxes != updated.swapAxes
        preset = updated
        val stationary = pending.isEmpty() && timeMs - lastMotion >= updated.stopTimeoutMs
        if (incompatible || stationary) {
            reset(timeMs)
        } else {
            response = Response(updated).also { responses.add(it) }
            pruneResponses()
        }
        return incompatible || stationary
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
