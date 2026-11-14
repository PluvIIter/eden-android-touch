// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yuzu.yuzu_emu.overlay.touch.model.AuxiliaryMode
import org.yuzu.yuzu_emu.overlay.touch.model.ButtonBinding
import org.yuzu.yuzu_emu.overlay.touch.model.ControlType
import org.yuzu.yuzu_emu.overlay.touch.model.GestureBinding
import org.yuzu.yuzu_emu.overlay.touch.model.GestureDirection
import org.yuzu.yuzu_emu.overlay.touch.model.GestureTiming
import org.yuzu.yuzu_emu.overlay.touch.model.GroupKey
import org.yuzu.yuzu_emu.overlay.touch.model.GroupStyle
import org.yuzu.yuzu_emu.overlay.touch.model.MotionScale
import org.yuzu.yuzu_emu.overlay.touch.model.StickTarget
import org.yuzu.yuzu_emu.overlay.touch.model.SwipePreset
import org.yuzu.yuzu_emu.overlay.touch.model.TouchControl
import org.yuzu.yuzu_emu.overlay.touch.model.TouchRect
import org.yuzu.yuzu_emu.overlay.touch.model.TouchTemplate

class TouchRuntimeTest {
    private class Sink : InputSink {
        var pressed = emptySet<Int>()
        val batches = mutableListOf<Set<Int>>()
        val native = mutableListOf<String>()
        val sticks = mutableMapOf<StickTarget, StickVector>()
        val lifecycle = mutableListOf<String>()
        override fun reset() { lifecycle += "reset" }
        override fun sync() { lifecycle += "sync" }
        override fun buttons(pressed: Set<Int>) { this.pressed = pressed; batches += pressed }
        override fun stick(target: StickTarget, x: Float, y: Float) { sticks[target] = StickVector(x, y) }
        override fun touchDown(pointerId: Int, x: Float, y: Float) { native += "down:$pointerId" }
        override fun touchMove(pointerId: Int, x: Float, y: Float) { native += "move:$pointerId" }
        override fun touchUp(pointerId: Int) { native += "up:$pointerId" }
    }

    private val sink = Sink()
    private val runtime = TouchRuntime(sink)
    private val preset = SwipePreset(id = "preset", deadzone = 0f, maximumSpeed = 1f)
    private fun button(id: String, x: Float, buttons: Set<Int> = setOf(0), turbo: Boolean = false) =
        TouchControl(id = id, bounds = TouchRect(x, 0f, 60f, 60f), binding = ButtonBinding(buttons, turbo, 10f))
    private fun area(id: String = "swipe", x: Float = 100f, type: ControlType = ControlType.SWIPE) =
        TouchControl(id = id, type = type, bounds = TouchRect(x, 0f, 100f, 100f), swipePresetId = preset.id)
    private fun configure(vararg controls: TouchControl, density: Float = 1f, preset: SwipePreset = this.preset) {
        runtime.configure(
            TouchTemplate(controls = controls.toList()),
            controls.associate { it.id to it.bounds },
            listOf(preset),
            1000f,
            1000f,
            density
        )
    }
    private fun right(): StickVector = runtime.snapshot().sticks.getValue(StickTarget.RIGHT)
    private fun assertVector(x: Float, y: Float, actual: StickVector = right()) {
        assertEquals(x, actual.x, 0.0001f)
        assertEquals(y, actual.y, 0.0001f)
    }

    @Test fun lifecycleHooksRunOnEveryTickAndCancellationWithoutChangingSourceSemantics() {
        configure(button("button", 0f))
        assertEquals(listOf("reset"), sink.lifecycle)
        sink.lifecycle.clear()
        runtime.down(1, 30f, 30f, 0)
        runtime.tick(8)
        runtime.tick(16)
        assertEquals(listOf("sync", "sync"), sink.lifecycle)
        assertEquals(setOf(0), sink.pressed)
        runtime.cancelAll()
        assertEquals(listOf("sync", "sync", "reset"), sink.lifecycle)
        assertTrue(sink.pressed.isEmpty())
        runtime.tick(24)
        assertEquals("sync", sink.lifecycle.last())
    }

