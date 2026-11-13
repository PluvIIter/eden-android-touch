// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch

import org.yuzu.yuzu_emu.overlay.touch.model.StickTarget
import org.yuzu.yuzu_emu.overlay.touch.runtime.InputSink
import org.yuzu.yuzu_emu.overlay.touch.runtime.StickVector

interface NativeOverlayPort {
    fun currentPort(): Int
    fun button(port: Int, button: Int, pressed: Boolean)
    fun stick(port: Int, target: StickTarget, x: Float, y: Float)
    fun touchDown(pointerId: Int, x: Float, y: Float)
    fun touchMove(pointerId: Int, x: Float, y: Float)
    fun touchUp(pointerId: Int)
}

class TouchNativeSink(private val native: NativeOverlayPort) : InputSink {
    private var port: Int? = null
    private var pressed = emptySet<Int>()
    private val sticks = mutableMapOf<StickTarget, StickVector>()
    private val touches = mutableSetOf<Int>()

    override fun reset() = clear()

    override fun sync() {
        syncPort()
    }

    override fun buttons(pressed: Set<Int>) {
        val targetPort = migratePort(buttonUpdate = true)
        (this.pressed - pressed).forEach { native.button(targetPort, it, false) }
        (pressed - this.pressed).forEach { native.button(targetPort, it, true) }
        this.pressed = pressed.toSet()
    }

    override fun stick(target: StickTarget, x: Float, y: Float) {
        require(x.isFinite() && y.isFinite())
        val targetPort = migratePort(stickUpdate = target)
        native.stick(targetPort, target, x, y)
        if (x == 0f && y == 0f) sticks.remove(target) else sticks[target] = StickVector(x, y)
    }

    override fun touchDown(pointerId: Int, x: Float, y: Float) {
        syncPort()
        if (touches.add(pointerId)) native.touchDown(pointerId, x, y)
    }

    override fun touchMove(pointerId: Int, x: Float, y: Float) {
        syncPort()
        if (pointerId in touches) native.touchMove(pointerId, x, y)
    }

    override fun touchUp(pointerId: Int) {
        syncPort()
        if (touches.remove(pointerId)) native.touchUp(pointerId)
    }

    fun syncPort(): Int = migratePort()

    private fun migratePort(buttonUpdate: Boolean = false, stickUpdate: StickTarget? = null): Int {
        val current = native.currentPort()
        val previous = port
        if (previous != null && previous != current) {
            pressed.forEach { native.button(previous, it, false) }
            sticks.keys.forEach { native.stick(previous, it, 0f, 0f) }
            if (buttonUpdate) {
                pressed = emptySet()
            } else {
                pressed.forEach { native.button(current, it, true) }
            }
            sticks.filterKeys { it != stickUpdate }.forEach { (target, vector) ->
                native.stick(current, target, vector.x, vector.y)
            }
        }
        port = current
        return current
    }

    fun clear() {
        port?.let { previous ->
            pressed.forEach { native.button(previous, it, false) }
            sticks.keys.forEach { native.stick(previous, it, 0f, 0f) }
        }
        touches.forEach { native.touchUp(it) }
        pressed = emptySet()
        sticks.clear()
        touches.clear()
        port = null
    }
}
