// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.store

import org.yuzu.yuzu_emu.overlay.touch.model.ButtonBinding
import org.yuzu.yuzu_emu.overlay.touch.model.ControlShape
import org.yuzu.yuzu_emu.overlay.touch.model.ControlType
import org.yuzu.yuzu_emu.overlay.touch.model.CurvePoint
import org.yuzu.yuzu_emu.overlay.touch.model.GestureDirection
import org.yuzu.yuzu_emu.overlay.touch.model.TouchGeometry
import org.yuzu.yuzu_emu.overlay.touch.model.TouchLibrary
import org.yuzu.yuzu_emu.overlay.touch.model.TouchRect
import java.util.Collections

data class TouchValidationIssue(val path: String, val message: String)

class TouchValidationException(val issues: List<TouchValidationIssue>) :
    IllegalArgumentException(issues.joinToString("; ") { "${it.path}: ${it.message}" })

object TouchValidator {
    const val VERSION = 1
    const val MIN_FREQUENCY_HZ = 1f
    const val MAX_FREQUENCY_HZ = 60f
    val buttonTargets: Set<Int> = Collections.unmodifiableSet((0..15).toSet() + setOf(18, 19))

    fun requireValid(library: TouchLibrary) {
        val issues = validate(library)
        if (issues.isNotEmpty()) throw TouchValidationException(issues)
    }

