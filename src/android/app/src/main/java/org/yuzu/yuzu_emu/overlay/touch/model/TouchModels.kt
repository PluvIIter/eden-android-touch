// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.model

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.min

fun newTouchId(): String = UUID.randomUUID().toString()

@Serializable
enum class ControlType { BUTTON, BUTTON_GROUP, STICK, SWIPE, GESTURE, VISIBILITY }

@Serializable
enum class ControlShape { CIRCLE, RECTANGLE }

@Serializable
enum class GroupStyle { FACE, DPAD }

@Serializable
enum class StickTarget { LEFT, RIGHT }

@Serializable
enum class AuxiliaryMode { PUSH, DOUBLE_TAP }

@Serializable
enum class GestureDirection { TAP, UP, UP_RIGHT, RIGHT, DOWN_RIGHT, DOWN, DOWN_LEFT, LEFT, UP_LEFT }

@Serializable
enum class GestureTiming { IMMEDIATE, RELEASE }

@Serializable
enum class MotionScale { DP, SHORT_EDGE }

@Serializable
data class TouchRect(
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 64f,
    val height: Float = 64f
) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height

    fun contains(px: Float, py: Float): Boolean =
        px >= x && px <= right && py >= y && py <= bottom

    fun intersects(other: TouchRect): Boolean =
        x < other.right && right > other.x && y < other.bottom && bottom > other.y
}

@Serializable
data class ButtonBinding(
    val buttons: Set<Int> = emptySet(),
    val turbo: Boolean = false,
    val frequencyHz: Float = 8f
)

@Serializable
data class GroupKey(
    val id: String = newTouchId(),
    val text: String = "",
    val binding: ButtonBinding = ButtonBinding()
)

@Serializable
data class GestureBinding(
    val buttons: Set<Int> = emptySet(),
    val stick: StickTarget? = null,
    val direction: GestureDirection = GestureDirection.UP
)

@Serializable
data class TouchControl(
    val id: String = newTouchId(),
    val name: String = "",
    val type: ControlType = ControlType.BUTTON,
    val bounds: TouchRect = TouchRect(),
    val enabled: Boolean = true,
    val locked: Boolean = false,
    val opacity: Float = 0.7f,
    val color: Int? = null,
    val haptics: Boolean = true,
    val hideSelf: Boolean = false,
    val showBorder: Boolean = false,
    val showText: Boolean = false,
    val showFill: Boolean = false,
    val text: String = "A",
    val shape: ControlShape = ControlShape.CIRCLE,
    val binding: ButtonBinding = ButtonBinding(setOf(0)),
    val groupStyle: GroupStyle = GroupStyle.FACE,
    val groupKeys: List<GroupKey> = listOf(
        GroupKey(text = "X", binding = ButtonBinding(setOf(2))),
        GroupKey(text = "A", binding = ButtonBinding(setOf(0))),
        GroupKey(text = "B", binding = ButtonBinding(setOf(1))),
        GroupKey(text = "Y", binding = ButtonBinding(setOf(3)))
    ),
    val stickTarget: StickTarget = StickTarget.RIGHT,
    val freeStick: Boolean = false,
    val triggerOnly: Boolean = false,
    val maximumDeflection: Boolean = false,
    val stickDeadzone: Float = 0.1f,
    val stickDiameter: Float = 96f,
    val auxiliaryButton: Int? = null,
    val auxiliaryMode: AuxiliaryMode = AuxiliaryMode.PUSH,
    val swipePresetId: String? = null,
    val gestures: Map<GestureDirection, GestureBinding> = emptyMap(),
    val diagonalGestures: Boolean = false,
    val gestureTiming: GestureTiming = GestureTiming.RELEASE,
    val gestureHold: Boolean = true,
    val gestureDistance: Float = 32f,
    val gesturePulseMs: Long = 100,
    val tapTimeoutMs: Long = 250,
    val tapSlop: Float = 12f
) {
    val isArea: Boolean
        get() = type == ControlType.SWIPE || type == ControlType.GESTURE ||
            (type == ControlType.STICK && triggerOnly)

    /** A visible stick is only a circle: its diameter is the trigger range and input range. */
    val isVisibleStick: Boolean
        get() = type == ControlType.STICK && !triggerOnly

    /** Square placement used by visible sticks; hidden sticks keep their rectangular touch area. */
    fun stickBounds(rect: TouchRect = bounds, density: Float = 1f): TouchRect {
        if (!isVisibleStick) return rect
        val diameter = stickDiameter * density
        return rect.copy(
            x = rect.x + rect.width / 2f - diameter / 2f,
            y = rect.y + rect.height / 2f - diameter / 2f,
            width = diameter,
            height = diameter
        )
    }
}

