// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Touch layouts live in the app's external files directory so they are part of Eden's user data
 * backup (which archives [android.content.Context.getExternalFilesDir]). Older builds stored them
 * in internal storage, so those files are migrated once on startup.
 */
object TouchStorage {
    private const val TAG = "TouchStorage"
    private const val RELATIVE_PATH = "touch/templates.json"

    fun file(context: Context): File = File(userDirectory(context), RELATIVE_PATH)

    fun legacyFile(context: Context): File = File(context.filesDir, RELATIVE_PATH)

    fun migrate(context: Context): Boolean {
        val target = file(context)
        val legacy = legacyFile(context)
        if (target.absolutePath == legacy.absolutePath) return true
        if (!legacy.isFile) return true
        if (target.exists()) {
            legacy.delete()
            return true
        }
        return try {
            target.parentFile?.mkdirs()
            legacy.copyTo(target, overwrite = false)
            legacy.delete()
            true
        } catch (error: IOException) {
            Log.w(TAG, "Unable to migrate touch layouts to external storage", error)
            false
        }
    }

    fun deleteLegacy(context: Context) {
        legacyFile(context).delete()
    }

    private fun userDirectory(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir
}
