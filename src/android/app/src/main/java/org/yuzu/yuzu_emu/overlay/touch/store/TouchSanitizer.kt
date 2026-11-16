// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.store

import org.yuzu.yuzu_emu.overlay.touch.model.TouchGeometry
import org.yuzu.yuzu_emu.overlay.touch.model.TouchLibrary
import org.yuzu.yuzu_emu.overlay.touch.model.TouchTemplate
import org.yuzu.yuzu_emu.overlay.touch.model.ButtonAction
import org.yuzu.yuzu_emu.overlay.touch.model.ButtonBinding
import org.yuzu.yuzu_emu.overlay.touch.model.AppearanceGroup
import org.yuzu.yuzu_emu.overlay.touch.model.AppearancePreset
import org.yuzu.yuzu_emu.overlay.touch.model.ControlType
import org.yuzu.yuzu_emu.overlay.touch.model.newTouchId
import kotlin.math.abs

data class RemovedControl(val id: String, val name: String, val reason: String)

data class SanitizedLibrary(val library: TouchLibrary, val removed: List<RemovedControl>)

/** Removes every control that fails validation; the caller reports the removed names. */
object TouchSanitizer {
    private val controlPath = Regex("""\.controls\.([^.]+)""")
    private val placementPath = Regex("""\.placements\.([^.]+)""")

    fun sanitize(library: TouchLibrary, density: Float = 1f): SanitizedLibrary {
        var current = library
        val removed = linkedMapOf<String, RemovedControl>()
        val appearancePresets = library.appearancePresets.toMutableList()
        current = current.copy(templates = current.templates.map { template ->
            val seen = mutableSetOf<String>()
            val kept = template.controls.filter { control ->
                if (control.id.isNotBlank() && seen.add(control.id)) {
                    true
                } else {
                    if (control.id.isNotBlank() && !removed.containsKey(control.id)) {
                        removed[control.id] = RemovedControl(
                            control.id,
                            control.name.ifBlank { control.text },
                            "Duplicate control ID"
                        )
                    }
                    false
                }
            }
            val ids = kept.map { it.id }.toSet()
            var legacyAppearancePresetId: String? = null
            val normalized = kept.map { control ->
                if (control.type == ControlType.VISIBILITY ||
                    control.buttonAction == ButtonAction.TOGGLE_VISIBILITY
                ) {
                    control.copy(
                        type = ControlType.BUTTON,
                        buttonAction = ButtonAction.NONE,
                        binding = ButtonBinding(setOf(0)),
                        targetTemplateId = null,
                        appearancePresetId = null,
                        enabled = false,
                        hideSelf = false
                    )
                } else if (control.buttonAction == ButtonAction.APPEARANCE) {
                    val presetId = control.appearancePresetId
                    if (presetId != null && appearancePresets.any { it.id == presetId }) {
                        control
                    } else if (template.appearanceGroups.isNotEmpty()) {
                        if (legacyAppearancePresetId == null) {
                            val preset = AppearancePreset(
                                id = newTouchId(),
                                name = "${template.name} appearance",
                                groups = template.appearanceGroups
                            )
                            appearancePresets += preset
                            legacyAppearancePresetId = preset.id
                        }
                        control.copy(appearancePresetId = legacyAppearancePresetId)
                    } else {
                        control.copy(
                            buttonAction = ButtonAction.NONE,
                            binding = ButtonBinding(setOf(0)),
                            appearancePresetId = null,
                            enabled = false
                        )
                    }
                } else {
                    control
                }
            }
            val inferredColor = normalized.mapNotNull { it.color }.distinct().singleOrNull()
            val inferredOpacity = if (template.globalOpacity == 1f) {
                normalized.map { it.opacity }.distinct().singleOrNull()
            } else {
                null
            }
            template.copy(
                controls = normalized,
                globalColor = template.globalColor ?: inferredColor,
                defaultOpacity = template.defaultOpacity ?: inferredOpacity,
                appearanceGroups = emptyList(),
                screens = template.screens.map { screen ->
                    screen.copy(placements = screen.placements.filterKeys { it in ids })
                }
            )
        })
        current = current.copy(appearancePresets = appearancePresets)
        repeat(4) {
            val issues = TouchValidator.validate(current)
            val visibleMismatch = mutableMapOf<String, String>()
            current.templates.forEach { template ->
                template.controls.filter { it.isVisibleStick }.forEach { control ->
                    val expected = control.stickDiameter * density
                    val invalid = template.screens.any { screen ->
                        val bounds = TouchGeometry.savedBounds(template, screen, control)
                        abs(bounds.width - expected) > 0.01f || abs(bounds.height - expected) > 0.01f
                    }
                    if (invalid) visibleMismatch[control.id] = "Visible stick must be a circle matching the stick size"
                }
            }
            val invalidIds = buildSet {
                addAll(issues.mapNotNull { issue ->
                    controlPath.find(issue.path)?.groupValues?.getOrNull(1)
                        ?: placementPath.find(issue.path)?.groupValues?.getOrNull(1)
                })
                addAll(visibleMismatch.keys)
            }
            if (invalidIds.isEmpty()) return SanitizedLibrary(current, removed.values.toList())

            val reasons = issues.associate { issue ->
                val id = controlPath.find(issue.path)?.groupValues?.getOrNull(1)
                    ?: placementPath.find(issue.path)?.groupValues?.getOrNull(1)
                id to issue.message
            } + visibleMismatch
            current.templates.forEach { template ->
                template.controls.filter { it.id in invalidIds }.forEach { control ->
                    if (!removed.containsKey(control.id)) {
                        removed[control.id] = RemovedControl(
                            control.id,
                            control.name.ifBlank { control.text },
                            reasons[control.id].orEmpty()
                        )
                    }
                }
            }
            current = current.copy(templates = current.templates.map { template -> template.without(invalidIds) })
        }
        return SanitizedLibrary(current, removed.values.toList())
    }

    private fun TouchTemplate.without(ids: Set<String>): TouchTemplate = copy(
        controls = controls.filterNot { it.id in ids },
        screens = screens.map { screen ->
            screen.copy(placements = screen.placements.filterKeys { it !in ids })
        }
    )
}