@Serializable
data class CurvePoint(val input: Float, val output: Float)

@Serializable
data class SwipePreset(
    val id: String = newTouchId(),
    val name: String = "Default camera",
    val sensitivityX: Float = 1f,
    val sensitivityY: Float = 1f,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
    val swapAxes: Boolean = false,
    val scale: MotionScale = MotionScale.SHORT_EDGE,
    val deadzone: Float = 0.15f,
    val exponent: Float = 1f,
    val curve: List<CurvePoint> = emptyList(),
    val maximumSpeed: Float = 2f,
    val smoothingMs: Float = 0f,
    val turnSmoothingMs: Float = 24f,
    val stopTimeoutMs: Long = 40,
    val notes: String = ""
)

@Serializable
data class ScreenLayout(
    val id: String = newTouchId(),
    val name: String = "Landscape",
    val width: Float = 960f,
    val height: Float = 432f,
    val placements: Map<String, TouchRect> = emptyMap()
)

@Serializable
data class TouchTemplate(
    val id: String = newTouchId(),
    val name: String = "Default layout",
    val controls: List<TouchControl> = emptyList(),
    val screens: List<ScreenLayout> = listOf(ScreenLayout()),
    val baseScreenId: String = screens.first().id,
    val globalOpacity: Float = 1f,
    val globalColor: Int? = null
)

@Serializable
data class TouchLibrary(
    val version: Int = 1,
    val revision: Long = 0,
    val templates: List<TouchTemplate> = emptyList(),
    val presets: List<SwipePreset> = emptyList(),
    val defaultTemplateId: String? = null,
    val gameTemplates: Map<String, String> = emptyMap()
) {
    fun templateFor(gameId: String?): TouchTemplate? {
        val id = gameId?.let { gameTemplates[it] } ?: defaultTemplateId
        return templates.firstOrNull { it.id == id }
    }
}

