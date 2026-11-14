// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.store

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yuzu.yuzu_emu.overlay.touch.model.ButtonBinding
import org.yuzu.yuzu_emu.overlay.touch.model.ControlType
import org.yuzu.yuzu_emu.overlay.touch.model.CurvePoint
import org.yuzu.yuzu_emu.overlay.touch.model.GestureBinding
import org.yuzu.yuzu_emu.overlay.touch.model.GestureDirection
import org.yuzu.yuzu_emu.overlay.touch.model.ScreenLayout
import org.yuzu.yuzu_emu.overlay.touch.model.StickTarget
import org.yuzu.yuzu_emu.overlay.touch.model.SwipePreset
import org.yuzu.yuzu_emu.overlay.touch.model.TouchControl
import org.yuzu.yuzu_emu.overlay.touch.model.TouchDefaults
import org.yuzu.yuzu_emu.overlay.touch.model.TouchGeometry
import org.yuzu.yuzu_emu.overlay.touch.model.TouchLibrary
import org.yuzu.yuzu_emu.overlay.touch.model.TouchRect
import org.yuzu.yuzu_emu.overlay.touch.model.TouchTemplate
import org.yuzu.yuzu_emu.overlay.touch.model.newTouchId
import java.io.File
import java.nio.file.Files

class TouchStoreTest {
    private fun repository(): TouchRepository = TouchRepository(File(Files.createTempDirectory("touch-store-test").toFile(), "library.json"))

    private inline fun <reified T : Throwable> fails(block: () -> Unit): T {
        try { block() } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        throw AssertionError("Expected ${T::class.java.name}")
    }

    private fun library(controls: List<TouchControl>, presets: List<SwipePreset> = emptyList()): TouchLibrary {
        val screen = ScreenLayout(width = 400f, height = 300f)
        val template = TouchTemplate(controls = controls, screens = listOf(screen), baseScreenId = screen.id)
        return TouchLibrary(templates = listOf(template), presets = presets, defaultTemplateId = template.id)
    }

    @Test fun defaultsAreValidAndStableAcrossRepositoryInstances() {
        val directory = Files.createTempDirectory("touch-defaults-test").toFile()
        val file = File(directory, "nested/library.json")
        val first = TouchRepository(file).load()
        assertTrue(TouchValidator.validate(first).isEmpty())
        assertEquals(first, TouchRepository(file).load())
        assertEquals(0, first.revision)
    }

    @Test fun savesIncrementRevisionAndRejectStaleWriters() {
        val repo = repository()
        val first = repo.load()
        val updated = repo.save(first.copy(templates = first.templates.map { it.copy(name = "Saved") }), first.revision)
        assertEquals(1, updated.revision)
        val conflict = fails<TouchRevisionConflictException> { repo.save(first, first.revision) }
        assertEquals(1, conflict.actualRevision)
        assertEquals(updated, repo.load())
    }

