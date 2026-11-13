// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch

import org.yuzu.yuzu_emu.features.input.NativeInput
import org.yuzu.yuzu_emu.features.input.model.NativeAnalog
import org.yuzu.yuzu_emu.features.input.model.NativeButton
import org.yuzu.yuzu_emu.overlay.touch.model.StickTarget

fun TouchNativeSink(): TouchNativeSink = TouchNativeSink(JniNativeOverlayPort)

private object JniNativeOverlayPort : NativeOverlayPort {
    override fun currentPort(): Int = if (NativeInput.isHandheldOnly()) 8 else 0

    override fun button(port: Int, button: Int, pressed: Boolean) {
        NativeInput.onOverlayButtonEvent(
            port,
            NativeButton.from(button),
            if (pressed) NativeInput.ButtonState.PRESSED else NativeInput.ButtonState.RELEASED
        )
    }

    override fun stick(port: Int, target: StickTarget, x: Float, y: Float) {
        NativeInput.onOverlayJoystickEvent(
            port,
            if (target == StickTarget.LEFT) NativeAnalog.LStick else NativeAnalog.RStick,
            x,
            y
        )
    }

    override fun touchDown(pointerId: Int, x: Float, y: Float) = NativeInput.onTouchPressed(pointerId, x, y)
    override fun touchMove(pointerId: Int, x: Float, y: Float) = NativeInput.onTouchMoved(pointerId, x, y)
    override fun touchUp(pointerId: Int) = NativeInput.onTouchReleased(pointerId)
}
