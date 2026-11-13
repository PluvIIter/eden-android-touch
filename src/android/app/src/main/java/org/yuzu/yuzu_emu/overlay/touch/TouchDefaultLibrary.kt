// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch

import android.content.Context
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.overlay.touch.model.ControlType
import org.yuzu.yuzu_emu.overlay.touch.model.TouchDefaults
import org.yuzu.yuzu_emu.overlay.touch.model.TouchLibrary

object TouchDefaultLibrary {
    fun create(context: Context): TouchLibrary {
        val library = TouchDefaults.library()
        return library.copy(
            templates = library.templates.map { template ->
                template.copy(
                    name = context.getString(R.string.touch_workspace_default_layout),
                    screens = template.screens.map { it.copy(name = context.getString(R.string.touch_workspace_landscape)) },
                    controls = template.controls.map { control ->
                        when (control.type) {
                            ControlType.STICK -> control.copy(name = context.getString(R.string.touch_workspace_move))
                            ControlType.SWIPE -> control.copy(name = context.getString(R.string.touch_workspace_camera))
                            else -> control
                        }
                    }
                )
            },
            presets = library.presets.map { it.copy(name = context.getString(R.string.touch_workspace_default_camera)) }
        )
    }
}
