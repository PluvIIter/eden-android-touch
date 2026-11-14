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
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
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
            val rect = workspace.placements.getValue(button.id)
            val downTime = android.os.SystemClock.uptimeMillis()
            for ((action, time) in listOf(android.view.MotionEvent.ACTION_DOWN to downTime, android.view.MotionEvent.ACTION_UP to downTime + 16)) {
                val event = android.view.MotionEvent.obtain(downTime, time, action, rect.x + rect.width / 2 + workspace.safe.left, rect.y + rect.height / 2 + workspace.safe.top, 0)
                try { workspace.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            settle(workspace, width, height)
            assertEquals("A real control tap must select it", button.id, workspace.selectedId)
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
            for (actionId in listOf(R.string.touch_tools_controls, R.string.touch_tools_add, R.string.touch_tools_appearance)) {
                val action = descendants(workspace.tools.getChildAt(0)).filterIsInstance<MaterialButton>()
                    .single { it.text.toString() == activity.getString(actionId) || it.contentDescription?.toString() == activity.getString(actionId) }
                action.performClick()
                settle(workspace, width, height)
                assertEquals(
                    "Only one tool window may be open",
                    1,
                    workspace.tools.exportState().windows.size
                )
            }
            writePng(workspace, "$name-single-tool")
            workspace.tools.closeTopWindow(false)
            settle(workspace, width, height)
            assertEquals("Closing the tool must leave no window", 0, workspace.tools.exportState().windows.size)
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
        assertTrue("Toolbar must expose every action without overflow", actions.size >= 8)
        val required = listOf(
            R.string.touch_tools_add,
            R.string.touch_tools_undo,
            R.string.touch_tools_redo,
            R.string.touch_tools_controls,
            R.string.touch_tools_test,
            R.string.touch_tools_save,
            R.string.touch_tools_set_base,
            R.string.touch_tools_appearance
        )
        for (id in required) {
            assertTrue("Missing direct toolbar action ${workspace.context.getString(id)}", actions.any { it.text.toString() == workspace.context.getString(id) || it.contentDescription?.toString() == workspace.context.getString(id) })
        }
        for (action in actions) {
            val label = action.contentDescription ?: (action as? TextView)?.text
            assertFullyVisible(workspace, action, "Toolbar action $label")
            assertTrue("Toolbar action has no accessible label", !label.isNullOrBlank())
        }
        val save = descendants(toolbar).filterIsInstance<MaterialButton>().singleOrNull {
            it.text.toString() == workspace.context.getString(R.string.touch_tools_save)
        }
        assertNotNull("Save must have a localized accessibility description", save)
        assertNotNull("Save must use a recognizable icon", save?.icon)
    }

    private fun assertFullyVisible(workspace: TouchWorkspace, view: View, label: String) {
        assertTrue("$label has no measured size", view.width > 0 && view.height > 0)
        val bounds = Rect(0, 0, view.width, view.height)
        workspace.offsetDescendantRectToMyCoords(view, bounds)
        assertTrue("$label extends beyond the workspace: $bounds", Rect(0, 0, workspace.width, workspace.height).contains(bounds))
        var child = view
        val ancestry = StringBuilder()
        val visible = android.graphics.RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        while (child !== workspace) {
            assertEquals("$label is hidden by an ancestor", View.VISIBLE, child.visibility)
            val parent = child.parent as? ViewGroup ?: error("$label is outside the workspace")
            visible.offset(child.x - parent.scrollX, child.y - parent.scrollY)
            ancestry.append("${child.javaClass.simpleName}(${child.width}x${child.height},${child.x},${child.y}) -> ${parent.javaClass.simpleName}(${parent.width}x${parent.height}); ")
            if (parent.clipChildren) {
                assertTrue("$label has no visible area", visible.intersect(0f, 0f, parent.width.toFloat(), parent.height.toFloat()))
            }
            child = parent
        }
        assertEquals("$label is horizontally clipped inside the workspace", view.width.toFloat(), visible.width(), 0.5f)
        assertEquals("$label is vertically clipped inside the workspace: $ancestry", view.height.toFloat(), visible.height(), 0.5f)
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
