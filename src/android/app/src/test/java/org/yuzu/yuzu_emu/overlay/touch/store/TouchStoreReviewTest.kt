// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.store

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yuzu.yuzu_emu.overlay.touch.model.ControlType
import org.yuzu.yuzu_emu.overlay.touch.model.GroupStyle
import org.yuzu.yuzu_emu.overlay.touch.model.ScreenLayout
import org.yuzu.yuzu_emu.overlay.touch.model.StickTarget
import org.yuzu.yuzu_emu.overlay.touch.model.SwipePreset
import org.yuzu.yuzu_emu.overlay.touch.model.TouchControl
import org.yuzu.yuzu_emu.overlay.touch.model.TouchDefaults
import org.yuzu.yuzu_emu.overlay.touch.model.TouchGeometry
import org.yuzu.yuzu_emu.overlay.touch.model.TouchLibrary
import org.yuzu.yuzu_emu.overlay.touch.model.TouchRect
import org.yuzu.yuzu_emu.overlay.touch.model.TouchTemplate
import java.io.File
import java.nio.file.Files
import java.util.UUID

class TouchStoreReviewTest {
    private fun fails(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
    }

    private fun sharedLibrary(): TouchLibrary {
        val first = TouchDefaults.library()
        val preset = first.presets.single().copy(id = "other-preset", name = "Other")
        val other = TouchReferences.copyTemplate(first.templates.single(), "Other layout").let { t ->
            t.copy(controls = t.controls.map { if (it.type == ControlType.SWIPE) it.copy(swipePresetId = preset.id) else it })
        }
        return first.copy(templates = first.templates + other, presets = first.presets + preset)
    }

    @Test fun copyingForOneGameRejectsUnrelatedPresetModificationWithoutMutatingDraft() {
        val original = sharedLibrary()
        val session = TouchEditSession(original)
        session.putPreset(original.presets.last().copy(sensitivityX = 7f))
        val draft = session.draft
        fails { session.copyTemplateForGame(original.templates.first().id, "0100000000000000") }
        assertEquals(draft, session.draft)
        assertTrue(session.isDirty)
        assertEquals(1f, original.presets.last().sensitivityX)
    }

    @Test fun copyingForOneGameRejectsUnrelatedLayoutAndAssociationChanges() {
        val original = sharedLibrary()
        val changes: List<(TouchEditSession) -> Unit> = listOf(
            { it.putTemplate(original.templates.last().copy(name = "Unrelated change")) },
            { it.associateGame("0100000000002000", original.templates.last().id) },
            { it.setDefaultTemplate(original.templates.last().id) },
            { it.putPreset(SwipePreset(name = "Unreferenced new preset")) },
            { it.deletePreset(original.presets.last().id, deleteReferencedControls = true) }
        )
        changes.forEach { change ->
            val session = TouchEditSession(original)
            change(session)
            val draft = session.draft
            fails { session.copyTemplateForGame(original.templates.first().id, "0100000000000000") }
            assertEquals(draft, session.draft)
        }
    }

    @Test fun copyingForOneGameCannotOptOutOfIsolatingModifiedSharedPreset() {
        val original = TouchDefaults.library()
        val session = TouchEditSession(original)
        session.putPreset(original.presets.single().copy(sensitivityX = 3f))
        val draft = session.draft
        fails { session.copyTemplateForGame(original.templates.single().id, "0100000000000000", false) }
        assertEquals(draft, session.draft)
    }

    @Test fun copyingForOneGameAllowsNewReferencedPresetButKeepsOriginalSharedObjects() {
        val original = TouchDefaults.library()
        val session = TouchEditSession(original)
        val preset = SwipePreset()
        session.putPreset(preset)
        session.putTemplate(
            original.templates.single().copy(
                controls = original.templates.single().controls.map {
                    if (it.type == ControlType.SWIPE) it.copy(swipePresetId = preset.id) else it
                }
            )
        )
        val copied = session.copyTemplateForGame(original.templates.single().id, "0100000000000800")
        assertEquals(original.templates.single(), session.draft.templates.first { it.id == original.templates.single().id })
        assertEquals(copied, session.draft.gameTemplates["0100000000000000"])
        assertEquals(original.presets.single(), session.draft.presets.first { it.id == original.presets.single().id })
        TouchValidator.requireValid(session.draft)
    }

    @Test(timeout = 5000)
    fun compactImportOfTwentyFourThousandTouchingAreasIsValidAndBounded() {
        val count = 24_000
        val preset = SwipePreset(id = "bench-p")
        val screen = ScreenLayout(id = "bench-screen", width = count.toFloat(), height = 1f)
        val template = TouchTemplate(
            id = "bench-template",
            controls = (0 until count).map {
                TouchControl(
                    id = "area-$it",
                    type = ControlType.SWIPE,
                    groupKeys = emptyList(),
                    bounds = TouchRect(it.toFloat(), 0f, 1f, 1f),
                    swipePresetId = preset.id
                )
            },
            screens = listOf(screen),
            baseScreenId = screen.id
        )
        val json = Json { encodeDefaults = false }
        val content = json.encodeToString(TouchBundle(templates = listOf(template), presets = listOf(preset)))
            .replace("\"id\":\"bench-template\"", "\"id\":\"bench-template\",\"baseScreenId\":\"bench-screen\"")
        val compact = "{\"format\":\"eden-touch-bundle\",\"version\":1," + content.drop(1)
        assertTrue(compact.toByteArray().size < 4_000_000)
        val before = System.nanoTime()
        TouchImportExport.preview(TouchDefaults.library(), compact)
        println("24k area preview: ${(System.nanoTime() - before) / 1_000_000} ms")
    }