    @Test fun invalidSavePreservesPreviousBytes() {
        val directory = Files.createTempDirectory("touch-invalid-test").toFile()
        val file = File(directory, "library.json")
        val repo = TouchRepository(file)
        val first = repo.load()
        val bytes = file.readBytes()
        fails<TouchValidationException> { repo.save(first.copy(defaultTemplateId = "missing"), 0) }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun corruptAndUnsupportedLibraryAreNotReplacedWithDefaults() {
        val file = File(Files.createTempDirectory("touch-corrupt-test").toFile(), "library.json")
        file.writeText("{\"version\":99,\"revision\":0}")
        fails<IllegalArgumentException> { TouchRepository(file).load() }
        assertEquals("{\"version\":99,\"revision\":0}", file.readText())
        file.writeText("not json")
        fails<IllegalArgumentException> { TouchRepository(file).load() }
        assertEquals("not json", file.readText())
    }

    @Test fun draftCancelAndLocalCheckpointNeverPublish() {
        val repo = repository()
        val original = repo.load()
        val session = repo.editSession()
        val checkpoint = session.checkpoint()
        session.putPreset(original.presets.first().copy(sensitivityX = 2f))
        assertTrue(session.isDirty)
        assertEquals(original, repo.load())
        session.restore(checkpoint)
        assertFalse(session.isDirty)
        session.putTemplate(original.templates.first().copy(name = "Draft"))
        session.cancel()
        assertEquals(original, session.draft)
        assertEquals(original, repo.load())
    }

    @Test fun snapshotCopiesAndFreezesNestedCollections() {
        val buttons = mutableSetOf(0)
        val placements = mutableMapOf<String, TouchRect>()
        val control = TouchControl(binding = ButtonBinding(buttons))
        val original = library(listOf(control))
        val screen = original.templates.first().screens.first().copy(placements = placements)
        val controls = mutableListOf(control)
        val session = TouchEditSession(original.copy(templates = listOf(original.templates.first().copy(controls = controls, screens = listOf(screen)))))
        buttons += 1
        placements[control.id] = TouchRect(2f, 2f)
        controls.clear()
        assertEquals(setOf(0), session.draft.templates.first().controls.first().binding.buttons)
        assertTrue(session.draft.templates.first().screens.first().placements.isEmpty())
        fails<UnsupportedOperationException> { (session.draft.templates as MutableList).clear() }
        fails<UnsupportedOperationException> { (session.draft.templates.first().controls.first().binding.buttons as MutableSet).clear() }
    }

    @Test fun undoRedoAndSaveAreOneTransaction() {
        val repo = repository()
        val session = repo.editSession()
        val template = session.draft.templates.first()
        session.putTemplate(template.copy(name = "Changed"))
        session.associateGame("0100000000000800", template.id)
        assertTrue(session.undo())
        assertTrue(session.draft.gameTemplates.isEmpty())
        assertTrue(session.redo())
        val saved = session.save(repo)
        assertEquals(template.id, saved.gameTemplates["0100000000000000"])
        assertEquals("Changed", saved.templates.first().name)
        assertFalse(session.canUndo)
        assertFalse(session.isDirty)
    }

    @Test fun failedDraftSaveRetainsDraftAndHistory() {
        val repo = repository()
        val session = repo.editSession()
        session.putTemplate(session.draft.templates.first().copy(name = ""))
        fails<TouchValidationException> { session.save(repo) }
        assertTrue(session.isDirty)
        assertTrue(session.canUndo)
        assertTrue(repo.load().templates.first().name.isNotBlank())
    }

    @Test fun templateReferencesAndDeletionHaveNoDanglingAssociations() {
        val initial = TouchDefaults.library()
        val id = initial.templates.first().id
        val copy = TouchReferences.copyTemplate(initial.templates.first())
        var original = initial.copy(templates = initial.templates + copy)
        original = TouchReferences.associateGame(original, "0100000000000000", id)
        original = TouchReferences.associateGame(original, "0100000000002000", id)
        assertEquals(3, TouchReferences.template(original, id).count)
        val replaced = TouchReferences.deleteTemplate(original, id, copy.id)
        TouchValidator.requireValid(replaced)
        assertEquals(copy.id, replaced.defaultTemplateId)
        assertTrue(replaced.gameTemplates.values.all { it == copy.id })
        val unlinked = TouchReferences.deleteTemplate(original, id)
        assertTrue(unlinked.gameTemplates.isEmpty())
        assertNull(unlinked.defaultTemplateId)
    }

    @Test fun presetDeletionRequiresExplicitReplacementOrRegionDeletion() {
        val original = TouchDefaults.library()
        val preset = original.presets.first()
        assertEquals(1, TouchReferences.preset(original, preset.id).size)
        fails<IllegalArgumentException> { TouchReferences.deletePreset(original, preset.id) }
        val other = preset.copy(id = newTouchId())
        val replaced = TouchReferences.deletePreset(original.copy(presets = listOf(preset, other)), preset.id, other.id)
        assertEquals(other.id, replaced.templates.first().controls.first { it.type == ControlType.SWIPE }.swipePresetId)
        val removed = TouchReferences.deletePreset(original, preset.id, deleteReferencedControls = true)
        TouchValidator.requireValid(removed)
        assertFalse(removed.templates.first().controls.any { it.type == ControlType.SWIPE })
    }

    @Test fun copiedTemplateRewritesControlScreenKeyIdsAndPlacements() {
        val original = TouchDefaults.library().templates.first()
        val base = original.screens.first()
        val withPlacements = original.copy(screens = listOf(base.copy(placements = original.controls.associate { it.id to it.bounds })))
        val copy = TouchReferences.copyTemplate(withPlacements)
        assertNotEquals(original.id, copy.id)
        assertNotEquals(original.baseScreenId, copy.baseScreenId)
        assertTrue(copy.controls.none { c -> original.controls.any { it.id == c.id } })
        assertEquals(copy.controls.map { it.id }.toSet(), copy.screens.first().placements.keys)
        assertEquals(original.controls.first { it.type == ControlType.SWIPE }.swipePresetId, copy.controls.first { it.type == ControlType.SWIPE }.swipePresetId)
    }

    @Test fun copyForGameSeparatesModifiedSharedPresetsWithoutPublishingOriginalChanges() {
        val original = TouchDefaults.library()
        val session = TouchEditSession(original)
        session.putTemplate(original.templates.first().copy(name = "Independent"))
        session.putPreset(original.presets.first().copy(sensitivityX = 3f))
        val copyId = session.copyTemplateForGame(original.templates.first().id, "0100000000000800")
        assertEquals(original.templates.first(), session.draft.templates.first { it.id == original.templates.first().id })
        assertEquals(original.presets.first(), session.draft.presets.first { it.id == original.presets.first().id })
        val copied = session.draft.templates.first { it.id == copyId }
        val presetId = copied.controls.first { it.type == ControlType.SWIPE }.swipePresetId
        assertNotEquals(original.presets.first().id, presetId)
        assertEquals(3f, session.draft.presets.first { it.id == presetId }.sensitivityX)
        TouchValidator.requireValid(session.draft)
    }

    @Test fun exportIncludesDependenciesAndSameContentImportsReuseIds() {
        val original = TouchDefaults.library()
        val text = TouchImportExport.exportTemplate(original, original.templates.first().id)
        val preview = TouchImportExport.preview(original, text)
        assertEquals(original.presets, preview.bundle.presets)
        assertTrue(preview.conflicts.isEmpty())
        val imported = TouchImportExport.importBundle(original, text)
        assertEquals(original, imported.library)
    }

    @Test fun conflictCopyRewritesSharedPresetOnceAndLeavesGamesUntouched() {
        val original = TouchDefaults.library()
        val preset = original.presets.first()
        val t = original.templates.first()
        // The shipped layout fills the screen with two areas, so this scenario builds a minimal
        // template with two non-overlapping swipe areas.
        val area = t.controls.first { it.type == ControlType.SWIPE }.copy(bounds = TouchRect(0f, 0f, 100f, 100f))
        val base = t.copy(controls = t.controls.filterNot { it.isArea } + area)
        val added = area.copy(id = newTouchId(), bounds = TouchRect(200f, 0f, 100f, 100f))
        val incoming = original.copy(
            templates = listOf(base.copy(name = "Imported", controls = base.controls + added)),
            presets = listOf(preset.copy(sensitivityX = 2f))
        )
        val text = TouchImportExport.exportTemplate(incoming, base.id)
        fails<IllegalArgumentException> { TouchImportExport.importBundle(original, text) }
        val associated = TouchReferences.associateGame(original, "0100000000000000", t.id)
        val result = TouchImportExport.importBundle(associated, text, ImportConflictResolution.COPY)
        assertEquals(associated.gameTemplates, result.library.gameTemplates)
        assertEquals(2, result.library.presets.size)
        assertEquals(2, result.library.templates.size)
        val copied = result.library.templates.first { it.id == result.templateIds[t.id] }
        val references = copied.controls.filter { it.type == ControlType.SWIPE }.map { it.swipePresetId }
        assertEquals(1, references.distinct().size)
        assertEquals(result.presetIds[preset.id], references.first())
        assertNotEquals(preset.id, references.first())
        TouchValidator.requireValid(result.library)
    }

    @Test fun conflictReplaceReportsReferencesAndRetainsExistingIds() {
        val original = TouchDefaults.library()
        val t = original.templates.first()
        val associated = TouchReferences.associateGame(original, "0100000000000000", t.id)
        val incoming = associated.copy(templates = listOf(t.copy(name = "Replaced")), presets = associated.presets.map { it.copy(sensitivityY = 2f) })
        val text = TouchImportExport.exportTemplate(incoming, t.id)
        val preview = TouchImportExport.preview(associated, text)
        assertEquals(listOf("0100000000000000"), preview.conflicts.first { it.kind == TouchObjectKind.TEMPLATE }.games)
        assertEquals(1, preview.conflicts.first { it.kind == TouchObjectKind.PRESET }.regions.size)
        val result = TouchImportExport.importBundle(associated, text, ImportConflictResolution.REPLACE)
        assertEquals(associated.gameTemplates, result.library.gameTemplates)
        assertEquals(t.id, result.library.templates.single().id)
        assertEquals("Replaced", result.library.templates.single().name)
    }

    @Test fun malformedImportNeverChangesDisk() {
        val repo = repository()
        val original = repo.load()
        fails<IllegalArgumentException> { repo.importBundle("{\"format\":\"eden-touch-bundle\",\"version\":99}", 0) }
        val broken = TouchBundle(templates = original.templates)
        fails<TouchValidationException> { repo.importBundle(TouchImportExport.json.encodeToString(broken), 0, ImportConflictResolution.REPLACE) }
        assertEquals(original, repo.load())
    }

    @Test fun presetStandaloneBundleAndRepositoryImportPersist() {
        val source = TouchDefaults.library()
        val target = repository()
        val initial = target.load()
        val text = TouchImportExport.exportPreset(source, source.presets.first().id)
        val imported = target.importBundle(text, initial.revision)
        assertEquals(1, imported.library.revision)
        assertEquals(2, imported.library.presets.size)
        assertEquals(imported.library, target.load())
    }

    @Test fun areasAllowOverlapAndTouchingEdges() {
        val preset = SwipePreset()
        val a = TouchControl(type = ControlType.SWIPE, bounds = TouchRect(0f, 0f, 100f, 100f), swipePresetId = preset.id)
        val b = TouchControl(type = ControlType.SWIPE, bounds = TouchRect(100f, 0f, 100f, 100f), swipePresetId = preset.id, opacity = 0f)
        TouchValidator.requireValid(library(listOf(a, b, TouchControl()), listOf(preset)))
        TouchValidator.requireValid(library(listOf(a, b.copy(bounds = TouchRect(99f, 0f, 100f, 100f))), listOf(preset)))
    }

    @Test fun alternateSavedScreensAreValidatedAndUnknownPlacementsRejected() {
        val original = library(listOf(TouchControl()))
        val t = original.templates.first()
        val control = t.controls.first()
        val second = ScreenLayout(width = 100f, height = 100f, placements = mapOf(control.id to TouchRect(90f, 0f, 64f, 64f)))
        fails<TouchValidationException> { TouchValidator.requireValid(original.copy(templates = listOf(t.copy(screens = t.screens + second)))) }
        val bad = t.screens.first().copy(placements = mapOf("missing" to TouchRect()))
        fails<TouchValidationException> { TouchValidator.requireValid(original.copy(templates = listOf(t.copy(screens = listOf(bad))))) }
    }

    @Test fun geometryTargetsModesAndDuplicateIdsAreRejected() {
        val bad = listOf(
            TouchControl(bounds = TouchRect(Float.NaN, 0f)),
            TouchControl(bounds = TouchRect(0f, 0f, -1f, 1f)),
            TouchControl(binding = ButtonBinding(emptySet())),
            TouchControl(binding = ButtonBinding(setOf(16))),
            TouchControl(binding = ButtonBinding(setOf(0), true, Float.POSITIVE_INFINITY)),
            TouchControl(binding = ButtonBinding(setOf(0), true, 61f)),
            TouchControl(type = ControlType.SWIPE, swipePresetId = "missing"),
            TouchControl(type = ControlType.GESTURE, gestures = mapOf(GestureDirection.UP to GestureBinding(button = 0, stick = StickTarget.RIGHT))),
            TouchControl(type = ControlType.GESTURE, gesturePulseMs = 0, gestures = mapOf(GestureDirection.TAP to GestureBinding(button = 0)))
        )
        bad.forEach { control -> assertTrue("Expected validation error for $control", TouchValidator.validate(library(listOf(control))).isNotEmpty()) }
        val fixedTriggerIssues = TouchValidator.validate(library(listOf(TouchControl(type = ControlType.STICK, triggerOnly = true))))
        assertTrue("$fixedTriggerIssues", fixedTriggerIssues.isEmpty())
        val control = TouchControl()
        fails<TouchValidationException> { TouchValidator.requireValid(library(listOf(control, control))) }
    }

    @Test fun simultaneousRepositoryInstancesCannotBothCommitSameRevision() {
        val file = File(Files.createTempDirectory("touch-race-test").toFile(), "library.json")
        val first = TouchRepository(file)
        val second = TouchRepository(file)
        val initial = first.load()
        val start = java.util.concurrent.CountDownLatch(1)
        val results = java.util.Collections.synchronizedList(mutableListOf<Throwable?>())
        val threads = listOf(first, second).map { repo ->
            Thread {
                start.await()
                results += runCatching { repo.save(initial, initial.revision) }.exceptionOrNull()
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, results.count { it == null })
        assertEquals(1, results.count { it is TouchRevisionConflictException })
        assertEquals(1, first.load().revision)
    }

    @Test fun sameIdTemplateMustCopyWhenItsDependencyWasCopied() {
        val original = TouchDefaults.library()
        val t = original.templates.first()
        val incoming = original.copy(presets = original.presets.map { it.copy(sensitivityX = 5f) })
        val text = TouchImportExport.exportTemplate(incoming, t.id)
        val result = TouchImportExport.importBundle(original, text, ImportConflictResolution.COPY)
        assertNotEquals(t.id, result.templateIds[t.id])
        assertEquals(t, result.library.templates.first { it.id == t.id })
        assertEquals(2, result.library.templates.size)
        TouchValidator.requireValid(result.library)
    }

    @Test fun copyBundlePreservesCrossTemplatePresetSharing() {
        val original = TouchDefaults.library()
        val first = original.templates.first()
        val second = TouchReferences.copyTemplate(first)
        val preset = original.presets.first().copy(sensitivityX = 4f)
        val text = TouchImportExport.json.encodeToString(TouchBundle(templates = listOf(first.copy(name = "First imported"), second), presets = listOf(preset)))
        val result = TouchImportExport.importBundle(original, text, ImportConflictResolution.COPY)
        val imported = result.library.templates.filter { it.id in result.templateIds.values }
        assertEquals(2, imported.size)
        assertEquals(1, imported.flatMap { it.controls }.filter { it.type == ControlType.SWIPE }.map { it.swipePresetId }.distinct().size)
    }

    @Test fun geometryRoundTripNeverMutatesSavedLayoutsOrAccumulatesError() {
        val library = TouchDefaults.library()
        val template = library.templates.first()
        val base = template.screens.first()
        val before = TouchImportExport.exportTemplate(library, template.id)
        val expected = TouchGeometry.placements(template, base.width, base.height)
        repeat(20) {
            val portrait = TouchGeometry.placements(template, base.height, base.width)
            portrait.values.forEach { bounds ->
                assertTrue(bounds.x >= 0f && bounds.y >= 0f && bounds.right <= base.height && bounds.bottom <= base.width)
            }
            val areas = template.controls.filter { it.isArea }.map { portrait.getValue(it.id) }
            areas.forEachIndexed { index, a -> areas.drop(index + 1).forEach { assertFalse(a.intersects(it)) } }
            assertEquals(expected, TouchGeometry.placements(template, base.width, base.height))
        }
        assertEquals(before, TouchImportExport.exportTemplate(library, template.id))
    }

    @Test fun matchingSavedScreenRestoresExplicitGeometry() {
        val original = TouchDefaults.library().templates.first()
        val portrait = ScreenLayout(
            width = 432f,
            height = 960f,
            placements = TouchGeometry.placements(original, 432f, 960f)
        )
        val template = original.copy(screens = original.screens + portrait)
        repeat(20) {
            TouchGeometry.placements(template, 960f, 432f)
            assertEquals(portrait.placements, TouchGeometry.placements(template, 432f, 960f))
        }
    }

    @Test fun newControlMissingFromSmallerSavedScreenUsesBaseGeometryAndStaysValid() {
        val preset = SwipePreset()
        val left = TouchControl(type = ControlType.SWIPE, bounds = TouchRect(0f, 0f, 200f, 300f), swipePresetId = preset.id)
        val right = TouchControl(type = ControlType.SWIPE, bounds = TouchRect(200f, 0f, 200f, 300f), swipePresetId = preset.id)
        val original = library(listOf(left, right), listOf(preset))
        val first = original.templates.first()
        val portrait = ScreenLayout(width = 100f, height = 400f)
        val template = first.copy(screens = first.screens + portrait)
        val changed = original.copy(templates = listOf(template))
        TouchValidator.requireValid(changed)
        val bounds = TouchGeometry.placements(template, 100f, 400f)
        assertEquals(TouchRect(0f, 162.5f, 50f, 75f), bounds.getValue(left.id))
        assertEquals(TouchRect(50f, 162.5f, 50f, 75f), bounds.getValue(right.id))
        assertFalse(bounds.getValue(left.id).intersects(bounds.getValue(right.id)))
        assertTrue(portrait.placements.isEmpty())
    }

    @Test fun missingSavedPlacementUsesExplicitBasePlacementBeforeControlBounds() {
        val control = TouchControl(bounds = TouchRect(0f, 0f, 64f, 64f))
        val original = library(listOf(control))
        val first = original.templates.first()
        val base = first.screens.first().copy(placements = mapOf(control.id to TouchRect(300f, 200f, 64f, 64f)))
        val portrait = ScreenLayout(width = 100f, height = 400f)
        val template = first.copy(screens = listOf(base, portrait))
        TouchValidator.requireValid(original.copy(templates = listOf(template)))
        assertEquals(TouchRect(75f, 212.5f, 16f, 16f), TouchGeometry.savedBounds(template, portrait, control))
        repeat(20) {
            assertEquals(base.placements, TouchGeometry.placements(template, base.width, base.height))
            TouchGeometry.placements(template, portrait.width, portrait.height)
        }
    }

    @Test fun serializedObjectsCannotInventMissingStableIdsOrTypes() {
        val original = TouchDefaults.library()
        val text = TouchImportExport.exportTemplate(original, original.templates.first().id)
        val missingPresetId = text.replace("\"id\": \"${original.presets.first().id}\",", "")
        fails<IllegalArgumentException> { TouchImportExport.preview(original, missingPresetId) }
        val missingControlType = text.replace("\"type\": \"STICK\",", "")
        fails<IllegalArgumentException> { TouchImportExport.preview(original, missingControlType) }
    }

    @Test fun curveAndTimeLimitsRejectInvalidParameters() {
        val bad = listOf(
            SwipePreset(sensitivityX = Float.NaN),
            SwipePreset(deadzone = 1f),
            SwipePreset(exponent = 0f),
            SwipePreset(maximumSpeed = -1f),
            SwipePreset(stopTimeoutMs = 0),
            SwipePreset(maxAccumulationMs = 0),
            SwipePreset(curve = listOf(CurvePoint(0f, 0f), CurvePoint(0f, 1f), CurvePoint(1f, 1f))),
            SwipePreset(curve = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.8f), CurvePoint(0.6f, 0.7f), CurvePoint(1f, 1f)))
        )
        bad.forEach { assertTrue(TouchValidator.validate(TouchLibrary(presets = listOf(it))).isNotEmpty()) }
        TouchValidator.requireValid(TouchLibrary(presets = listOf(SwipePreset(curve = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.3f), CurvePoint(1f, 1f))))))
    }
}
