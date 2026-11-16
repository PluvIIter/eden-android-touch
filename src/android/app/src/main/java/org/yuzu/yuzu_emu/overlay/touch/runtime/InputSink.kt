// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.runtime

import org.yuzu.yuzu_emu.overlay.touch.model.StickTarget
import org.yuzu.yuzu_emu.overlay.touch.model.TouchControl

interface InputSink {
    fun reset() {}
    fun sync() {}
    fun buttons(pressed: Set<Int>) {}
    fun stick(target: StickTarget, x: Float, y: Float) {}
    fun touchDown(pointerId: Int, x: Float, y: Float) {}
    fun touchMove(pointerId: Int, x: Float, y: Float) {}
    fun touchUp(pointerId: Int) {}
    fun haptic(control: TouchControl, strong: Boolean = false) {}
    fun toggleVisibility() {}
    fun switchTemplate(templateId: String) {}
    fun appearance() {}
}

data class StickVector(val x: Float = 0f, val y: Float = 0f)

data class PointerSnapshot(
    val pointerId: Int,
    val controlId: String?,
    val keyIndex: Int?,
    val x: Float,
    val y: Float,
    val centerX: Float,
    val centerY: Float,
    val activeKeys: Set<Int>,
    val recognizedGesture: org.yuzu.yuzu_emu.overlay.touch.model.GestureDirection?,
    val nativeTouch: Boolean
)

data class RuntimeSnapshot(
    val pointers: List<PointerSnapshot>,
    val buttons: Set<Int>,
    val sticks: Map<StickTarget, StickVector>,
    val stickOwners: Map<StickTarget, String?>,
    val saturatedControls: Set<String>,
    val effectiveTurboHz: Map<String, Float> = emptyMap()
)

internal class InputAggregator(private val sink: InputSink) {
    private data class StickSource(val target: StickTarget, val order: Long, var vector: StickVector)

    private val buttonSources = mutableMapOf<String, Set<Int>>()
    private val stickSources = mutableMapOf<String, StickSource>()
    private var order = 0L
    private var buttonsDirty = false
    private var sticksDirty = false
    private val targets = StickTarget.values()
    var buttons: Set<Int> = emptySet()
        private set
    val sticks = StickTarget.values().associateWith { StickVector() }.toMutableMap()

    fun buttons(source: String, pressed: Set<Int>) {
        if (buttonSources[source].orEmpty() == pressed) return
        if (pressed.isEmpty()) buttonSources.remove(source) else buttonSources[source] = pressed.toSet()
        buttonsDirty = true
    }

    fun acquire(source: String, target: StickTarget, vector: StickVector = StickVector()) {
        check(source !in stickSources)
        stickSources[source] = StickSource(target, ++order, vector)
        sticksDirty = true
    }

    fun vector(source: String, vector: StickVector) {
        val state = stickSources[source] ?: return
        if (state.vector == vector) return
        state.vector = vector
        sticksDirty = true
    }

    fun remove(source: String) {
        if (buttonSources.remove(source) != null) buttonsDirty = true
        if (stickSources.remove(source) != null) sticksDirty = true
    }

    fun clear() {
        buttonsDirty = buttonsDirty || buttonSources.isNotEmpty()
        sticksDirty = sticksDirty || stickSources.isNotEmpty()
        buttonSources.clear()
        stickSources.clear()
    }

    fun owners(): Map<StickTarget, String?> = StickTarget.values().associateWith { target ->
        stickSources.entries.filter { it.value.target == target }.maxByOrNull { it.value.order }?.key
    }

    fun flush() {
        if (buttonsDirty) {
            val pressed = HashSet<Int>()
            for (source in buttonSources.values) pressed.addAll(source)
            if (buttons != pressed) {
                buttons = pressed
                sink.buttons(pressed)
            }
            buttonsDirty = false
        }
        if (!sticksDirty) return
        for (target in targets) {
            var owner: StickSource? = null
            for (source in stickSources.values) {
                if (source.target == target && (owner == null || source.order > owner.order)) owner = source
            }
            val vector = owner?.vector ?: ZERO
            if (sticks[target] != vector) {
                sticks[target] = vector
                sink.stick(target, vector.x, vector.y)
            }
        }
        sticksDirty = false
    }

    companion object {
        private val ZERO = StickVector()
    }
}
