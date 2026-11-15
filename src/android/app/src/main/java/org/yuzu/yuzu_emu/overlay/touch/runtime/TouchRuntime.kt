// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.runtime

import org.yuzu.yuzu_emu.overlay.touch.model.AuxiliaryMode
import org.yuzu.yuzu_emu.overlay.touch.model.ButtonAction
import org.yuzu.yuzu_emu.overlay.touch.model.ButtonBinding
import org.yuzu.yuzu_emu.overlay.touch.model.ControlType
import org.yuzu.yuzu_emu.overlay.touch.model.GestureBinding
import org.yuzu.yuzu_emu.overlay.touch.model.GestureDirection
import org.yuzu.yuzu_emu.overlay.touch.model.GestureTiming
import org.yuzu.yuzu_emu.overlay.touch.model.GroupStyle
import org.yuzu.yuzu_emu.overlay.touch.model.SwipePreset
import org.yuzu.yuzu_emu.overlay.touch.model.TouchControl
import org.yuzu.yuzu_emu.overlay.touch.model.TouchRect
import org.yuzu.yuzu_emu.overlay.touch.model.TouchTemplate
import kotlin.math.hypot
import kotlin.math.min

class TouchRuntime(private val sink: InputSink) {
    private data class Capture(
        val pointerId: Int,
        val control: TouchControl?,
        val bounds: TouchRect,
        val keyIndex: Int?,
        val nativeTouch: Boolean,
        val startX: Float,
        val startY: Float,
        val started: Long,
        var centerX: Float,
        var centerY: Float,
        var x: Float,
        var y: Float,
        var maxDistance: Float = 0f,
        var gesture: GestureDirection? = null,
        var activeKeys: Set<Int> = emptySet(),
        var motion: SwipeMotion? = null,
        var doubleTap: Boolean = false
    ) {
        val source: String = "pointer:$pointerId"
    }
    private class Repeater(val binding: ButtonBinding, val started: Long) {
        val halfPeriodMs = 500.0 / binding.frequencyHz
        var nextEdgeMs = started + halfPeriodMs
        var pressed = true
        var lastEdgeMs = started
        var effectiveFrequencyHz = binding.frequencyHz
    }
    private data class Tap(val x: Float, val y: Float, val released: Long)

    private val aggregate = InputAggregator(sink)
    private val captures = mutableMapOf<Int, Capture>()
    private val repeaters = mutableMapOf<String, Repeater>()
    private val pulses = mutableMapOf<String, Long>()
    private val pulseOwners = mutableMapOf<String, String>()
    private val taps = mutableMapOf<String, Tap>()
    private var controls: List<TouchControl> = emptyList()
    private var hitOrder: List<TouchControl> = emptyList()
    private var placements: Map<String, TouchRect> = emptyMap()
    private var presets: Map<String, SwipePreset> = emptyMap()
    private var width = 1f
    private var height = 1f
    private var density = 1f
    private var touchscreenEnabled = true
    private var isolatedSwipe: String? = null
    private var currentTime = 0L
    private var tickTime: Long? = null
    private var pulseSerial = 0L

    @Synchronized
    fun configure(
        template: TouchTemplate,
        placements: Map<String, TouchRect>,
        presets: List<SwipePreset>,
        screenWidth: Float,
        screenHeight: Float,
        density: Float
    ) {
        require(screenWidth.isFinite() && screenWidth > 0f && screenHeight.isFinite() && screenHeight > 0f)
        require(density.isFinite() && density > 0f)
        require(presets.all(SwipeMotion::valid)) { "Invalid swipe preset" }
        require(presets.map { it.id }.distinct().size == presets.size) { "Duplicate preset ID" }
        val copied = template.controls.map(::copyControl)
        require(copied.map { it.id }.distinct().size == copied.size) { "Duplicate control ID" }
        val bounds = copied.associate { it.id to (placements[it.id] ?: it.bounds) }
        copied.forEach {
            val rect = bounds.getValue(it.id)
            validate(it, rect, presets)
            require(rect.x >= -0.01f && rect.y >= -0.01f && rect.right <= screenWidth + 0.01f && rect.bottom <= screenHeight + 0.01f) {
                "Control outside screen"
            }
        }
        // Areas may overlap each other; the later area in the control list wins the hit test.
        cancelAll()
        controls = copied
        val enabled = copied.asReversed().filter { it.enabled }
        hitOrder = enabled.filterNot { it.isArea } + enabled.filter { it.isArea }
        this.placements = bounds
        this.presets = presets.associate { it.id to it.copy(curve = it.curve.toList()) }
        width = screenWidth
        height = screenHeight
        this.density = density
        isolatedSwipe = null
    }

