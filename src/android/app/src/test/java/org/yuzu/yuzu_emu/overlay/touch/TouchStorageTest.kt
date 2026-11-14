// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.yuzu.yuzu_emu.model.TaskState
import org.yuzu.yuzu_emu.utils.FileUtil
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TouchStorageTest {
    private val context = RuntimeEnvironment.getApplication()
    private val legacy = TouchStorage.legacyFile(context)
    private val target = TouchStorage.file(context)

    @Before
    fun clear() {
        legacy.parentFile?.deleteRecursively()
        if (target != legacy) target.parentFile?.deleteRecursively()
    }

    @After
    fun cleanup() {
        legacy.parentFile?.deleteRecursively()
        if (target != legacy) target.parentFile?.deleteRecursively()
    }

    @Test
    fun legacyLayoutIsMigratedIntoUserDataDirectory() {
        assumeTrue(target != legacy)
        legacy.parentFile?.mkdirs()
        legacy.writeText("{\"version\":1}", Charsets.UTF_8)
        assertTrue(TouchStorage.migrate(context))
        assertTrue(target.isFile)
        assertEquals("{\"version\":1}", target.readText(Charsets.UTF_8))
        assertFalse(legacy.exists())
    }

    @Test
    fun existingUserDataLayoutWinsOverLegacyLayout() {
        assumeTrue(target != legacy)
        target.parentFile?.mkdirs()
        legacy.parentFile?.mkdirs()
        target.writeText("new", Charsets.UTF_8)
        legacy.writeText("old", Charsets.UTF_8)
        assertTrue(TouchStorage.migrate(context))
        assertEquals("new", target.readText(Charsets.UTF_8))
        assertFalse(legacy.exists())
    }

    @Test
    fun deletingLegacyDoesNotRemoveUserDataLayout() {
        assumeTrue(target != legacy)
        target.parentFile?.mkdirs()
        legacy.parentFile?.mkdirs()
        target.writeText("new", Charsets.UTF_8)
        legacy.writeText("old", Charsets.UTF_8)
        TouchStorage.deleteLegacy(context)
        assertTrue(target.isFile)
        assertFalse(legacy.exists())
    }

    @Test
    fun userDataArchiveIncludesTouchLayouts() {
        assumeTrue(target != legacy)
        val root = context.getExternalFilesDir(null) ?: return
        target.parentFile?.mkdirs()
        target.writeText("{}", Charsets.UTF_8)
        val bytes = ByteArrayOutputStream()
        assertEquals(
            TaskState.Completed,
            FileUtil.zipFromInternalStorage(
                root,
                root.absolutePath,
                BufferedOutputStream(bytes)
            )
        )
        val names = ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry?.name?.replace('\\', '/')?.trimStart('/') }.toSet()
        }
        assertTrue("entries=$names", "touch/templates.json" in names)
    }
}
