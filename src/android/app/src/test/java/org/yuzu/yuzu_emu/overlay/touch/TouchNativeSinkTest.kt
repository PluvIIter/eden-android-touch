// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yuzu.yuzu_emu.overlay.touch.model.ControlType
import org.yuzu.yuzu_emu.overlay.touch.model.StickTarget
import org.yuzu.yuzu_emu.overlay.touch.model.TouchControl
import org.yuzu.yuzu_emu.overlay.touch.model.TouchRect
import org.yuzu.yuzu_emu.overlay.touch.model.TouchTemplate
import org.yuzu.yuzu_emu.overlay.touch.runtime.TouchRuntime

class TouchNativeSinkTest {
    private class Native : NativeOverlayPort {
        var selectedPort = 0
        val events = mutableListOf<String>()
        val buttons = mutableMapOf<Pair<Int, Int>, Boolean>()
        val sticks = mutableMapOf<Pair<Int, StickTarget>, Pair<Float, Float>>()
        val touches = mutableSetOf<Int>()
        override fun currentPort(): Int = selectedPort
        override fun button(port: Int, button: Int, pressed: Boolean) {
            events += "button:$port:$button:$pressed"
            buttons[port to button] = pressed
        }
        override fun stick(port: Int, target: StickTarget, x: Float, y: Float) {
            events += "stick:$port:$target:$x:$y"
            sticks[port to target] = x to y
        }
        override fun touchDown(pointerId: Int, x: Float, y: Float) {
            events += "down:$pointerId:$x:$y"
            touches += pointerId
        }
        override fun touchMove(pointerId: Int, x: Float, y: Float) { events += "move:$pointerId:$x:$y" }
        override fun touchUp(pointerId: Int) { events += "up:$pointerId"; touches -= pointerId }
    }

    private val native = Native()
    private val sink = TouchNativeSink(native)

    @Test fun buttonSetsGenerateOnlyChangedEdges() {
        sink.buttons(setOf(4, 5))
        sink.buttons(setOf(5, 6))
        sink.buttons(setOf(5, 6))
        assertEquals(listOf("button:0:4:true", "button:0:5:true", "button:0:4:false", "button:0:6:true"), native.events)
        sink.buttons(emptySet())
        assertTrue(native.buttons.values.none { it })
    }

    @Test fun portChangeReleasesOldButtonsBeforePressingNewSet() {
        sink.buttons(setOf(4, 5))
        native.events.clear()
        native.selectedPort = 8
        sink.buttons(setOf(5, 6))
        assertEquals(listOf("button:0:4:false", "button:0:5:false", "button:8:5:true", "button:8:6:true"), native.events)
        assertFalse(native.buttons.getValue(0 to 4))
        assertFalse(native.buttons.getValue(0 to 5))
    }

    @Test fun releasingAfterPortChangeDoesNotPhantomPressOnNewPort() {
        sink.buttons(setOf(4))
        native.events.clear()
        native.selectedPort = 8
        sink.buttons(emptySet())
        assertEquals(listOf("button:0:4:false"), native.events)
    }

    @Test fun stickPortChangeZeroesOldTargetBeforeNewVector() {
        sink.stick(StickTarget.RIGHT, 0.5f, 0.75f)
        native.events.clear()
        native.selectedPort = 8
        sink.stick(StickTarget.RIGHT, -1f, 0f)
        assertEquals(listOf("stick:0:RIGHT:0.0:0.0", "stick:8:RIGHT:-1.0:0.0"), native.events)
        assertEquals(0f to 0f, native.sticks[0 to StickTarget.RIGHT])
    }

    @Test fun aButtonEventMigratesBothStickTargetsAndAllButtons() {
        sink.buttons(setOf(1))
        sink.stick(StickTarget.LEFT, 0.5f, 0f)
        sink.stick(StickTarget.RIGHT, 0f, 1f)
        native.events.clear()
        native.selectedPort = 8
        sink.buttons(setOf(1))
        assertEquals(
            listOf(
                "button:0:1:false",
                "stick:0:LEFT:0.0:0.0",
                "stick:0:RIGHT:0.0:0.0",
                "stick:8:LEFT:0.5:0.0",
                "stick:8:RIGHT:0.0:1.0",
                "button:8:1:true"
            ),
            native.events
        )
    }

    @Test fun explicitPortSyncMigratesUnchangedStateOnce() {
        sink.buttons(setOf(1))
        sink.stick(StickTarget.LEFT, 0.5f, 0f)
        native.selectedPort = 8
        sink.syncPort()
        val count = native.events.size
        sink.syncPort()
        assertEquals(count, native.events.size)
        assertFalse(native.buttons.getValue(0 to 1))
        assertTrue(native.buttons.getValue(8 to 1))
        assertEquals(0f to 0f, native.sticks[0 to StickTarget.LEFT])
        assertEquals(0.5f to 0f, native.sticks[8 to StickTarget.LEFT])
    }