    fun validate(library: TouchLibrary): List<TouchValidationIssue> {
        val issues = mutableListOf<TouchValidationIssue>()
        fun check(valid: Boolean, path: String, message: String) {
            if (!valid) issues += TouchValidationIssue(path, message)
        }
        fun ids(values: List<String>, path: String) {
            check(values.all { it.isNotBlank() }, path, "IDs must not be blank")
            check(values.distinct().size == values.size, path, "Duplicate IDs")
        }
        fun range(value: Float, min: Float, max: Float, path: String) =
            check(value.isFinite() && value in min..max, path, "Must be finite in $min..$max")
        fun positive(value: Float, path: String) =
            check(value.isFinite() && value > 0f, path, "Must be finite and positive")
        fun binding(value: ButtonBinding, path: String) {
            check(value.buttons.isNotEmpty() && value.buttons.all { it in buttonTargets }, path, "Invalid or empty button mapping")
            range(value.frequencyHz, MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ, "$path.frequencyHz")
        }
        fun rect(value: TouchRect, width: Float, height: Float, path: String) {
            check(
                listOf(value.x, value.y, value.width, value.height, value.right, value.bottom).all { it.isFinite() } &&
                    value.x >= 0f && value.y >= 0f && value.width > 0f && value.height > 0f &&
                    value.right <= width && value.bottom <= height,
                path,
                "Rectangle must be finite, positive and inside the screen"
            )
        }
        check(library.version == VERSION, "version", "Unsupported library version ${library.version}")
        check(library.revision >= 0, "revision", "Revision must not be negative")
        ids(library.templates.map { it.id }, "templates")
        ids(library.presets.map { it.id }, "presets")
        val templateIds = library.templates.map { it.id }.toSet()
        val presetIds = library.presets.map { it.id }.toSet()
        check(library.defaultTemplateId == null || library.defaultTemplateId in templateIds, "defaultTemplateId", "Missing template")
        library.gameTemplates.forEach { (game, template) ->
            check(runCatching { TouchReferences.baseGameId(game) == game }.getOrDefault(false), "gameTemplates.$game", "Expected canonical base Title ID")
            check(template in templateIds, "gameTemplates.$game", "Missing template")
        }
        library.presets.forEach { preset ->
            val p = "presets.${preset.id}"
            check(preset.name.isNotBlank(), "$p.name", "Name must not be blank")
            positive(preset.sensitivityX, "$p.sensitivityX")
            positive(preset.sensitivityY, "$p.sensitivityY")
            check(preset.deadzone.isFinite() && preset.deadzone >= 0f && preset.deadzone < 1f, "$p.deadzone", "Must be finite in [0, 1)")
            positive(preset.exponent, "$p.exponent")
            positive(preset.maximumSpeed, "$p.maximumSpeed")
            range(preset.smoothingMs, 0f, 10000f, "$p.smoothingMs")
            check(preset.stopTimeoutMs in 1..10000, "$p.stopTimeoutMs", "Must be 1..10000 ms")
            check(preset.maxAccumulationMs in 1..1000, "$p.maxAccumulationMs", "Must be 1..1000 ms")
            if (preset.curve.isNotEmpty()) {
                check(
                    preset.curve.size >= 2 && preset.curve.first() == CurvePoint(0f, 0f) &&
                        preset.curve.last() == CurvePoint(1f, 1f),
                    "$p.curve",
                    "Curve must start at (0, 0) and end at (1, 1)"
                )
                preset.curve.forEachIndexed { index, point ->
                    range(point.input, 0f, 1f, "$p.curve.$index.input")
                    range(point.output, 0f, 1f, "$p.curve.$index.output")
                }
                check(
                    preset.curve.zipWithNext().all { (a, b) -> a.input < b.input && a.output <= b.output },
                    "$p.curve",
                    "Inputs must increase strictly and outputs must not decrease"
                )
            }
        }
        library.templates.forEach { template ->
            val t = "templates.${template.id}"
            check(template.name.isNotBlank(), "$t.name", "Name must not be blank")
            ids(template.controls.map { it.id }, "$t.controls")
            ids(template.screens.map { it.id }, "$t.screens")
            check(template.screens.isNotEmpty(), "$t.screens", "At least one saved screen is required")
            check(template.screens.any { it.id == template.baseScreenId }, "$t.baseScreenId", "Missing base screen")
            val controlIds = template.controls.map { it.id }.toSet()
            val childIds = template.controls.filter { it.type == ControlType.BUTTON_GROUP }.flatMap { it.groupKeys.map { key -> key.id } }
            ids(template.controls.map { it.id } + childIds, "$t.controlAndKeyIds")
            template.controls.forEach { control ->
                val c = "$t.controls.${control.id}"
                range(control.opacity, 0f, 1f, "$c.opacity")
                check(!control.triggerOnly || (control.type == ControlType.STICK && control.freeStick), "$c.triggerOnly", "Trigger-only display requires a free stick")
                when (control.type) {
                    ControlType.BUTTON -> binding(control.binding, "$c.binding")
                    ControlType.BUTTON_GROUP -> {
                        check(control.groupKeys.size == 4, "$c.groupKeys", "A button group requires four keys")
                        control.groupKeys.forEach { binding(it.binding, "$c.groupKeys.${it.id}") }
                    }
                    ControlType.STICK -> {
                        check(
                            control.stickDeadzone.isFinite() && control.stickDeadzone >= 0f && control.stickDeadzone < 1f,
                            "$c.stickDeadzone",
                            "Must be finite in [0, 1)"
                        )
                        positive(control.stickDiameter, "$c.stickDiameter")
                        check(control.auxiliaryButton == null || control.auxiliaryButton in buttonTargets, "$c.auxiliaryButton", "Invalid button")
                        check(control.tapTimeoutMs in 1..10000, "$c.tapTimeoutMs", "Must be 1..10000 ms")
                        range(control.tapSlop, 0f, Float.MAX_VALUE, "$c.tapSlop")
                    }
                    ControlType.SWIPE -> check(control.swipePresetId in presetIds, "$c.swipePresetId", "Missing swipe preset")
                    ControlType.GESTURE -> {
                        check(control.gestures.isNotEmpty(), "$c.gestures", "At least one gesture mapping is required")
                        positive(control.gestureDistance, "$c.gestureDistance")
                        check(control.gesturePulseMs in 1..10000, "$c.gesturePulseMs", "Must be 1..10000 ms")
                        check(control.tapTimeoutMs in 1..10000, "$c.tapTimeoutMs", "Must be 1..10000 ms")
                        range(control.tapSlop, 0f, Float.MAX_VALUE, "$c.tapSlop")
                        control.gestures.forEach { (direction, target) ->
                            check((target.button != null) xor (target.stick != null), "$c.gestures.$direction", "Choose exactly one button or stick target")
                            check(target.button == null || target.button in buttonTargets, "$c.gestures.$direction", "Invalid button")
                            check(target.stick == null || target.direction != GestureDirection.TAP, "$c.gestures.$direction", "Stick output requires one of eight directions")
                        }
                    }
                }
            }
            val base = template.screens.firstOrNull { it.id == template.baseScreenId }
            if (base != null) template.controls.forEach { rect(it.bounds, base.width, base.height, "$t.controls.${it.id}.bounds") }
            template.screens.forEach { screen ->
                val s = "$t.screens.${screen.id}"
                positive(screen.width, "$s.width")
                positive(screen.height, "$s.height")
                check(screen.placements.keys.all { it in controlIds }, "$s.placements", "Unknown control reference")
                template.controls.forEach { control ->
                    val bounds = TouchGeometry.savedBounds(template, screen, control)
                    rect(bounds, screen.width, screen.height, "$s.placements.${control.id}")
                    check(
                        control.type != ControlType.BUTTON || control.shape != ControlShape.CIRCLE ||
                            kotlin.math.abs(bounds.width - bounds.height) < 0.001f,
                        "$s.placements.${control.id}",
                        "Circular buttons must have equal width and height"
                    )
                }
                val areas = template.controls.filter { it.isArea }
                areas.forEachIndexed { index, a ->
                    areas.drop(index + 1).forEach { b ->
                        check(
                            !TouchGeometry.savedBounds(template, screen, a).intersects(TouchGeometry.savedBounds(template, screen, b)),
                            "$s.areas.${a.id}.${b.id}",
                            "Areas must not have positive-area overlap"
                        )
                    }
                }
            }
        }
        return issues.toList()
    }
}