    @Test fun combinationsAreSynchronousAndOrAggregated() {
        configure(button("a", 0f, setOf(4, 5)), button("b", 80f, setOf(5)))
        runtime.down(91, 30f, 30f, 0)
        assertEquals(listOf(setOf(4, 5)), sink.batches)
        runtime.down(2, 110f, 30f, 1)
        runtime.move(91, 900f, 900f, 2)
        assertEquals(setOf(4, 5), sink.pressed)
        runtime.up(91, 900f, 900f, 3)
        assertEquals(setOf(5), sink.pressed)
        runtime.up(2, 110f, 30f, 4)
        assertTrue(sink.pressed.isEmpty())
    }

    @Test fun turboStartsImmediatelyAndCannotReleaseAnotherSource() {
        configure(button("turbo", 0f, setOf(4, 5), true), button("hold", 80f, setOf(5)))
        runtime.down(1, 30f, 30f, 0)
        assertEquals(setOf(4, 5), sink.pressed)
        runtime.down(2, 110f, 30f, 1)
        runtime.tick(50)
        assertEquals(setOf(5), sink.pressed)
        runtime.tick(100)
        assertEquals(setOf(4, 5), sink.pressed)
        runtime.up(1, 30f, 30f, 101)
        runtime.tick(200)
        assertEquals(setOf(5), sink.pressed)
    }

    @Test fun routingUsesUpperTierAndStablePointerCapture() {
        configure(button("upper", 100f), area())
        runtime.down(7, 130f, 30f, 0)
        assertEquals("upper", runtime.snapshot().pointers.single().controlId)
        runtime.down(8, 130f, 30f, 1)
        assertNull(runtime.snapshot().pointers.last().controlId)
        assertFalse(runtime.snapshot().pointers.last().nativeTouch)
        runtime.down(9, 190f, 90f, 2)
        assertEquals("swipe", runtime.snapshot().pointers.last().controlId)
        runtime.move(7, 190f, 90f, 3)
        assertEquals(setOf(0), sink.pressed)
        assertTrue(sink.native.isEmpty())
    }

    @Test fun circlesDoNotStealTheirBoundingCornersAndTopOrderWins() {
        configure(button("lower", 0f), button("upper", 0f))
        runtime.down(1, 1f, 1f, 0)
        runtime.move(1, 30f, 30f, 1)
        assertEquals(listOf("down:1", "move:1"), sink.native)
        assertTrue(sink.pressed.isEmpty())
        runtime.down(2, 30f, 30f, 2)
        assertEquals("upper", runtime.snapshot().pointers.last().controlId)
        runtime.cancelAll()
        assertEquals("up:1", sink.native.last())
    }

    @Test fun transparentEnabledControlsHitAndDisabledControlsPassThrough() {
        configure(button("transparent", 0f).copy(opacity = 0f), button("disabled", 80f).copy(enabled = false))
        runtime.down(1, 30f, 30f, 0)
        runtime.down(2, 110f, 30f, 0)
        assertEquals(setOf(0), sink.pressed)
        assertEquals(listOf("down:2"), sink.native)
        runtime.setTouchscreenEnabled(false)
        assertTrue(sink.pressed.isEmpty())
        runtime.down(3, 500f, 500f, 1)
        assertFalse(runtime.snapshot().pointers.single().nativeTouch)
    }

    @Test fun faceChildrenCaptureIndependentlyAndDoNotSlide() {
        val group = TouchControl(id = "face", type = ControlType.BUTTON_GROUP, bounds = TouchRect(0f, 0f, 90f, 90f))
        configure(group)
        runtime.down(1, 45f, 15f, 0)
        runtime.down(2, 75f, 45f, 1)
        assertEquals(setOf(2, 0), sink.pressed)
        runtime.move(1, 45f, 75f, 2)
        assertEquals(setOf(2, 0), sink.pressed)
        runtime.down(3, 75f, 45f, 3)
        assertFalse(runtime.snapshot().pointers.last().nativeTouch)
    }