object TouchDefaults {
    fun library(): TouchLibrary {
        val preset = SwipePreset()
        val screen = ScreenLayout(
            name = "Landscape",
            width = 1280f,
            height = 720f
        )
        val faceKeys = listOf(
            GroupKey(text = "X", binding = ButtonBinding(setOf(2))),
            GroupKey(text = "A", binding = ButtonBinding(setOf(0))),
            GroupKey(text = "B", binding = ButtonBinding(setOf(1))),
            GroupKey(text = "Y", binding = ButtonBinding(setOf(3)))
        )
        val dpadKeys = listOf(
            GroupKey(text = "↑", binding = ButtonBinding(setOf(13))),
            GroupKey(text = "→", binding = ButtonBinding(setOf(14))),
            GroupKey(text = "↓", binding = ButtonBinding(setOf(15))),
            GroupKey(text = "←", binding = ButtonBinding(setOf(12)))
        )
        // The list order is bottom-to-top; runtime hit-testing always tries
        // ordinary controls before the lower area layer.
        val controls = listOf(
            TouchControl(
                name = "L3",
                text = "L3",
                bounds = TouchRect(250f, 470f, 64f, 64f),
                binding = ButtonBinding(setOf(4))
            ),
            TouchControl(
                name = "R3",
                text = "R3",
                bounds = TouchRect(966f, 470f, 64f, 64f),
                binding = ButtonBinding(setOf(5))
            ),
            TouchControl(
                name = "ZL",
                text = "ZL",
                shape = ControlShape.RECTANGLE,
                bounds = TouchRect(18f, 18f, 104f, 48f),
                binding = ButtonBinding(setOf(8))
            ),
            TouchControl(
                name = "L",
                text = "L",
                shape = ControlShape.RECTANGLE,
                bounds = TouchRect(130f, 18f, 88f, 44f),
                binding = ButtonBinding(setOf(6))
            ),
            TouchControl(
                name = "R",
                text = "R",
                shape = ControlShape.RECTANGLE,
                bounds = TouchRect(1062f, 18f, 88f, 44f),
                binding = ButtonBinding(setOf(7))
            ),
            TouchControl(
                name = "ZR",
                text = "ZR",
                shape = ControlShape.RECTANGLE,
                bounds = TouchRect(1158f, 18f, 104f, 48f),
                binding = ButtonBinding(setOf(9))
            ),
            TouchControl(
                name = "−",
                text = "−",
                shape = ControlShape.RECTANGLE,
                bounds = TouchRect(556f, 88f, 56f, 48f),
                binding = ButtonBinding(setOf(11))
            ),
            TouchControl(
                name = "+",
                text = "+",
                shape = ControlShape.RECTANGLE,
                bounds = TouchRect(644f, 88f, 56f, 48f),
                binding = ButtonBinding(setOf(10))
            ),
            TouchControl(
                name = "ABXY",
                type = ControlType.BUTTON_GROUP,
                groupStyle = GroupStyle.FACE,
                groupKeys = faceKeys,
                bounds = TouchRect(1044f, 468f, 200f, 200f)
            ),
            TouchControl(
                name = "D-pad",
                type = ControlType.BUTTON_GROUP,
                groupStyle = GroupStyle.DPAD,
                groupKeys = dpadKeys,
                bounds = TouchRect(36f, 486f, 190f, 190f)
            ),
            TouchControl(
                name = "Move",
                type = ControlType.STICK,
                bounds = TouchRect(0f, 0f, 640f, 720f),
                stickTarget = StickTarget.LEFT,
                freeStick = true,
                triggerOnly = true,
                stickDiameter = 110f
            ),
            TouchControl(
                name = "Camera",
                type = ControlType.SWIPE,
                bounds = TouchRect(640f, 0f, 640f, 720f),
                stickTarget = StickTarget.RIGHT,
                swipePresetId = preset.id
            )
        )
        val template = TouchTemplate(controls = controls, screens = listOf(screen), baseScreenId = screen.id)
        return TouchLibrary(templates = listOf(template), presets = listOf(preset), defaultTemplateId = template.id)
    }
}

object TouchGeometry {
    fun placements(template: TouchTemplate, width: Float, height: Float): Map<String, TouchRect> {
        val base = template.screens.firstOrNull { it.id == template.baseScreenId }
            ?: template.screens.firstOrNull() ?: ScreenLayout()
        val screen = template.screens.firstOrNull {
            kotlin.math.abs(it.width - width) < 0.5f && kotlin.math.abs(it.height - height) < 0.5f
        } ?: base
        val scale = min(width / screen.width, height / screen.height)
        val offsetX = (width - screen.width * scale) / 2f
        val offsetY = (height - screen.height * scale) / 2f
        return template.controls.associate { control ->
            val rect = savedBounds(template, screen, control)
            control.id to TouchRect(
                offsetX + rect.x * scale,
                offsetY + rect.y * scale,
                rect.width * scale,
                rect.height * scale
            )
        }
    }

    fun savedBounds(template: TouchTemplate, screen: ScreenLayout, control: TouchControl): TouchRect {
        screen.placements[control.id]?.let { return it }
        val base = template.screens.firstOrNull { it.id == template.baseScreenId }
            ?: template.screens.firstOrNull() ?: ScreenLayout()
        val rect = base.placements[control.id] ?: control.bounds
        if (screen.id == base.id) return rect
        val scale = min(screen.width / base.width, screen.height / base.height)
        return TouchRect(
            (screen.width - base.width * scale) / 2f + rect.x * scale,
            (screen.height - base.height * scale) / 2f + rect.y * scale,
            rect.width * scale,
            rect.height * scale
        )
    }
}
