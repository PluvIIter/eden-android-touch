// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.store

import org.yuzu.yuzu_emu.overlay.touch.model.ButtonBinding
import org.yuzu.yuzu_emu.overlay.touch.model.ControlType
import org.yuzu.yuzu_emu.overlay.touch.model.TouchLibrary
import org.yuzu.yuzu_emu.overlay.touch.model.TouchTemplate
import org.yuzu.yuzu_emu.overlay.touch.model.newTouchId
import java.util.Collections

data class PresetReference(val templateId: String, val controlId: String)
data class TemplateReferences(val gameIds: List<String>, val isDefault: Boolean) {
    val count: Int get() = gameIds.size + if (isDefault) 1 else 0
}

object TouchReferences {
    fun baseGameId(titleId: String): String {
        val text = titleId.trim().removePrefix("0x").removePrefix("0X")
        require(text.length == 16 && text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "Expected a 16-digit hexadecimal Title ID" }
        return (text.toULong(16) and 0xFFFFFFFFFFFFE000uL).toString(16).uppercase().padStart(16, '0')
    }

    fun template(library: TouchLibrary, id: String): TemplateReferences = TemplateReferences(
        library.gameTemplates.filterValues { it == id }.keys.sorted(),
        library.defaultTemplateId == id
    )

    fun preset(library: TouchLibrary, id: String): List<PresetReference> =
        library.templates.flatMap { template ->
            template.controls.filter { it.type == ControlType.SWIPE && it.swipePresetId == id }
                .map { PresetReference(template.id, it.id) }
        }

    fun associateGame(library: TouchLibrary, titleId: String, templateId: String?): TouchLibrary {
        require(templateId == null || library.templates.any { it.id == templateId }) { "Unknown template" }
        val game = baseGameId(titleId)
        return library.copy(
            gameTemplates = library.gameTemplates.toMutableMap().apply {
                if (templateId == null) remove(game) else put(game, templateId)
            }
        ).immutableSnapshot()
    }

    fun deleteTemplate(library: TouchLibrary, id: String, replacementId: String? = null): TouchLibrary {
        require(library.templates.any { it.id == id }) { "Unknown template" }
        require(replacementId == null || replacementId != id && library.templates.any { it.id == replacementId }) { "Invalid replacement template" }
        return library.copy(
            templates = library.templates.filterNot { it.id == id },
            defaultTemplateId = if (library.defaultTemplateId == id) replacementId else library.defaultTemplateId,
            gameTemplates = library.gameTemplates.mapNotNull { (game, template) ->
                if (template != id) game to template else replacementId?.let { game to it }
            }.toMap()
        ).immutableSnapshot()
    }

    fun deletePreset(library: TouchLibrary, id: String, replacementId: String? = null, deleteReferencedControls: Boolean = false): TouchLibrary {
        require(library.presets.any { it.id == id }) { "Unknown preset" }
        require(replacementId == null || replacementId != id && library.presets.any { it.id == replacementId }) { "Invalid replacement preset" }
        require(preset(library, id).isEmpty() || replacementId != null || deleteReferencedControls) { "Referenced presets require a replacement or explicit control deletion" }
        return library.copy(
            presets = library.presets.filterNot { it.id == id },
            templates = library.templates.map { template ->
                val removed = template.controls.filter { it.type == ControlType.SWIPE && it.swipePresetId == id && replacementId == null }.map { it.id }.toSet()
                template.copy(
                    controls = template.controls.filterNot { it.id in removed }.map {
                        if (it.type == ControlType.SWIPE && it.swipePresetId == id) it.copy(swipePresetId = replacementId) else it
                    },
                    screens = template.screens.map { it.copy(placements = it.placements.filterKeys { key -> key !in removed }) }
                )
            }
        ).immutableSnapshot()
    }

    fun copyTemplate(template: TouchTemplate, name: String = "${template.name} copy"): TouchTemplate {
        val ids = (
            template.controls.map { it.id } + template.controls.flatMap { it.groupKeys.map { key -> key.id } } +
                template.screens.map { it.id } + template.id
            ).associateWith { newTouchId() }
        return template.copy(
            id = ids.getValue(template.id),
            name = name,
            controls = template.controls.map { control ->
                control.copy(
                    id = ids.getValue(control.id),
                    groupKeys = control.groupKeys.map { it.copy(id = ids.getValue(it.id)) },
                    targetTemplateId = control.targetTemplateId?.let { ids[it] ?: it }
                )
            },
            screens = template.screens.map { screen -> screen.copy(id = ids.getValue(screen.id), placements = screen.placements.mapKeys { ids.getValue(it.key) }) },
            baseScreenId = ids.getValue(template.baseScreenId)
        ).immutableSnapshot()
    }
}

internal fun <T> List<T>.frozen(): List<T> = Collections.unmodifiableList(ArrayList(this))
internal fun <K, V> Map<K, V>.frozen(): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(this))
private fun ButtonBinding.immutableSnapshot() = copy(buttons = Collections.unmodifiableSet(LinkedHashSet(buttons)))
internal fun TouchTemplate.immutableSnapshot(): TouchTemplate = copy(
    controls = controls.map { control ->
        control.copy(
            binding = control.binding.immutableSnapshot(),
            groupKeys = control.groupKeys.map { it.copy(binding = it.binding.immutableSnapshot()) }.frozen(),
            gestures = control.gestures.frozen()
        )
    }.frozen(),
    screens = screens.map { it.copy(placements = it.placements.frozen()) }.frozen()
)
internal fun TouchLibrary.immutableSnapshot(): TouchLibrary = copy(
    templates = templates.map { it.immutableSnapshot() }.frozen(),
    presets = presets.map { it.copy(curve = it.curve.frozen()) }.frozen(),
    gameTemplates = gameTemplates.frozen()
)