    @Test fun dpadSlidesThroughAdjacentCombinationsAndCenter() {
        val group = TouchControl(
            id = "pad",
            type = ControlType.BUTTON_GROUP,
            groupStyle = GroupStyle.DPAD,
            bounds = TouchRect(0f, 0f, 100f, 100f)
        )
        configure(group)
        runtime.down(1, 50f, 5f, 0)
        assertEquals(setOf(2), sink.pressed)
        runtime.move(1, 95f, 5f, 1)
        assertEquals(setOf(2, 0), sink.pressed)
        runtime.move(1, 95f, 50f, 2)
        assertEquals(setOf(0), sink.pressed)
        runtime.move(1, 50f, 50f, 3)
        assertTrue(sink.pressed.isEmpty())
        runtime.down(2, 50f, 5f, 4)
        assertTrue(sink.pressed.isEmpty())
    }

    @Test fun dpadChildrenKeepIndependentTurboClocks() {
        val group = TouchControl(
            id = "pad",
            type = ControlType.BUTTON_GROUP,
            groupStyle = GroupStyle.DPAD,
            bounds = TouchRect(0f, 0f, 100f, 100f),
            groupKeys = listOf(
                GroupKey(binding = ButtonBinding(setOf(0), true, 10f)),
                GroupKey(binding = ButtonBinding(setOf(1), true, 5f)),
                GroupKey(binding = ButtonBinding(setOf(2))),
                GroupKey(binding = ButtonBinding(setOf(3)))
            )
        )
        configure(group)
        runtime.down(1, 65f, 20f, 0)
        assertEquals(setOf(0, 1), sink.pressed)
        runtime.tick(50)
        assertEquals(setOf(1), sink.pressed)
        runtime.tick(100)
        assertEquals(setOf(0), sink.pressed)
    }

    @Test fun idleTicksDoNotRepublishUnchangedAggregatesAndActivityHintTracksPulses() {
        configure(button("button", 0f))
        assertFalse(runtime.hasActiveInputs())
        runtime.down(1, 30f, 30f, 0)
        assertTrue(runtime.hasActiveInputs())
        for (time in 8L..800L step 8) runtime.tick(time)
        assertEquals(1, sink.batches.size)
        runtime.up(1, 30f, 30f, 801)
        assertFalse(runtime.hasActiveInputs())
        val count = sink.batches.size
        for (time in 808L..1600L step 8) runtime.tick(time)
        assertEquals(count, sink.batches.size)
    }

    @Test fun transparentDpadCornersFallThroughButCapturedFingerCanSlideOutsideCross() {
        val group = TouchControl(
            id = "pad",
            type = ControlType.BUTTON_GROUP,
            groupStyle = GroupStyle.DPAD,
            bounds = TouchRect(0f, 0f, 300f, 300f)
        )
        configure(group)
        runtime.down(1, 5f, 5f, 0)
        assertEquals(listOf("down:1"), sink.native)
        assertTrue(sink.pressed.isEmpty())
        runtime.down(2, 150f, 5f, 1)
        assertEquals(setOf(2), sink.pressed)
        runtime.move(2, 295f, 5f, 2)
        assertEquals(setOf(2, 0), sink.pressed)
        assertEquals("pad", runtime.snapshot().pointers.last().controlId)
    }

    @Test fun delayedTurboTicksDeliverAtMostOneEdgeAndExposeActualFrequency() {
        configure(button("turbo", 0f, turbo = true).copy(binding = ButtonBinding(setOf(0), true, 60f)))
        runtime.down(1, 30f, 30f, 0)
        for (time in 16L..1008L step 16) runtime.tick(time)
        assertEquals(64, sink.batches.size)
        assertEquals(31.25f, runtime.snapshot().effectiveTurboHz.getValue("pointer:1"))
        runtime.tick(5000)
        assertEquals(65, sink.batches.size)
        runtime.tick(5000)
        assertEquals(65, sink.batches.size)
        runtime.cancelAll()
        assertTrue(runtime.snapshot().effectiveTurboHz.isEmpty())
    }