    @Synchronized
    fun setTouchscreenEnabled(enabled: Boolean) {
        if (touchscreenEnabled == enabled) return
        cancelAll()
        touchscreenEnabled = enabled
    }

    @Synchronized
    fun setIsolatedSwipe(controlId: String?) {
        require(controlId == null || controls.any { it.id == controlId && it.type == ControlType.SWIPE && it.enabled })
        if (isolatedSwipe == controlId) return
        cancelAll()
        isolatedSwipe = controlId
    }

    @Synchronized
    fun updatePresets(presets: List<SwipePreset>) {
        require(presets.all(SwipeMotion::valid)) { "Invalid swipe preset" }
        require(presets.map { it.id }.distinct().size == presets.size) { "Duplicate preset ID" }
        val updated = presets.associate { it.id to it.copy(curve = it.curve.toList()) }
        require(controls.filter { it.enabled && it.type == ControlType.SWIPE }.all { it.swipePresetId in updated })
        for (capture in captures.values) {
            val motion = capture.motion ?: continue
            val preset = updated.getValue(capture.control!!.swipePresetId!!)
            if (motion.preset != preset) {
                if (motion.update(preset, currentTime)) aggregate.vector(capture.source, StickVector())
            }
        }
        this.presets = updated
        aggregate.flush()
    }

    @Synchronized
    fun down(pointerId: Int, x: Float, y: Float, timeMs: Long) {
        if (!x.isFinite() || !y.isFinite() || pointerId in captures) return
        val time = eventTime(timeMs)
        if (tickTime == null) tickTime = time
        val control = hitOrder.firstOrNull {
            (isolatedSwipe == null || it.id == isolatedSwipe) &&
                TouchHitGeometry.contains(it, placements.getValue(it.id), x, y)
        }
        val bounds = control?.let { placements.getValue(it.id) } ?: TouchRect()
        val key = if (control?.type == ControlType.BUTTON_GROUP && control.groupStyle == GroupStyle.FACE) {
            TouchHitGeometry.keyAt(control, bounds, x, y)
        } else {
            null
        }
        val occupied = control != null && captures.values.any {
            it.control?.id == control.id && (key == null || it.keyIndex == key)
        }
        val captured = if (occupied) null else control
        val native = control == null && touchscreenEnabled && isolatedSwipe == null
        val isStick = captured?.type == ControlType.STICK
        val capture = Capture(
            pointerId, captured, bounds, key, native, x, y, time,
            if (isStick) x else bounds.x + bounds.width / 2f,
            if (isStick) y else bounds.y + bounds.height / 2f, x, y
        )
        captures[pointerId] = capture
        when (captured?.type) {
            ControlType.BUTTON -> {
                if (captured.haptics) sink.haptic(captured)
                when (captured.buttonAction) {
                    ButtonAction.TOGGLE_VISIBILITY -> sink.toggleVisibility()
                    ButtonAction.SWITCH_TEMPLATE -> captured.targetTemplateId?.let(sink::switchTemplate)
                    ButtonAction.NONE -> activate(capture.source, captured.binding, time)
                }
            }
            ControlType.BUTTON_GROUP -> updateGroup(capture, time)
            ControlType.STICK -> {
                val tap = taps.remove(captured.id)
                capture.doubleTap = captured.auxiliaryMode == AuxiliaryMode.DOUBLE_TAP && tap != null &&
                    time - tap.released in 0..captured.tapTimeoutMs && hypot(x - tap.x, y - tap.y) <= captured.tapSlop * density
                if (captured.haptics) sink.haptic(captured, capture.doubleTap)
                aggregate.acquire(capture.source, captured.stickTarget)
                updateStick(capture)
            }
            ControlType.SWIPE -> {
                capture.motion = SwipeMotion(presets.getValue(captured.swipePresetId!!), density, min(width, height), time)
                aggregate.acquire(capture.source, captured.stickTarget)
            }
            ControlType.GESTURE -> Unit
            ControlType.VISIBILITY -> {
                if (captured.haptics) sink.haptic(captured)
                sink.toggleVisibility()
            }
            null -> if (native) sink.touchDown(pointerId, x, y)
        }
        aggregate.flush()
    }

