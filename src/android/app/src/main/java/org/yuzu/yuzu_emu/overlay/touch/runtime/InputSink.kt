// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.runtime

import org.yuzu.yuzu_emu.overlay.touch.model.StickTarget

interface InputSink {
    fun reset() {}
    fun sync() {}
    fun buttons(pressed: Set<Int>) {}
    fun stick(target: StickTarget, x: Float, y: Float) {}
    fun touchDown(pointerId: Int, x: Float, y: Float) {}
    fun touchMove(pointerId: Int, x: Float, y: Float) {}
    fun touchUp(pointerId: Int) {}
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
    val saturatedControls: Set<String>
)

internal class InputAggregator(private val sink: InputSink) {
    private data class StickSource(val target: StickTarget, val order: Long, var vector: StickVector)

    private val buttonSources = mutableMapOf<String, Set<Int>>()
    private val stickSources = mutableMapOf<String, StickSource>()
    private var order = 0L
    var buttons: Set<Int> = emptySet()
        private set
    val sticks = StickTarget.values().associateWith { StickVector() }.toMutableMap()

    fun buttons(source: String, pressed: Set<Int>) {
        if (pressed.isEmpty()) buttonSources.remove(source) else buttonSources[source] = pressed.toSet()
    }

    fun acquire(source: String, target: StickTarget, vector: StickVector = StickVector()) {
        check(source !in stickSources)
        stickSources[source] = StickSource(target, ++order, vector)
    }

    fun vector(source: String, vector: StickVector) {
        stickSources[source]?.vector = vector
    }

    fun remove(source: String) {
        buttonSources.remove(source)
        stickSources.remove(source)
    }

    fun clear() {
        buttonSources.clear()
        stickSources.clear()
    }

    fun owners(): Map<StickTarget, String?> = StickTarget.values().associateWith { target ->
        stickSources.entries.filter { it.value.target == target }.maxByOrNull { it.value.order }?.key
    }

    fun flush() {
        val pressed = buttonSources.values.flatten().toSet()
        if (buttons != pressed) {
            buttons = pressed
            sink.buttons(pressed)
        }
        for (target in StickTarget.values()) {
            val vector = stickSources.values.filter { it.target == target }
                .maxByOrNull { it.order }?.vector ?: StickVector()
            if (sticks[target] != vector) {
                sticks[target] = vector
                sink.stick(target, vector.x, vector.y)
            }
        }
    }
}