    @Test fun stickOwnershipDoesNotChangeOnMovesAndRestoresCurrentState() {
        val a = TouchControl(id = "a", type = ControlType.STICK, bounds = TouchRect(0f, 0f, 100f, 100f), stickDeadzone = 0f, stickDiameter = 100f)
        val b = a.copy(id = "b", bounds = TouchRect(200f, 0f, 100f, 100f))
        configure(a, b)
        runtime.down(1, 50f, 50f, 0)
        runtime.move(1, 100f, 50f, 1)
        assertVector(1f, 0f)
        runtime.down(2, 250f, 50f, 2)
        assertVector(0f, 0f)
        runtime.move(1, 50f, 0f, 3)
        assertVector(0f, 0f)
        runtime.move(2, 200f, 50f, 4)
        assertVector(-1f, 0f)
        runtime.up(2, 200f, 50f, 5)
        assertVector(0f, 1f)
        runtime.up(1, 50f, 0f, 6)
        assertVector(0f, 0f)
    }

    @Test fun fixedTriggerOnlyStickKeepsItsCenterAndUsesItsDiameter() {
        val stick = TouchControl(
            id = "stick",
            type = ControlType.STICK,
            bounds = TouchRect(0f, 0f, 400f, 400f),
            triggerOnly = true,
            stickDeadzone = 0f,
            stickDiameter = 100f
        )
        configure(stick)
        runtime.down(1, 200f, 200f, 0)
        assertVector(0f, 0f)
        assertEquals(200f, runtime.snapshot().pointers.single().centerX)
        runtime.move(1, 225f, 200f, 1)
        assertEquals(0.5f, right().x, 0.0001f)
        assertEquals(0f, right().y, 0.0001f)
    }

    @Test fun visibleFixedStickKeepsItsPressCenter() {
        val stick = TouchControl(
            id = "stick",
            type = ControlType.STICK,
            bounds = TouchRect(0f, 0f, 100f, 100f),
            stickDeadzone = 0f,
            stickDiameter = 100f
        )
        configure(stick)
        runtime.down(1, 20f, 20f, 0)
        assertEquals(20f, runtime.snapshot().pointers.single().centerX)
        runtime.move(1, 100f, 20f, 1)
        assertEquals(20f, runtime.snapshot().pointers.single().centerX)
        assertVector(1f, 0f)
    }

    @Test fun freeStickFollowsOnlyAfterLeavingItsRadius() {
        val stick = TouchControl(
            id = "stick",
            type = ControlType.STICK,
            bounds = TouchRect(0f, 0f, 100f, 100f),
            freeStick = true,
            stickDeadzone = 0f,
            stickDiameter = 100f
        )
        configure(stick)
        runtime.down(1, 50f, 50f, 0)
        assertVector(0f, 0f)
        runtime.move(1, 90f, 50f, 1)
        assertVector(0.8f, 0f)
        assertEquals(50f, runtime.snapshot().pointers.single().centerX)
        runtime.move(1, 150f, 50f, 2)
        assertVector(1f, 0f)
        assertEquals(100f, runtime.snapshot().pointers.single().centerX)
        runtime.move(1, 60f, 50f, 3)
        assertVector(-0.8f, 0f)
    }

    @Test fun freeMaximumStickPreservesLogicalCenterAndPushAuxiliary() {
        val stick = TouchControl(
            id = "stick",
            type = ControlType.STICK,
            bounds = TouchRect(0f, 0f, 100f, 100f),
            freeStick = true,
            maximumDeflection = true,
            stickDiameter = 100f,
            auxiliaryButton = 10
        )
        configure(stick)
        runtime.down(1, 20f, 20f, 0)
        assertVector(0f, 0f)
        runtime.move(1, 30f, 10f, 1)
        assertVector(0.70710677f, 0.70710677f)
        assertEquals(setOf(10), sink.pressed)
        assertEquals(20f, runtime.snapshot().pointers.single().centerX)
        runtime.move(1, 20f, 20f, 2)
        assertVector(0f, 0f)
        assertTrue(sink.pressed.isEmpty())
    }

