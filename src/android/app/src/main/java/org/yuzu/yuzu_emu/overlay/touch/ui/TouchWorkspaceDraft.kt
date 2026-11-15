// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.ui

import kotlinx.serialization.Serializable
import org.yuzu.yuzu_emu.overlay.touch.model.TouchLibrary

@Serializable
internal data class TouchWorkspaceDraft(
    val draft: TouchLibrary,
    val saved: TouchLibrary,
    val templateId: String,
    val selectedId: String?,
    val editing: Boolean,
    val tools: TouchToolState = TouchToolState(),
    val standalonePresetId: String? = null
) {
    fun requireRestorable(revision: Long) {
        if (saved.revision != revision) {
            throw TouchDraftRevisionConflict(saved.revision, revision)
        }
        require(draft.templates.any { it.id == templateId }) { "Recovered template is missing" }
        require(saved.templates.any { it.id == templateId }) { "Saved template is missing" }
    }
}

internal class TouchDraftRevisionConflict(
    val draftRevision: Long,
    val currentRevision: Long
) : IllegalArgumentException(
    "Saved library changed since this draft; reload or save a copy"
)