    @Synchronized
    fun move(pointerId: Int, x: Float, y: Float, timeMs: Long) {
        if (!x.isFinite() || !y.isFinite()) return
        val capture = captures[pointerId] ?: return
        updatePosition(capture, x, y, eventTime(timeMs))
        aggregate.flush()
    }

    private fun updatePosition(capture: Capture, x: Float, y: Float, time: Long) {
        val dx = (x.toDouble() - capture.x).coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat()
        val dy = (y.toDouble() - capture.y).coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat()
        capture.x = x
        capture.y = y
        capture.maxDistance = maxOf(capture.maxDistance, hypot(x - capture.startX, y - capture.startY))
        when (capture.control?.type) {
            ControlType.BUTTON_GROUP -> if (capture.control.groupStyle == GroupStyle.DPAD) updateGroup(capture, time)
            ControlType.STICK -> updateStick(capture)
            ControlType.SWIPE -> capture.motion!!.move(dx, dy, time)
            ControlType.GESTURE -> recognize(capture)
            else -> if (capture.nativeTouch) sink.touchMove(capture.pointerId, x, y)
        }
    }

    @Synchronized
    fun up(pointerId: Int, x: Float, y: Float, timeMs: Long) {
        val capture = captures[pointerId] ?: return
        val time = eventTime(timeMs)
        if (x.isFinite() && y.isFinite()) updatePosition(capture, x, y, time)
        val control = capture.control
        if (control?.type == ControlType.GESTURE) {
            val gesture = capture.gesture
            if (gesture != null && control.gestureTiming == GestureTiming.RELEASE) {
                if (control.haptics) sink.haptic(control)
                pulse(control.id, control.gestures[gesture], control.gesturePulseMs, time)
            } else if (gesture == null && time - capture.started <= control.tapTimeoutMs &&
                capture.maxDistance <= control.tapSlop * density
            ) {
                if (control.haptics) sink.haptic(control)
                pulse(control.id, control.gestures[GestureDirection.TAP], control.gesturePulseMs, time)
            }
        }
        if (control?.type == ControlType.STICK && !capture.doubleTap &&
            time - capture.started <= control.tapTimeoutMs && capture.maxDistance <= control.tapSlop * density
        ) {
            taps[control.id] = Tap(capture.startX, capture.startY, time)
        }
        release(capture)
        aggregate.flush()
    }

    @Synchronized
    fun tick(timeMs: Long) {
        val time = eventTime(timeMs)
        val previous = tickTime ?: time.also { tickTime = it }
        if (time - previous > MAX_CATCH_UP_MS) {
            captures.values.forEach { capture ->
                capture.motion?.reset(time)
                if (capture.motion != null) aggregate.vector(capture.source, StickVector())
            }
            tickTime = time
        } else {
            val elapsed = time - previous
            if (elapsed > 0) {
                for (capture in captures.values) {
                    capture.motion?.let { aggregate.vector(capture.source, it.tick(time, elapsed)) }
                }
                tickTime = time
            }
        }
        for ((source, repeater) in repeaters) {
            if (!repeater.binding.turbo || time < repeater.nextEdgeMs) continue
            repeater.pressed = !repeater.pressed
            repeater.effectiveFrequencyHz = minOf(
                repeater.binding.frequencyHz,
                (500.0 / (time - repeater.lastEdgeMs).coerceAtLeast(1)).toFloat()
            )
            repeater.lastEdgeMs = time
            repeater.nextEdgeMs = time + repeater.halfPeriodMs
            aggregate.buttons(source, if (repeater.pressed) repeater.binding.buttons else emptySet())
        }
        val pulseIterator = pulses.iterator()
        while (pulseIterator.hasNext()) {
            val pulse = pulseIterator.next()
            if (time < pulse.value) continue
            aggregate.remove(pulse.key)
            pulseOwners.remove(pulse.key)
            pulseIterator.remove()
        }
        aggregate.flush()
        sink.sync()
    }

