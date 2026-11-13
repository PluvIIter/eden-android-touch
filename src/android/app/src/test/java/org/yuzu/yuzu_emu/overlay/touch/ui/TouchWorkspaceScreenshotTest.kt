// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.ui

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.overlay.touch.model.ControlType
import org.yuzu.yuzu_emu.overlay.touch.TouchDefaultLibrary
import org.yuzu.yuzu_emu.overlay.touch.runtime.InputSink
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [35], application = Application::class)
class TouchWorkspaceScreenshotTest {
    @Test
    @Config(qualifiers = "zh-rCN-w640dp-h360dp-land-notnight-xhdpi")
    fun chineseLightLandscape() = render("zh-CN-light-1280x720", 1280, 720, true)

    @Test
    @Config(qualifiers = "zh-rCN-w640dp-h360dp-land-night-xhdpi")
    fun chineseDarkLandscape() = render("zh-CN-dark-1280x720", 1280, 720, true)

    @Test
    @Config(qualifiers = "en-w640dp-h360dp-land-notnight-xhdpi")
    fun englishLightLandscape() = render("en-light-1280x720", 1280, 720, false)

    @Test
    @Config(qualifiers = "en-w640dp-h360dp-land-night-xhdpi")
    fun englishDarkLandscape() = render("en-dark-1280x720", 1280, 720, false)

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-port-notnight-xhdpi")
    fun chineseLightPortrait() = render("zh-CN-light-720x1280", 720, 1280, true)

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h640dp-port-night-xhdpi")
    fun chineseDarkPortrait() = render("zh-CN-dark-720x1280", 720, 1280, true)

    @Test
    @Config(qualifiers = "en-w360dp-h640dp-port-notnight-xhdpi")
    fun englishLightPortrait() = render("en-light-720x1280", 720, 1280, false)

    @Test
    @Config(qualifiers = "en-w360dp-h640dp-port-night-xhdpi")
    fun englishDarkPortrait() = render("en-dark-720x1280", 720, 1280, false)

    private fun render(name: String, width: Int, height: Int, chinese: Boolean) {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_Eden_Main)
        try {
            controller.setup().visible()
            assertEquals(2f, activity.resources.displayMetrics.density, 0.01f)
            assertEquals(if (chinese) "zh" else "en", activity.resources.configuration.locales[0].language)
            val nightMode = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            assertEquals(
                if ("dark" in name) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
                nightMode
            )
            val library = TouchDefaultLibrary.create(activity)
            val workspace = TouchWorkspace(activity)
            workspace.bind(library, library.templates.first().id, false, object : InputSink {})
            workspace.setEditing(true)
            activity.setContentView(workspace)
            settle(workspace, width, height)
            writePng(workspace, "$name-toolbar")
            val button = workspace.template.controls.first { it.type == ControlType.BUTTON }
            workspace.select(button.id)
            workspace.tools.restoreState(
                TouchToolState(propertyId = button.id, activePanel = "control:${button.id}:0:null")
            )
            settle(workspace, width, height)
            writePng(workspace, "$name-properties")
            assertTrue("Selected control properties must be open", workspace.tools.hasPanel)
            val propertyCard = workspace.tools.getChildAt(1)
            assertFullyVisible(workspace, propertyCard, "Property card")
            for (labelId in listOf(R.string.touch_tools_cancel, R.string.touch_tools_done)) {
                val label = activity.getString(labelId)
                val action = descendants(propertyCard).filterIsInstance<MaterialButton>()
                    .firstOrNull { it.text.toString() == label }
                assertNotNull("Property action $label is missing", action)
                assertFullyVisible(workspace, action!!, "Property action $label")
            }
            val tabs = descendants(propertyCard).filterIsInstance<com.google.android.material.tabs.TabLayout>().firstOrNull()
            assertNotNull("Property form tabs are missing", tabs)
            assertFullyVisible(workspace, tabs!!, "Property form tabs")
            if (chinese) assertChinese(workspace)
            workspace.tools.closePanel()
            workspace.selectedId = null
            workspace.tools.restoreState(TouchToolState())
            settle(workspace, width, height)
            assertToolbar(workspace, workspace.tools.getChildAt(0) as ViewGroup)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun settle(workspace: TouchWorkspace, width: Int, height: Int) {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            workspace.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            workspace.layout(0, 0, width, height)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(width, workspace.width)
        assertEquals(height, workspace.height)
    }

    private fun writePng(workspace: TouchWorkspace, name: String) {
        val directory = File(System.getProperty("touch.ui.screenshotDir", "build/reports/touch-ui"))
        assertTrue("Cannot create screenshot directory: $directory", directory.isDirectory || directory.mkdirs())
        val bitmap = Bitmap.createBitmap(workspace.width, workspace.height, Bitmap.Config.ARGB_8888)
        try {
            workspace.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use {
                assertTrue("PNG encoding failed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Native rendering produced an empty image", pixels.asSequence().distinct().take(33).count() > 32)
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertToolbar(workspace: TouchWorkspace, toolbar: ViewGroup) {
        val actions = descendants(toolbar).filterIsInstance<MaterialButton>().toList()
        assertTrue("Toolbar must expose its actions", actions.size >= 3)
        for (action in actions) {
            val label = action.contentDescription ?: (action as? TextView)?.text
            assertFullyVisible(workspace, action, "Toolbar action $label")
            assertTrue("Toolbar action has no accessible label", !label.isNullOrBlank())
        }
        val save = descendants(toolbar).filterIsInstance<MaterialButton>().singleOrNull {
            it.contentDescription?.toString() == workspace.context.getString(R.string.touch_tools_save)
        }
        assertNotNull("Save must have a localized accessibility description", save)
        assertNotNull("Save must use a recognizable icon", save?.icon)
    }

    private fun assertFullyVisible(workspace: TouchWorkspace, view: View, label: String) {
        assertTrue("$label has no measured size", view.width > 0 && view.height > 0)
        val bounds = Rect(0, 0, view.width, view.height)
        workspace.offsetDescendantRectToMyCoords(view, bounds)
        assertTrue("$label extends beyond the workspace: $bounds", Rect(0, 0, workspace.width, workspace.height).contains(bounds))
        val visible = Rect()
        assertTrue("$label is hidden", view.getLocalVisibleRect(visible))
        assertEquals("$label is horizontally clipped", view.width, visible.width())
        assertEquals("$label is vertically clipped", view.height, visible.height())
    }

    private fun assertChinese(workspace: TouchWorkspace) {
        val forbidden = Regex("(?i)\\b(add|undo|redo|controls|test|save|more|fold|drag|draft|properties|input|appearance|geometry|done|cancel|adjust|duplicate|delete|button|enabled|mapping|output|turbo|frequency|sync|default layout)\\b")
        for (view in descendants(workspace)) {
            val visible = Rect()
            if (!view.isShown || !view.getLocalVisibleRect(visible)) continue
            val text = listOfNotNull((view as? TextView)?.text, (view as? TextView)?.hint, view.contentDescription)
                .joinToString(" ")
            assertFalse("Visible English UI in Chinese locale: $text", forbidden.containsMatchIn(text))
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
