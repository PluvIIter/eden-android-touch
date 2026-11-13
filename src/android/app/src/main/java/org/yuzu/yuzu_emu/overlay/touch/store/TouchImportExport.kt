// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.yuzu.yuzu_emu.overlay.touch.model.ControlType
import org.yuzu.yuzu_emu.overlay.touch.model.SwipePreset
import org.yuzu.yuzu_emu.overlay.touch.model.TouchLibrary
import org.yuzu.yuzu_emu.overlay.touch.model.TouchTemplate
import org.yuzu.yuzu_emu.overlay.touch.model.newTouchId

@Serializable
data class TouchBundle(
    val format: String = "eden-touch-bundle",
    val version: Int = TouchValidator.VERSION,
    val templates: List<TouchTemplate> = emptyList(),
    val presets: List<SwipePreset> = emptyList()
)

enum class ImportConflictResolution { COPY, REPLACE }
enum class TouchObjectKind { TEMPLATE, PRESET }
data class TouchImportConflict(
    val kind: TouchObjectKind,
    val id: String,
    val games: List<String> = emptyList(),
    val isDefault: Boolean = false,
    val regions: List<PresetReference> = emptyList()
)
data class TouchImportPreview(val bundle: TouchBundle, val conflicts: List<TouchImportConflict>)
data class TouchImportResult(
    val library: TouchLibrary,
    val templateIds: Map<String, String>,
    val presetIds: Map<String, String>
)

object TouchImportExport {
    internal val json = Json { encodeDefaults = true; prettyPrint = true }

    fun exportTemplate(library: TouchLibrary, templateId: String): String {
        TouchValidator.requireValid(library)
        val template = library.templates.firstOrNull { it.id == templateId } ?: error("Unknown template")
        val referenced = template.controls.filter { it.type == ControlType.SWIPE }.mapNotNull { it.swipePresetId }.toSet()
        return json.encodeToString(TouchBundle(templates = listOf(template), presets = library.presets.filter { it.id in referenced }))
    }

    fun exportPreset(library: TouchLibrary, presetId: String): String {
        TouchValidator.requireValid(library)
        val preset = library.presets.firstOrNull { it.id == presetId } ?: error("Unknown preset")
        return json.encodeToString(TouchBundle(presets = listOf(preset)))
    }

    fun preview(library: TouchLibrary, text: String): TouchImportPreview {
        TouchValidator.requireValid(library)
        val root = json.parseToJsonElement(text).jsonObject
        require(root["format"]?.jsonPrimitive?.content == "eden-touch-bundle") { "Unsupported touch bundle format" }
        require(root["version"]?.jsonPrimitive?.content == TouchValidator.VERSION.toString()) { "Unsupported touch bundle version" }
        requireStableIds(root)
        val bundle = json.decodeFromString<TouchBundle>(text)
        require(bundle.templates.isNotEmpty() || bundle.presets.isNotEmpty()) { "Bundle is empty" }
        val snapshot = TouchLibrary(templates = bundle.templates, presets = bundle.presets).immutableSnapshot()
        TouchValidator.requireValid(snapshot)
        val conflicts = mutableListOf<TouchImportConflict>()
        snapshot.templates.forEach { incoming ->
            val existing = library.templates.firstOrNull { it.id == incoming.id }
            if (existing != null && existing != incoming) {
                val references = TouchReferences.template(library, incoming.id)
                conflicts += TouchImportConflict(TouchObjectKind.TEMPLATE, incoming.id, references.gameIds, references.isDefault)
            }
        }
        snapshot.presets.forEach { incoming ->
            val existing = library.presets.firstOrNull { it.id == incoming.id }
            if (existing != null && existing != incoming) {
                conflicts += TouchImportConflict(
                    TouchObjectKind.PRESET,
                    incoming.id,
                    regions = TouchReferences.preset(library, incoming.id)
                )
            }
        }
        return TouchImportPreview(bundle.copy(templates = snapshot.templates, presets = snapshot.presets), conflicts.frozen())
    }

    internal fun requireStableIds(root: JsonObject) {
        fun requireId(value: JsonObject) {
            require(!value["id"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) { "Missing stable object ID" }
        }
        root["presets"]?.jsonArray?.forEach { requireId(it.jsonObject) }
        root["templates"]?.jsonArray?.forEach { element ->
            val template = element.jsonObject
            requireId(template)
            require(!template["baseScreenId"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) { "Missing base screen ID" }
            require(template["screens"]?.jsonArray?.isNotEmpty() == true) { "Missing saved screens" }
            template["screens"]?.jsonArray?.forEach { requireId(it.jsonObject) }
            template["controls"]?.jsonArray?.forEach { controlElement ->
                val control = controlElement.jsonObject
                requireId(control)
                require(control.containsKey("type")) { "Missing control type" }
                if (control["type"]?.jsonPrimitive?.content == ControlType.BUTTON_GROUP.name) {
                    require(control.containsKey("groupKeys")) { "Missing button group keys" }
                    control["groupKeys"]?.jsonArray?.forEach { requireId(it.jsonObject) }
                }
            }
        }
    }

    fun importBundle(library: TouchLibrary, text: String, resolution: ImportConflictResolution? = null): TouchImportResult {
        val preview = preview(library, text)
        require(preview.conflicts.isEmpty() || resolution != null) { "Conflicting IDs require explicit COPY or REPLACE" }
        val incoming = preview.bundle
        val presets = library.presets.toMutableList()
        val presetIds = incoming.presets.associate { preset ->
            val existing = library.presets.firstOrNull { it.id == preset.id }
            preset.id to if (existing != null && existing != preset && resolution == ImportConflictResolution.COPY) newTouchId() else preset.id
        }
        incoming.presets.forEach { preset ->
            val rewritten = preset.copy(id = presetIds.getValue(preset.id))
            val index = presets.indexOfFirst { it.id == rewritten.id }
            if (index < 0) presets += rewritten else presets[index] = rewritten
        }
        val templates = library.templates.toMutableList()
        val templateIds = linkedMapOf<String, String>()
        incoming.templates.forEach { template ->
            var rewritten = template.copy(
                controls = template.controls.map { control ->
                    if (control.type == ControlType.SWIPE) control.copy(swipePresetId = presetIds[control.swipePresetId] ?: control.swipePresetId) else control
                }
            )
            val index = templates.indexOfFirst { it.id == rewritten.id }
            if (index >= 0 && templates[index] != rewritten && resolution == ImportConflictResolution.COPY) {
                rewritten = TouchReferences.copyTemplate(rewritten)
            }
            templateIds[template.id] = rewritten.id
            val replacement = templates.indexOfFirst { it.id == rewritten.id }
            if (replacement < 0) templates += rewritten else templates[replacement] = rewritten
        }
        val result = library.copy(templates = templates, presets = presets).immutableSnapshot()
        TouchValidator.requireValid(result)
        return TouchImportResult(result, templateIds.frozen(), presetIds.frozen())
    }
}