    @Synchronized
    fun cancel(pointerId: Int) {
        val capture = captures[pointerId] ?: return
        capture.control?.let { taps.remove(it.id) }
        release(capture)
        aggregate.flush()
    }

    @Synchronized
    fun cancelAll() {
        sink.reset()
        captures.values.toList().forEach(::release)
        repeaters.clear()
        pulses.clear()
        pulseOwners.clear()
        taps.clear()
        aggregate.clear()
        tickTime = null
        aggregate.flush()
    }

    @Synchronized
    fun hasActiveInputs(): Boolean = captures.isNotEmpty() || pulses.isNotEmpty()

    @Synchronized
    fun snapshot(): RuntimeSnapshot = RuntimeSnapshot(
        captures.values.map {
            PointerSnapshot(
                it.pointerId, it.control?.id, it.keyIndex,
                it.x, it.y, it.centerX, it.centerY, it.activeKeys.toSet(), it.gesture, it.nativeTouch
            )
        },
        aggregate.buttons.toSet(),
        aggregate.sticks.toMap(),
        aggregate.owners().mapValues { (_, source) ->
            captures.values.firstOrNull { it.source == source }?.control?.id ?: pulseOwners[source]
        },
        captures.values.filter { it.motion?.saturated == true }.mapNotNull { it.control?.id }.toSet(),
        repeaters.filterValues { it.binding.turbo }.mapValues { it.value.effectiveFrequencyHz }
    )

    private fun eventTime(timeMs: Long): Long {
        currentTime = maxOf(currentTime, timeMs)
        return currentTime
    }

    private fun activate(source: String, binding: ButtonBinding, time: Long) {
        repeaters[source] = Repeater(binding, time)
        aggregate.buttons(source, binding.buttons)
    }

    private fun deactivate(source: String) {
        repeaters.remove(source)
        aggregate.remove(source)
    }

    private fun updateGroup(capture: Capture, time: Long) {
        val control = capture.control!!
        val keys = if (control.groupStyle == GroupStyle.FACE) {
            setOfNotNull(capture.keyIndex)
        } else {
            TouchHitGeometry.dpadKeys(capture.bounds, capture.x, capture.y).filter { it < control.groupKeys.size }.toSet()
        }
        for (key in capture.activeKeys - keys) deactivate("${capture.source}:$key")
        for (key in keys - capture.activeKeys) {
            if (control.haptics) sink.haptic(control)
            activate("${capture.source}:$key", control.groupKeys[key].binding, time)
        }
        capture.activeKeys = keys
    }

    private fun updateStick(capture: Capture) {
        val control = capture.control!!
        val radius = control.stickDiameter * density / 2f
        if (control.freeStick) {
            // Keep the current finger exactly on the edge by moving the center with it.
            val dx = capture.x - capture.centerX
            val dy = capture.y - capture.centerY
            val distance = hypot(dx, dy)
            if (distance > radius) {
                val scale = radius / distance
                capture.centerX = capture.x - dx * scale
                capture.centerY = capture.y - dy * scale
            }
        }
        val dx = (capture.x.toDouble() - capture.centerX) / radius
        val dy = (capture.centerY.toDouble() - capture.y) / radius
        val length = hypot(dx, dy)
        val magnitude = when {
            length <= control.stickDeadzone -> 0.0
            control.maximumDeflection -> 1.0
            else -> ((length - control.stickDeadzone) / (1f - control.stickDeadzone)).coerceAtMost(1.0)
        }
        val vector = if (magnitude == 0.0) {
            StickVector()
        } else {
            StickVector((dx / length * magnitude).toFloat(), (dy / length * magnitude).toFloat())
        }
        aggregate.vector(capture.source, vector)
        val auxiliary = control.auxiliaryButton
        val pressed = if (control.auxiliaryMode == AuxiliaryMode.PUSH) magnitude > 0f else capture.doubleTap
        aggregate.buttons(capture.source, if (pressed && auxiliary != null) setOf(auxiliary) else emptySet())
    }

