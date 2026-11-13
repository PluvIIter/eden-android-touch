// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.store

import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class TouchDraftStore(
    directory: File,
    private val maxBytes: Long = MAX_SNAPSHOT_BYTES,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val directory = directory.canonicalFile

    fun save(serialized: String, token: String? = null, owner: Any = this): String {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create draft directory" }
        val id = token ?: UUID.randomUUID().toString()
        val target = record(id)
        val temporary = File.createTempFile("$id.", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                val compressed = GZIPOutputStream(output)
                val bounded = object : FilterOutputStream(compressed) {
                    private var count = 0L
                    override fun write(value: Int) {
                        if (++count > maxBytes) throw IOException("Touch draft exceeds recovery limit")
                        out.write(value)
                    }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        count += length
                        if (count > maxBytes) throw IOException("Touch draft exceeds recovery limit")
                        out.write(bytes, offset, length)
                    }
                }
                compressed.use {
                    val writer = bounded.writer(Charsets.UTF_8)
                    writer.write(serialized)
                    writer.flush()
                    compressed.finish()
                    output.fd.sync()
                }
            }
            if (!temporary.renameTo(target)) throw IOException("Cannot replace touch draft")
            target.setLastModified(clock())
            retain(id, owner)
            cleanup()
            return id
        } finally {
            temporary.delete()
        }
    }

    fun read(token: String, owner: Any = this): String {
        val file = record(token)
        require(file.isFile && file.length() <= maxBytes + 1_048_576) { "Saved touch draft is missing or too large" }
        val text = file.inputStream().buffered().use { input ->
            input.mark(2)
            val gzip = input.read() == 0x1f && input.read() == 0x8b
            input.reset()
            val decoded = if (gzip) GZIPInputStream(input) else input
            bounded(decoded).reader(Charsets.UTF_8).use { it.readText() }
        }
        file.setLastModified(clock())
        retain(token, owner)
        return text
    }

    fun retain(token: String, owner: Any = this) {
        active[record(token).path] = WeakReference(owner)
    }

    fun discard(token: String) {
        val file = record(token)
        active.remove(file.path)
        if (file.exists() && !file.delete()) throw IOException("Cannot discard touch draft")
    }

    fun cleanup(): Int {
        val cutoff = clock() - RETENTION_MS
        val abandoned = directory.listFiles()?.filter {
            (idPattern.matches(it.name) || it.name.endsWith(".tmp")) && it.isFile &&
                it.canonicalFile.parentFile == directory && active[it.canonicalPath]?.get() == null
        }?.sortedBy { it.lastModified() }.orEmpty()
        var count = abandoned.size
        var bytes = abandoned.sumOf { it.length() }
        var deleted = 0
        for (file in abandoned) {
            if (deleted >= MAX_CLEANUP_FILES) break
            if (file.lastModified() >= cutoff && count <= MAX_ABANDONED_RECORDS && bytes <= MAX_ABANDONED_BYTES) break
            val size = file.length()
            if (file.delete()) {
                active.remove(file.canonicalPath)
                count--
                bytes -= size
                deleted++
            }
        }
        return deleted
    }

    private fun record(token: String): File {
        require(idPattern.matches(token)) { "Invalid touch draft token" }
        return File(directory, token).canonicalFile.also {
            require(it.parentFile == directory) { "Invalid touch draft location" }
        }
    }

    private fun bounded(input: InputStream): InputStream = object : FilterInputStream(input) {
        private var count = 0L
        private fun consumed(amount: Int): Int {
            if (amount > 0) count += amount
            if (count > maxBytes) throw IOException("Touch draft exceeds recovery limit")
            return amount
        }
        override fun read(): Int {
            val value = super.read()
            consumed(if (value < 0) 0 else 1)
            return value
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            consumed(`in`.read(bytes, offset, length))
    }

    companion object {
        const val MAX_SNAPSHOT_BYTES = 64L * 1024 * 1024
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_CLEANUP_FILES = 16
        private const val MAX_ABANDONED_RECORDS = 32
        private const val MAX_ABANDONED_BYTES = 128L * 1024 * 1024
        private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val active = ConcurrentHashMap<String, WeakReference<Any>>()
    }
}
