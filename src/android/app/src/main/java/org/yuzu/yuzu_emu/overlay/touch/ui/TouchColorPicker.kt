// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView
import org.yuzu.yuzu_emu.R

/** Palette-based color picker used by both the template manager and the layout editor. */
object TouchColorPicker {
    private val palette = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFF44336.toInt(), 0xFFE91E63.toInt(),
        0xFF9C27B0.toInt(), 0xFF673AB7.toInt(), 0xFF3F51B5.toInt(), 0xFF2196F3.toInt(),
        0xFF03A9F4.toInt(), 0xFF00BCD4.toInt(), 0xFF009688.toInt(), 0xFF4CAF50.toInt(),
        0xFF8BC34A.toInt(), 0xFFCDDC39.toInt(), 0xFFFFEB3B.toInt(), 0xFFFFC107.toInt(),
        0xFFFF9800.toInt(), 0xFFFF5722.toInt(), 0xFF795548.toInt(), 0xFF9E9E9E.toInt(),
        0xFF607D8B.toInt(), 0xFFE0E0E0.toInt(), 0xFFB71C1C.toInt(), 0xFF1B5E20.toInt()
    )

    fun show(context: Context, current: Int?, onPick: (Int?) -> Unit) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val card = MaterialCardView(ContextThemeWrapper(context, R.style.EdenCard)).apply {
            radius = dp(context, 18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(context, 1)
            val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
            val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline)
            setCardBackgroundColor(surface)
            setStrokeColor(outline)
        }
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 12))
        }
        page.addView(
            MaterialTextView(context).apply {
                text = context.getString(R.string.touch_tools_color_pick)
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            }
        )
        var row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        palette.forEachIndexed { index, color ->
            if (index > 0 && index % 6 == 0) {
                page.addView(row)
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            }
            val outline = MaterialColors.getColor(page, com.google.android.material.R.attr.colorOutline)
            val primary = MaterialColors.getColor(page, com.google.android.material.R.attr.colorPrimary)
            val swatch = View(context).apply {
                contentDescription = String.format(java.util.Locale.ROOT, "#%08X", color)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(context, 10).toFloat()
                    setColor(color)
                    if (current == color) {
                        setStroke(dp(context, 3), primary)
                    } else {
                        setStroke(dp(context, 1), outline)
                    }
                }
                setOnClickListener {
                    dialog.dismiss()
                    onPick(color)
                }
            }
            row.addView(
                swatch,
                LinearLayout.LayoutParams(dp(context, 42), dp(context, 42)).apply {
                    marginStart = dp(context, 3)
                    marginEnd = dp(context, 3)
                    topMargin = dp(context, 3)
                    bottomMargin = dp(context, 3)
                }
            )
        }
        page.addView(row)
        page.addView(
            MaterialButton(
                ContextThemeWrapper(context, com.google.android.material.R.style.Widget_Material3_Button_TextButton),
                null,
                com.google.android.material.R.attr.materialButtonStyle
            ).apply {
                text = context.getString(R.string.touch_tools_color_default)
                isAllCaps = false
                setOnClickListener {
                    dialog.dismiss()
                    onPick(null)
                }
            },
            LinearLayout.LayoutParams(-1, -2)
        )
        card.addView(page)
        val root = FrameLayout(context).apply {
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
            addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setLayout(dp(context, 360), WindowManager.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