    private fun recognize(capture: Capture) {
        val control = capture.control!!
        if (capture.gesture != null || capture.maxDistance < control.gestureDistance * density) return
        val allowed = control.gestures.keys.filter { it != GestureDirection.TAP }.toSet()
        if (allowed.isEmpty()) return
        val direction = TouchHitGeometry.direction(capture.x - capture.startX, capture.y - capture.startY, allowed) ?: return
        capture.gesture = direction
        if (control.gestureTiming == GestureTiming.IMMEDIATE) {
            if (control.haptics) sink.haptic(control)
            if (control.gestureHold) {
                applyGesture(capture.source, control.gestures[direction])
            } else {
                pulse(control.id, control.gestures[direction], control.gesturePulseMs, currentTime)
            }
        }
    }

    private fun applyGesture(source: String, binding: GestureBinding?) {
        if (binding == null) return
        if (binding.buttons.isNotEmpty()) aggregate.buttons(source, binding.buttons)
        binding.stick?.let { aggregate.acquire(source, it, TouchHitGeometry.vector(binding.direction)) }
    }

    private fun pulse(controlId: String, binding: GestureBinding?, duration: Long, time: Long) {
        if (binding == null) return
        val source = "pulse:${++pulseSerial}"
        applyGesture(source, binding)
        pulses[source] = time + duration
        pulseOwners[source] = controlId
    }

    private fun release(capture: Capture) {
        if (capture.nativeTouch) sink.touchUp(capture.pointerId)
        capture.activeKeys.forEach { deactivate("${capture.source}:$it") }
        deactivate(capture.source)
        captures.remove(capture.pointerId)
    }

    private fun copyControl(control: TouchControl): TouchControl = control.copy(
        binding = control.binding.copy(buttons = control.binding.buttons.toSet()),
        groupKeys = control.groupKeys.map { it.copy(binding = it.binding.copy(buttons = it.binding.buttons.toSet())) },
        gestures = control.gestures.toMap()
    )

    private fun validate(control: TouchControl, bounds: TouchRect, presets: List<SwipePreset>) {
        require(listOf(bounds.x, bounds.y, bounds.width, bounds.height, bounds.right, bounds.bottom).all { it.isFinite() } && bounds.width > 0f && bounds.height > 0f)
        require(!control.triggerOnly || control.type == ControlType.STICK)
        if (control.isVisibleStick) {
            val diameter = control.stickDiameter * density
            require(
                kotlin.math.abs(bounds.width - diameter) < 0.01f &&
                    kotlin.math.abs(bounds.height - diameter) < 0.01f
            ) { "Visible stick must be a circle matching its size" }
        }
        require(control.type != ControlType.BUTTON_GROUP || control.groupKeys.size == 4)
        val targets = (0..15).toSet() + setOf(18, 19)
        require(control.auxiliaryButton == null || control.auxiliaryButton in targets)
        control.gestures.values.forEach {
            require((it.buttons.isNotEmpty()) xor (it.stick != null))
            require(it.buttons.all { button -> button in targets })
            require(it.stick == null || it.direction != GestureDirection.TAP)
        }
        require(control.stickDeadzone.isFinite() && control.stickDeadzone >= 0f && control.stickDeadzone < 1f)
        require(control.stickDiameter.isFinite() && control.stickDiameter > 0f)
        require(control.gestureDistance.isFinite() && control.gestureDistance > 0f && control.tapSlop.isFinite() && control.tapSlop >= 0f)
        require(control.gesturePulseMs in 1..60_000 && control.tapTimeoutMs in 1..60_000)
        for (binding in listOf(control.binding) + control.groupKeys.map { it.binding }) {
            require(binding.buttons.all { it in targets })
            require(!binding.turbo || binding.frequencyHz.isFinite() && binding.frequencyHz in 1f..60f)
        }
        if (control.enabled && control.type == ControlType.SWIPE) require(presets.any { it.id == control.swipePresetId })
    }

    companion object {
        const val TICK_MS = 8L
        private const val MAX_CATCH_UP_MS = 256L
    }
}
