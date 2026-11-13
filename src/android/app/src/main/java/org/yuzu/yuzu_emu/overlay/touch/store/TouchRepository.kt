// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.store

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.yuzu.yuzu_emu.overlay.touch.model.TouchDefaults
import org.yuzu.yuzu_emu.overlay.touch.model.TouchLibrary
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

class TouchRevisionConflictException(val expectedRevision: Long, val actualRevision: Long) :
    IllegalStateException("Touch library changed: expected revision $expectedRevision, found $actualRevision")

class TouchRepository(file: File) {
    private val file = file.canonicalFile
    private val monitor = monitors.getOrPut(this.file.path) { Any() }

    fun load(): TouchLibrary = locked { readOrInitialize() }

    fun save(library: TouchLibrary, expectedRevision: Long): TouchLibrary = locked {
        val snapshot = library.immutableSnapshot()
        TouchValidator.requireValid(snapshot)
        val current = readOrInitialize()
        if (current.revision != expectedRevision) throw TouchRevisionConflictException(expectedRevision, current.revision)
        commit(snapshot, current.revision)
    }

    fun editSession(): TouchEditSession = TouchEditSession(load())

    fun exportTemplate(templateId: String): String = TouchImportExport.exportTemplate(load(), templateId)

    fun exportPreset(presetId: String): String = TouchImportExport.exportPreset(load(), presetId)

    fun previewImport(text: String): TouchImportPreview = TouchImportExport.preview(load(), text)

    fun importBundle(text: String, expectedRevision: Long, resolution: ImportConflictResolution? = null): TouchImportResult = locked {
        val current = readOrInitialize()
        if (current.revision != expectedRevision) throw TouchRevisionConflictException(expectedRevision, current.revision)
        val imported = TouchImportExport.importBundle(current, text, resolution)
        imported.copy(library = commit(imported.library, current.revision))
    }

    private fun commit(library: TouchLibrary, revision: Long): TouchLibrary {
        check(revision < Long.MAX_VALUE) { "Touch library revision exhausted" }
        val saved = library.copy(revision = revision + 1)
        atomicWrite(saved)
        return saved
    }

    private fun readOrInitialize(): TouchLibrary {
        if (!file.exists()) {
            val defaults = TouchDefaults.library().immutableSnapshot()
            TouchValidator.requireValid(defaults)
            atomicWrite(defaults)
            return defaults
        }
        val text = file.readText(Charsets.UTF_8)
        val root = TouchImportExport.json.parseToJsonElement(text).jsonObject
        require(root["version"]?.jsonPrimitive?.content == TouchValidator.VERSION.toString()) { "Unsupported touch library version" }
        require(root.containsKey("revision")) { "Missing touch library revision" }
        TouchImportExport.requireStableIds(root)
        val library = TouchImportExport.json.decodeFromString<TouchLibrary>(text).immutableSnapshot()
        TouchValidator.requireValid(library)
        return library
    }

    private fun atomicWrite(library: TouchLibrary) {
        val parent = file.parentFile ?: error("Missing configuration directory")
        val temporary = File.createTempFile(".${file.name}.", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(TouchImportExport.json.encodeToString(library).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            // Android's same-directory rename is atomic and available before API 26.
            if (!temporary.renameTo(file)) throw IOException("Cannot replace touch configuration")
        } finally {
            temporary.delete()
        }
    }

    private fun <T> locked(action: () -> T): T = synchronized(monitor) {
        val parent = file.parentFile ?: error("Missing configuration directory")
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create touch configuration directory" }
        // The separate lock inode survives atomic replacement of the configuration file.
        RandomAccessFile(File(parent, ".${file.name}.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use { action() }
        }
    }

    companion object {
        private val monitors = ConcurrentHashMap<String, Any>()
    }
}
