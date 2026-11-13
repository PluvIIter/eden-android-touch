// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch

import android.content.Context
import android.os.Bundle
import org.yuzu.yuzu_emu.overlay.touch.store.TouchDraftStore
import org.yuzu.yuzu_emu.overlay.touch.ui.TouchWorkspace
import java.io.File
import java.util.WeakHashMap

object TouchDraftState {
    private const val KEY = "touch_workspace_draft"
    private val tokens = WeakHashMap<TouchWorkspace, String>()

    @Synchronized
    fun save(context: Context, state: Bundle, workspace: TouchWorkspace?) {
        if (workspace == null || (!workspace.isEditing && !workspace.isTesting)) return
        val store = store(context)
        val token = store.save(workspace.exportDraft(), tokens[workspace], workspace)
        tokens[workspace] = token
        state.putString(KEY, token)
    }

    @Synchronized
    fun restore(context: Context, state: Bundle?, workspace: TouchWorkspace): Boolean {
        val token = state?.getString(KEY) ?: return false
        val store = store(context)
        val serialized = store.read(token, workspace)
        tokens[workspace] = token
        workspace.restoreDraft(serialized)
        return true
    }

    @Synchronized
    fun discard(context: Context, workspace: TouchWorkspace?) {
        val token = if (workspace == null) null else tokens.remove(workspace)
        if (token != null) store(context).discard(token)
    }

    @Synchronized
    fun discard(context: Context, state: Bundle?) {
        val token = state?.getString(KEY) ?: return
        store(context).discard(token)
        state.remove(KEY)
        tokens.entries.removeAll { it.value == token }
    }

    private fun store(context: Context) = TouchDraftStore(File(context.filesDir, "touch/drafts"))
}