    @Test fun doubleTapAuxiliaryHoldsThroughCenterOnlyOnSecondTouch() {
        val stick = TouchControl(
            id = "stick",
            type = ControlType.STICK,
            bounds = TouchRect(0f, 0f, 100f, 100f),
            freeStick = true,
            stickDiameter = 100f,
            auxiliaryButton = 10,
            auxiliaryMode = AuxiliaryMode.DOUBLE_TAP
        )
        configure(stick)
        runtime.down(1, 20f, 20f, 0)
        runtime.up(1, 20f, 20f, 20)
        runtime.down(2, 22f, 22f, 100)
        assertEquals(setOf(10), sink.pressed)
        assertVector(0f, 0f)
        runtime.up(2, 22f, 22f, 120)
        assertTrue(sink.pressed.isEmpty())
        runtime.down(3, 22f, 22f, 150)
        assertTrue(sink.pressed.isEmpty())
    }

    @Test fun swipeDownDoesNotMoveAndUpClearsAllPendingMovement() {
        configure(area())
        runtime.down(1, 110f, 20f, 0)
        runtime.tick(8)
        assertVector(0f, 0f)
        runtime.move(1, 118f, 20f, 16)
        runtime.tick(16)
        assertVector(0.5f, 0f)
        runtime.up(1, 900f, 20f, 17)
        assertVector(0f, 0f)
        runtime.tick(24)
        assertVector(0f, 0f)
    }

    @Test fun stoppedSwipeRetainsZeroOwnershipUntilUp() {
        val stick = TouchControl(id = "stick", type = ControlType.STICK, bounds = TouchRect(0f, 0f, 100f, 100f), stickDeadzone = 0f, stickDiameter = 100f)
        configure(stick, area())
        runtime.down(1, 50f, 50f, 0)
        runtime.down(2, 110f, 20f, 1)
        assertVector(0f, 0f)
        runtime.move(2, 118f, 20f, 9)
        runtime.tick(16)
        assertVector(1f, 0f)
        runtime.tick(56)
        assertVector(0f, 0f)
        runtime.move(1, 50f, 0f, 57)
        assertVector(0f, 0f)
        runtime.up(2, 118f, 20f, 58)
        assertVector(0f, 1f)
    }

    @Test fun swipeSamplingDoesNotChangeIntegratedOutput() {
        fun integral(samples: Int): Float {
            val local = TouchRuntime(Sink())
            val swipe = area()
            local.configure(TouchTemplate(controls = listOf(swipe)), mapOf(swipe.id to swipe.bounds), listOf(preset), 1000f, 1000f, 1f)
            local.down(1, 110f, 20f, 0)
            var sum = 0f
            val interval = 16 / samples
            for (i in 1..samples) {
                local.move(1, 110f + i * interval * 0.25f, 20f, (i * interval).toLong())
            }
            for (time in listOf(8L, 16L)) {
                local.tick(time)
                sum += local.snapshot().sticks.getValue(StickTarget.RIGHT).x * 8f
            }
            return sum
        }
        assertEquals(integral(1), integral(2), 0.0001f)
        assertEquals(integral(2), integral(4), 0.0001f)
    }

    @Test fun swipeDensityScalingAxesAndInverseCurveAreApplied() {
        val p = preset.copy(scale = MotionScale.DP, maximumSpeed = 1000f, exponent = 2f, deadzone = 0.2f)
        configure(area(), density = 2f, preset = p)
        runtime.down(1, 110f, 20f, 0)
        runtime.move(1, 114f, 20f, 8)
        runtime.tick(8)
        assertVector(0.6f, 0f)
        runtime.updatePresets(listOf(p.copy(swapAxes = true, invertX = true)))
        runtime.move(1, 118f, 20f, 16)
        runtime.tick(16)
        assertVector(0f, -0.6f)
    }

