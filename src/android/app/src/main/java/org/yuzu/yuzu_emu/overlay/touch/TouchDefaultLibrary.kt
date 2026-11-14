// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch

import android.content.Context
import org.yuzu.yuzu_emu.R
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
                        val name = when (control.name) {
                            "Move" -> context.getString(R.string.touch_workspace_move)
                            "Camera" -> context.getString(R.string.touch_workspace_camera)
                            "D-pad" -> context.getString(R.string.touch_workspace_dpad)
                            "Home" -> context.getString(R.string.touch_tools_home)
                            "Capture" -> context.getString(R.string.touch_tools_capture)
                            else -> return@map control
                        }
                        control.copy(name = name, text = if (control.text == control.name) name else control.text)
                    }
                )
            },
            presets = library.presets.map { it.copy(name = context.getString(R.string.touch_workspace_default_camera)) }
        )
    }
}
