// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch

import android.content.Context
import android.os.Bundle
import org.yuzu.yuzu_emu.overlay.touch.ui.TouchWorkspace
import java.io.File
import java.util.UUID

object TouchDraftState {
    private const val KEY = "touch_workspace_draft"
    private val idPattern = Regex("[0-9a-f-]{36}")

    fun save(context: Context, state: Bundle, workspace: TouchWorkspace?) {
        if (workspace == null || (!workspace.isEditing && !workspace.isTesting)) return
        val directory = File(context.filesDir, "touch/drafts")
        check(directory.isDirectory || directory.mkdirs())
        val id = UUID.randomUUID().toString()
        File(directory, id).writeText(workspace.exportDraft(), Charsets.UTF_8)
        state.putString(KEY, id)
    }

    fun restore(context: Context, state: Bundle?, workspace: TouchWorkspace): Boolean {
        val id = state?.getString(KEY) ?: return false
        require(id.matches(idPattern)) { "Invalid touch draft token" }
        val file = File(context.filesDir, "touch/drafts/$id")
        require(file.isFile && file.length() <= 16_000_000) { "Saved touch draft is missing or too large" }
        workspace.restoreDraft(file.readText(Charsets.UTF_8))
        return true
    }
}