    @Test fun incompatiblePresetUpdateKeepsCaptureAndResetsHistory() {
        configure(area())
        runtime.down(41, 110f, 20f, 0)
        runtime.move(41, 118f, 20f, 8)
        runtime.updatePresets(listOf(preset.copy(invertX = true)))
        runtime.tick(8)
        assertVector(0f, 0f)
        assertEquals(41, runtime.snapshot().pointers.single().pointerId)
        runtime.move(41, 126f, 20f, 16)
        runtime.tick(16)
        assertVector(-1f, 0f)
        runtime.tick(80)
        runtime.updatePresets(listOf(preset.copy(invertX = true, sensitivityX = 2f)))
        runtime.tick(88)
        assertVector(0f, 0f)
    }

    @Test fun continuousPresetUpdatesDoNotReinterpretOldSamples() {
        configure(area())
        runtime.down(1, 110f, 20f, 0)
        runtime.move(1, 112f, 20f, 8)
        runtime.updatePresets(listOf(preset.copy(sensitivityX = 2f)))
        runtime.tick(8)
        assertVector(0.25f, 0f)
        runtime.move(1, 114f, 20f, 16)
        runtime.tick(16)
        assertVector(0.5f, 0f)
    }

    @Test fun isolatedDebugReleasesOthersAndDoesNotPassThroughOutside() {
        configure(button("button", 100f), area())
        runtime.down(1, 130f, 30f, 0)
        runtime.setIsolatedSwipe("swipe")
        assertTrue(sink.pressed.isEmpty())
        runtime.down(2, 130f, 30f, 1)
        assertEquals("swipe", runtime.snapshot().pointers.single().controlId)
        runtime.down(3, 900f, 900f, 1)
        assertTrue(sink.native.isEmpty())
        runtime.setIsolatedSwipe(null)
        assertTrue(runtime.snapshot().pointers.isEmpty())
        runtime.down(4, 130f, 30f, 2)
        assertEquals("button", runtime.snapshot().pointers.single().controlId)
    }

    @Test fun immediateGestureLocksOnceAndUsesIndependentOutputDirection() {
        val gesture = area(type = ControlType.GESTURE).copy(
            gestureTiming = GestureTiming.IMMEDIATE,
            gestures = mapOf(GestureDirection.UP to GestureBinding(stick = StickTarget.RIGHT, direction = GestureDirection.LEFT))
        )
        configure(gesture)
        runtime.down(1, 150f, 50f, 0)
        runtime.move(1, 150f, 10f, 10)
        assertVector(-1f, 0f)
        runtime.move(1, 190f, 50f, 20)
        assertEquals(GestureDirection.UP, runtime.snapshot().pointers.single().recognizedGesture)
        assertVector(-1f, 0f)
        runtime.up(1, 190f, 50f, 30)
        assertVector(0f, 0f)
    }

    @Test fun releaseGestureIsFiniteAndCancelNeverFires() {
        val gesture = area(type = ControlType.GESTURE).copy(
            gestures = mapOf(
                GestureDirection.RIGHT to GestureBinding(buttons = setOf(0)),
                GestureDirection.TAP to GestureBinding(buttons = setOf(1))
            )
        )
        configure(gesture)
        runtime.down(1, 110f, 20f, 0)
        runtime.move(1, 160f, 20f, 10)
        assertTrue(sink.pressed.isEmpty())
        runtime.cancelAll()
        assertTrue(sink.pressed.isEmpty())
        runtime.down(1, 110f, 20f, 20)
        runtime.move(1, 160f, 20f, 30)
        runtime.up(1, 160f, 20f, 40)
        assertEquals(setOf(0), sink.pressed)
        runtime.tick(139)
        assertEquals(setOf(0), sink.pressed)
        runtime.tick(140)
        assertTrue(sink.pressed.isEmpty())
    }