    @Test fun clearReleasesRecordedPortWithoutActivatingChangedPortAndIsIdempotent() {
        sink.buttons(setOf(1))
        sink.stick(StickTarget.LEFT, 0.5f, 0f)
        sink.stick(StickTarget.RIGHT, 0f, 1f)
        sink.touchDown(71, 4f, 5f)
        native.events.clear()
        native.selectedPort = 8
        sink.clear()
        assertEquals(listOf("button:0:1:false", "stick:0:LEFT:0.0:0.0", "stick:0:RIGHT:0.0:0.0", "up:71"), native.events)
        assertTrue(native.buttons.values.none { it })
        assertTrue(native.sticks.values.all { it == 0f to 0f })
        assertTrue(native.touches.isEmpty())
        val count = native.events.size
        sink.clear()
        assertEquals(count, native.events.size)
    }

    @Test fun nativePointerIdentityAndCoordinatesPassThroughAndUnknownPointersAreIgnored() {
        sink.touchDown(91, 10.5f, 12.5f)
        sink.touchDown(91, 20f, 20f)
        sink.touchMove(91, 30f, 40f)
        sink.touchMove(2, 30f, 40f)
        sink.touchUp(91)
        sink.touchUp(91)
        assertEquals(listOf("down:91:10.5:12.5", "move:91:30.0:40.0", "up:91"), native.events)
    }

    @Test fun runtimeCancelAndSinkClearLeaveNoStaleInputsAcrossPortSwitch() {
        val runtime = TouchRuntime(sink)
        val button = TouchControl(id = "button", bounds = TouchRect(0f, 0f, 60f, 60f))
        val stick = TouchControl(id = "stick", type = ControlType.STICK, bounds = TouchRect(100f, 0f, 100f, 100f), stickDeadzone = 0f)
        runtime.configure(
            TouchTemplate(controls = listOf(button, stick)),
            mapOf(button.id to button.bounds, stick.id to stick.bounds),
            emptyList(),
            500f,
            500f,
            1f
        )
        runtime.down(1, 30f, 30f, 0)
        runtime.down(2, 200f, 50f, 0)
        runtime.down(3, 400f, 400f, 0)
        native.selectedPort = 8
        runtime.cancelAll()
        sink.clear()
        assertTrue(native.buttons.values.none { it })
        assertTrue(native.sticks.values.all { it == 0f to 0f })
        assertTrue(native.touches.isEmpty())
        assertTrue(runtime.snapshot().pointers.isEmpty())
    }

    @Test fun runtimeTickMigratesUnchangedHeldOutputsWithoutNewPointerEvents() {
        val runtime = TouchRuntime(sink)
        val button = TouchControl(id = "button", bounds = TouchRect(0f, 0f, 60f, 60f))
        val stick = TouchControl(id = "stick", type = ControlType.STICK, bounds = TouchRect(100f, 0f, 100f, 100f), stickDeadzone = 0f)
        runtime.configure(
            TouchTemplate(controls = listOf(button, stick)),
            mapOf(button.id to button.bounds, stick.id to stick.bounds),
            emptyList(),
            500f,
            500f,
            1f
        )
        runtime.down(1, 30f, 30f, 0)
        runtime.down(2, 200f, 50f, 0)
        native.events.clear()
        native.selectedPort = 8
        runtime.tick(8)
        assertEquals(
            listOf("button:0:0:false", "stick:0:RIGHT:0.0:0.0", "button:8:0:true", "stick:8:RIGHT:1.0:0.0"),
            native.events
        )
        val count = native.events.size
        runtime.tick(16)
        assertEquals(count, native.events.size)
    }

    @Test fun runtimeCancelResetsNativeBeforeAggregateReleasesWithoutTransientPortMigration() {
        val runtime = TouchRuntime(sink)
        val button = TouchControl(id = "button", bounds = TouchRect(0f, 0f, 60f, 60f))
        val stick = TouchControl(id = "stick", type = ControlType.STICK, bounds = TouchRect(100f, 0f, 100f, 100f), stickDeadzone = 0f)
        runtime.configure(
            TouchTemplate(controls = listOf(button, stick)),
            mapOf(button.id to button.bounds, stick.id to stick.bounds),
            emptyList(),
            500f,
            500f,
            1f
        )
        runtime.down(1, 30f, 30f, 0)
        runtime.down(2, 200f, 50f, 0)
        runtime.down(3, 400f, 400f, 0)
        native.events.clear()
        native.selectedPort = 8
        runtime.cancelAll()
        assertEquals(
            listOf("button:0:0:false", "stick:0:RIGHT:0.0:0.0", "up:3", "stick:8:RIGHT:0.0:0.0"),
            native.events
        )
        assertTrue(native.buttons.values.none { it })
        assertTrue(native.sticks.values.all { it == 0f to 0f })
        assertTrue(native.touches.isEmpty())
    }

    @Test fun invalidStickValuesNeverReachNativePort() {
        assertThrows(IllegalArgumentException::class.java) { sink.stick(StickTarget.LEFT, Float.NaN, 0f) }
        assertTrue(native.events.isEmpty())
    }
}
