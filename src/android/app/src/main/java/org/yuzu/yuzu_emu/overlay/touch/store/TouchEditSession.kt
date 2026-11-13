// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.store

import org.yuzu.yuzu_emu.overlay.touch.model.ControlType
import org.yuzu.yuzu_emu.overlay.touch.model.SwipePreset
import org.yuzu.yuzu_emu.overlay.touch.model.TouchLibrary
import org.yuzu.yuzu_emu.overlay.touch.model.TouchTemplate
import org.yuzu.yuzu_emu.overlay.touch.model.newTouchId

class TouchEditSession(library: TouchLibrary) {
    private var baseline = library.immutableSnapshot()
    private val undo = mutableListOf<TouchLibrary>()
    private val redo = mutableListOf<TouchLibrary>()
    var draft: TouchLibrary = baseline
        private set
    val expectedRevision: Long get() = baseline.revision
    val isDirty: Boolean get() = draft != baseline
    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun update(transform: (TouchLibrary) -> TouchLibrary) {
        val next = transform(draft).copy(version = baseline.version, revision = baseline.revision).immutableSnapshot()
        if (next == draft) return
        undo += draft
        draft = next
        redo.clear()
    }

    fun checkpoint(): TouchLibrary = draft

    fun restore(checkpoint: TouchLibrary) {
        update { checkpoint }
    }

    fun undo(): Boolean {
        if (undo.isEmpty()) return false
        redo += draft
        draft = undo.removeAt(undo.lastIndex)
        return true
    }

    fun redo(): Boolean {
        if (redo.isEmpty()) return false
        undo += draft
        draft = redo.removeAt(redo.lastIndex)
        return true
    }

    fun putTemplate(template: TouchTemplate) = update { library ->
        library.copy(
            templates = if (library.templates.any { it.id == template.id }) {
                library.templates.map { if (it.id == template.id) template else it }
            } else {
                library.templates + template
            }
        )
    }

    fun putPreset(preset: SwipePreset) = update { library ->
        library.copy(
            presets = if (library.presets.any { it.id == preset.id }) {
                library.presets.map { if (it.id == preset.id) preset else it }
            } else {
                library.presets + preset
            }
        )
    }

    fun associateGame(titleId: String, templateId: String?) = update { TouchReferences.associateGame(it, titleId, templateId) }

    fun setDefaultTemplate(templateId: String?) = update { library ->
        require(templateId == null || library.templates.any { it.id == templateId }) { "Unknown template" }
        library.copy(defaultTemplateId = templateId)
    }

    fun deleteTemplate(templateId: String, replacementId: String? = null) =
        update { TouchReferences.deleteTemplate(it, templateId, replacementId) }

    fun deletePreset(presetId: String, replacementId: String? = null, deleteReferencedControls: Boolean = false) =
        update { TouchReferences.deletePreset(it, presetId, replacementId, deleteReferencedControls) }

    fun deleteControl(templateId: String, controlId: String) = update { library ->
        library.copy(
            templates = library.templates.map { template ->
                if (template.id != templateId) {
                    template
                } else {
                    template.copy(
                        controls = template.controls.filterNot { it.id == controlId },
                        screens = template.screens.map { it.copy(placements = it.placements - controlId) }
                    )
                }
            }
        )
    }

    fun copyTemplateForGame(templateId: String, titleId: String, copyModifiedPresets: Boolean = true): String {
        var copiedId = ""
        update { library ->
            val original = library.templates.firstOrNull { it.id == templateId } ?: error("Unknown template")
            var copy = TouchReferences.copyTemplate(original)
            copiedId = copy.id
            val referenced = original.controls.filter { it.type == ControlType.SWIPE }.mapNotNull { it.swipePresetId }.toSet()
            val modified = if (copyModifiedPresets) {
                library.presets.filter { preset ->
                    preset.id in referenced && baseline.presets.any { it.id == preset.id && it != preset }
                }
            } else {
                emptyList()
            }
            val presetIds = modified.associate { it.id to newTouchId() }
            copy = copy.copy(
                controls = copy.controls.map { control ->
                    if (control.type == ControlType.SWIPE && control.swipePresetId in presetIds) {
                        control.copy(swipePresetId = presetIds.getValue(control.swipePresetId!!))
                    } else {
                        control
                    }
                }
            )
            val oldTemplate = baseline.templates.firstOrNull { it.id == templateId }
            val shared = library.copy(
                templates = library.templates.map { if (it.id == templateId && oldTemplate != null) oldTemplate else it } + copy,
                presets = library.presets.map { preset ->
                    if (preset.id in presetIds) baseline.presets.first { it.id == preset.id } else preset
                } + modified.map { it.copy(id = presetIds.getValue(it.id), name = "${it.name} copy") }
            )
            TouchReferences.associateGame(shared, titleId, copy.id)
        }
        return copiedId
    }

    fun importBundle(text: String, resolution: ImportConflictResolution? = null): TouchImportResult {
        val result = TouchImportExport.importBundle(draft, text, resolution)
        update { result.library }
        return result.copy(library = draft)
    }

    fun save(repository: TouchRepository): TouchLibrary {
        val saved = repository.save(draft, expectedRevision)
        baseline = saved
        draft = saved
        undo.clear()
        redo.clear()
        return saved
    }

    fun cancel(): TouchLibrary {
        draft = baseline
        undo.clear()
        redo.clear()
        return draft
    }
}