    @Test fun tapCannotBecomeHoldAndMovementOutAndBackInvalidatesTap() {
        val gesture = area(type = ControlType.GESTURE).copy(gestures = mapOf(GestureDirection.TAP to GestureBinding(buttons = setOf(1))))
        configure(gesture)
        runtime.down(1, 110f, 20f, 0)
        runtime.up(1, 110f, 20f, 10)
        assertEquals(setOf(1), sink.pressed)
        runtime.tick(110)
        assertTrue(sink.pressed.isEmpty())
        runtime.down(2, 110f, 20f, 120)
        runtime.move(2, 130f, 20f, 130)
        runtime.up(2, 110f, 20f, 140)
        assertTrue(sink.pressed.isEmpty())
        runtime.down(3, 110f, 20f, 150)
        runtime.up(3, 110f, 20f, 500)
        assertTrue(sink.pressed.isEmpty())
    }

    @Test fun unmappedGestureDirectionsFallBackToTheNearestMappedDirection() {
        val gesture = area(type = ControlType.GESTURE).copy(
            gestures = mapOf(
                GestureDirection.UP to GestureBinding(buttons = setOf(0)),
                GestureDirection.LEFT to GestureBinding(buttons = setOf(1))
            )
        )
        configure(gesture)
        runtime.down(1, 110f, 50f, 0)
        // Up-right is not mapped; the nearest mapped direction is UP.
        runtime.up(1, 160f, 0f, 10)
        assertEquals(setOf(0), sink.pressed)
    }

    @Test fun diagonalGestureVectorsHaveUnitMagnitude() {
        val gesture = area(type = ControlType.GESTURE).copy(
            diagonalGestures = true,
            gestures = mapOf(
                GestureDirection.UP_RIGHT to GestureBinding(stick = StickTarget.RIGHT, direction = GestureDirection.UP_LEFT)
            )
        )
        configure(gesture)
        runtime.down(1, 110f, 50f, 0)
        runtime.up(1, 160f, 0f, 10)
        assertVector(-0.70710677f, 0.70710677f)
        runtime.cancelAll()
        assertVector(0f, 0f)
    }

    @Test fun stickGesturePulseTakesOwnershipAndRestoresUnderlyingCurrentVector() {
        val stick = TouchControl(id = "stick", type = ControlType.STICK, bounds = TouchRect(0f, 0f, 100f, 100f), stickDeadzone = 0f, stickDiameter = 100f)
        val gesture = area(type = ControlType.GESTURE).copy(
            gestures = mapOf(
                GestureDirection.RIGHT to GestureBinding(stick = StickTarget.RIGHT, direction = GestureDirection.LEFT)
            )
        )
        configure(stick, gesture)
        runtime.down(1, 50f, 50f, 0)
        runtime.move(1, 100f, 50f, 1)
        runtime.down(2, 110f, 20f, 2)
        assertVector(1f, 0f)
        runtime.up(2, 150f, 20f, 10)
        assertVector(-1f, 0f)
        assertEquals("swipe", runtime.snapshot().stickOwners[StickTarget.RIGHT])
        runtime.move(1, 50f, 0f, 11)
        assertVector(-1f, 0f)
        runtime.tick(110)
        assertVector(0f, 1f)
    }

    @Test fun hiddenSwipeSamplesAreConsumedRatherThanReplayedAfterRestore() {
        val stick = TouchControl(id = "stick", type = ControlType.STICK, bounds = TouchRect(0f, 0f, 100f, 100f), stickDeadzone = 0f, stickDiameter = 100f)
        configure(stick, area())
        runtime.down(1, 110f, 20f, 0)
        runtime.down(2, 50f, 50f, 1)
        runtime.move(2, 100f, 50f, 2)
        runtime.move(1, 118f, 20f, 8)
        runtime.tick(8)
        assertVector(1f, 0f)
        runtime.tick(48)
        runtime.up(2, 100f, 50f, 49)
        assertVector(0f, 0f)
        runtime.tick(56)
        assertVector(0f, 0f)
    }

