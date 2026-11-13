// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch

import android.content.Context
import android.view.View
import org.yuzu.yuzu_emu.overlay.touch.model.TouchLibrary
import org.yuzu.yuzu_emu.overlay.touch.store.TouchReferences
import org.yuzu.yuzu_emu.overlay.touch.ui.TouchWorkspace

object TouchGameOverlay {
    fun gameId(programId: String): String? {
        val decimal = programId.toULongOrNull() ?: return null
        if (decimal == 0uL) return null
        return TouchReferences.baseGameId(decimal.toString(16).padStart(16, '0'))
    }

    fun create(context: Context, library: TouchLibrary, programId: String): TouchWorkspace? {
        val template = library.templateFor(gameId(programId)) ?: return null
        return TouchWorkspace(context).apply {
            bind(library, template.id, true, TouchNativeSink())
            setEditing(false)
            visibility = View.VISIBLE
        }
    }
}