    @Test fun largeNotesSnapshotRoundTripsAndRepeatedSavesReuseOneRecord() {
        val directory = Files.createTempDirectory("touch-large-draft").toFile()
        val store = TouchDraftStore(directory)
        val original = TouchDefaults.library()
        val library = original.copy(
            presets = original.presets + (0 until 5).map {
                SwipePreset(id = "large-$it", notes = "x".repeat(2_000_000))
            }
        )
        TouchValidator.requireValid(library)
        val encoded = Json.encodeToString(library)
        val snapshot = "{\"draft\":$encoded,\"saved\":$encoded}"
        assertTrue(snapshot.toByteArray().size > 20_000_000)
        val token = store.save(snapshot)
        assertEquals(snapshot, store.read(token))
        repeat(5) { assertEquals(token, store.save(snapshot, token)) }
        assertEquals(listOf(token), directory.listFiles()!!.map { it.name })
        assertTrue(File(directory, token).length() < 100_000)
        store.discard(token)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun draftLimitUsesUtf8BytesConsistentlyAndFailedWriteRetainsPriorRecord() {
        val directory = Files.createTempDirectory("touch-limited-draft").toFile()
        val store = TouchDraftStore(directory, maxBytes = 12)
        val token = store.save("界".repeat(4))
        assertEquals("界".repeat(4), store.read(token))
        fails { store.save("界".repeat(5), token) }
        assertEquals("界".repeat(4), store.read(token))
        assertEquals(1, directory.listFiles()!!.size)
        val legacy = UUID.randomUUID().toString()
        File(directory, legacy).writeText("a".repeat(13))
        fails { store.read(legacy) }
    }

    @Test fun oversizedCompressedRecordCannotBypassDecodedLimit() {
        val directory = Files.createTempDirectory("touch-compressed-limit").toFile()
        val token = TouchDraftStore(directory, maxBytes = 1000).save("x".repeat(100))
        fails { TouchDraftStore(directory, maxBytes = 50).read(token) }
    }

    @Test fun legacyRecordsReadAndCleanupProtectsOtherActiveOwners() {
        val directory = Files.createTempDirectory("touch-cleanup-draft").toFile()
        var now = 10L * TouchDraftStore.RETENTION_MS
        val store = TouchDraftStore(directory, clock = { now })
        val otherOwner = Any()
        val active = store.save("other activity draft", owner = otherOwner)
        val legacy = UUID.randomUUID().toString()
        File(directory, legacy).writeText("legacy snapshot")
        assertEquals("legacy snapshot", store.read(legacy, owner = otherOwner))
        repeat(40) { File(directory, UUID.randomUUID().toString()).apply { writeText("stale"); setLastModified(1) } }
        File(directory, active).setLastModified(1)
        now += TouchDraftStore.RETENTION_MS
        assertEquals(16, store.cleanup())
        assertTrue(File(directory, active).isFile)
        assertEquals("other activity draft", store.read(active))
        assertEquals("legacy snapshot", store.read(legacy))
        store.discard(active)
        store.discard(active)
        assertFalse(File(directory, active).exists())
    }

    @Test fun cleanupCapsAbandonedRecordsWithoutDeletingLiveTokens() {
        val directory = Files.createTempDirectory("touch-quota-draft").toFile()
        val now = System.currentTimeMillis()
        val store = TouchDraftStore(directory, clock = { now })
        val owner = Any()
        val token = store.save("active record", owner = owner)
        repeat(40) { File(directory, UUID.randomUUID().toString()).apply { writeText("abandoned"); setLastModified(now - 1) } }
        assertEquals(8, store.cleanup())
        assertEquals(33, directory.listFiles()!!.size)
        assertEquals("active record", store.read(token, owner))
    }

    @Test fun draftTokensCannotEscapeDirectory() {
        val store = TouchDraftStore(Files.createTempDirectory("touch-token-test").toFile())
        fails { store.read("../outside") }
        fails { store.save("text", "../outside") }
        fails { store.discard("../outside") }
    }

    @Test fun defaultLayoutCoversEveryControllerButtonAndSplitsAreas() {
        val library = TouchDefaults.library()
        val template = library.templates.single()
        TouchValidator.requireValid(library)
        val targets = (0..15).toSet()
        val mapped = buildSet {
            template.controls.forEach { control ->
                addAll(control.binding.buttons)
                control.groupKeys.forEach { addAll(it.binding.buttons) }
            }
        }
        assertEquals(targets, mapped)
        assertTrue(template.controls.none { it.name == "Home" || it.name == "Capture" })

        val screen = template.screens.single()
        val stick = template.controls.single { it.type == ControlType.STICK }
        assertTrue(stick.freeStick)
        assertTrue(stick.triggerOnly)
        assertEquals(StickTarget.LEFT, stick.stickTarget)
        val swipe = template.controls.single { it.type == ControlType.SWIPE }
        assertEquals(StickTarget.RIGHT, swipe.stickTarget)
        val left = TouchGeometry.savedBounds(template, screen, stick)
        val right = TouchGeometry.savedBounds(template, screen, swipe)
        assertEquals(0f, left.x, 0.01f)
        assertEquals(screen.width / 2f, right.x, 0.01f)
        assertEquals(screen.width, left.width + right.width, 0.01f)
        assertEquals(screen.height, left.height, 0.01f)
        assertEquals(screen.height, right.height, 0.01f)
        assertFalse(left.intersects(right))
    }

    @Test fun defaultFaceGroupUsesNintendoPositions() {
        val group = TouchDefaults.library().templates.single().controls
            .single { it.type == ControlType.BUTTON_GROUP && it.groupStyle == GroupStyle.FACE }
        assertEquals(listOf("X", "A", "B", "Y"), group.groupKeys.map { it.text })
        assertEquals(listOf(2, 0, 1, 3), group.groupKeys.map { it.binding.buttons.single() })
    }
}