    @Test fun gestureOutputPressesAButtonCombination() {
        val gesture = area(type = ControlType.GESTURE).copy(
            gestures = mapOf(GestureDirection.TAP to GestureBinding(buttons = setOf(0, 1)))
        )
        configure(gesture)
        runtime.down(1, 110f, 20f, 0)
        runtime.up(1, 110f, 20f, 10)
        assertEquals(setOf(0, 1), sink.pressed)
    }

    @Test fun pointerCancelReleasesOnlyItsNativeTouchAndNeverFiresGesture() {
        val gesture = area(type = ControlType.GESTURE).copy(gestures = mapOf(GestureDirection.TAP to GestureBinding(buttons = setOf(1))))
        configure(button("button", 0f), gesture)
        runtime.down(1, 30f, 30f, 0)
        runtime.down(2, 900f, 900f, 0)
        runtime.down(3, 110f, 20f, 0)
        runtime.cancel(2)
        assertEquals(listOf("down:2", "up:2"), sink.native)
        assertEquals(setOf(0), sink.pressed)
        runtime.cancel(3)
        assertEquals(setOf(0), sink.pressed)
        assertEquals(1, runtime.snapshot().pointers.single().pointerId)
        runtime.cancel(1)
        assertTrue(sink.pressed.isEmpty())
    }

    @Test fun simultaneousGesturePulsesRestorePreviousActivePulse() {
        val a = area("a", 100f, ControlType.GESTURE).copy(
            gesturePulseMs = 100,
            gestures = mapOf(GestureDirection.TAP to GestureBinding(stick = StickTarget.RIGHT, direction = GestureDirection.LEFT))
        )
        val b = a.copy(
            id = "b",
            bounds = TouchRect(200f, 0f, 100f, 100f),
            gesturePulseMs = 20,
            gestures = mapOf(GestureDirection.TAP to GestureBinding(stick = StickTarget.RIGHT, direction = GestureDirection.UP))
        )
        configure(a, b)
        runtime.down(1, 110f, 20f, 0)
        runtime.up(1, 110f, 20f, 1)
        assertVector(-1f, 0f)
        runtime.down(2, 210f, 20f, 10)
        runtime.up(2, 210f, 20f, 11)
        assertVector(0f, 1f)
        runtime.tick(31)
        assertVector(-1f, 0f)
        assertEquals("a", runtime.snapshot().stickOwners[StickTarget.RIGHT])
        runtime.tick(101)
        assertVector(0f, 0f)
    }

    @Test fun roundingEpsilonAllowedAtScreenEdgeButRealOverflowRejected() {
        configure(button("edge", 940.001f))
        assertThrows(IllegalArgumentException::class.java) { configure(button("outside", 941f)) }
    }

    @Test fun invalidUpdatesAndConfigurationsLeaveCurrentStateUntouched() {
        configure(area())
        runtime.down(1, 110f, 20f, 0)
        assertThrows(IllegalArgumentException::class.java) { runtime.updatePresets(listOf(preset.copy(exponent = Float.NaN))) }
        assertEquals(1, runtime.snapshot().pointers.size)
    }

    @Test fun overlappingAreasKeepUpperLayerPriority() {
        configure(area(id = "lower"), area(id = "upper", x = 120f))
        runtime.down(1, 130f, 20f, 0)
        assertEquals("upper", runtime.snapshot().pointers.first().controlId)
    }

    @Test fun reconfigureAndCancelClearTurboPulsesAxesAndNativeTouches() {
        configure(button("turbo", 0f, turbo = true), area())
        runtime.down(1, 30f, 30f, 0)
        runtime.down(2, 110f, 20f, 0)
        runtime.down(3, 900f, 900f, 0)
        runtime.move(2, 118f, 20f, 8)
        runtime.tick(8)
        configure()
        assertTrue(sink.pressed.isEmpty())
        assertVector(0f, 0f)
        assertTrue(runtime.snapshot().pointers.isEmpty())
        assertEquals("up:3", sink.native.last())
        runtime.tick(200)
        assertTrue(sink.pressed.isEmpty())
    }
}
