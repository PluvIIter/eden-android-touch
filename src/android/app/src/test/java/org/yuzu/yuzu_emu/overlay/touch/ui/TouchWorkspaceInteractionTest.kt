// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch.ui

import android.app.Application
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import org.yuzu.yuzu_emu.overlay.touch.TouchDefaultLibrary
import org.yuzu.yuzu_emu.overlay.touch.model.ControlType
import org.yuzu.yuzu_emu.overlay.touch.model.TouchControl
import org.yuzu.yuzu_emu.overlay.touch.model.TouchRect
import org.yuzu.yuzu_emu.overlay.touch.runtime.InputSink

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(
    sdk = [35],
    application = Application::class,
    qualifiers = "en-w960dp-h540dp-land-notnight-xhdpi"
)
class TouchWorkspaceInteractionTest {
    @Test
    fun canvasTapAutomaticallyOpensEditableProperties() = withWorkspace { workspace ->
        val control = workspace.template.controls.first { it.type == ControlType.BUTTON }
        tapControl(workspace, control.id)

        assertEquals(control.id, workspace.selectedId)
        assertEquals(setOf("control:${control.id}"), windowKeys(workspace))
        val properties = appearance(workspace, topCard(workspace))
        nameField(properties, control.name).setText("Automatic properties")
        settle(workspace)
        assertEquals("Automatic properties", control(workspace, control.id).name)

        click(workspace, properties, R.string.touch_tools_done)
        assertFalse(workspace.tools.hasPanel)
        toolbarClick(workspace, R.string.touch_tools_undo)
        assertEquals(control.name, control(workspace, control.id).name)
        toolbarClick(workspace, R.string.touch_tools_redo)
        assertEquals("Automatic properties", control(workspace, control.id).name)
    }

    @Test
    fun concurrentFormEditsStayWithTheirOwnerAndCancelPreservesTheOtherForm() = withWorkspace { workspace ->
        val (a, b) = buttons(workspace)
        val list = openControls(workspace)
        val aCard = appearance(workspace, openFromList(workspace, list, a))
        val aName = nameField(aCard, a.name)
        aName.setText("A first edit")
        val bCard = appearance(workspace, openFromList(workspace, list, b))
        val bName = nameField(bCard, b.name)
        bName.setText("B independent edit")

        aName.setText("A edited while B is selected")
        settle(workspace)
        assertEquals(b.id, workspace.selectedId)
        assertEquals("A edited while B is selected", control(workspace, a.id).name)
        assertEquals("B independent edit", control(workspace, b.id).name)

        click(workspace, aCard, R.string.touch_tools_cancel)
        assertEquals(a.name, control(workspace, a.id).name)
        assertEquals("B independent edit", control(workspace, b.id).name)
        assertEquals(setOf("controls", "control:${b.id}"), windowKeys(workspace))
        click(workspace, bCard, R.string.touch_tools_done)
        toolbarClick(workspace, R.string.touch_tools_undo)
        assertEquals(a.name, control(workspace, a.id).name)
        assertEquals(b.name, control(workspace, b.id).name)
        toolbarClick(workspace, R.string.touch_tools_redo)
        assertEquals(a.name, control(workspace, a.id).name)
        assertEquals("B independent edit", control(workspace, b.id).name)
    }

    @Test
    fun finishingConcurrentFormsCreatesIndependentUndoStepsWithoutResurrection() = withWorkspace { workspace ->
        val (a, b) = buttons(workspace)
        val list = openControls(workspace)
        val aCard = appearance(workspace, openFromList(workspace, list, a))
        nameField(aCard, a.name).setText("A committed")
        val bCard = appearance(workspace, openFromList(workspace, list, b))
        nameField(bCard, b.name).setText("B committed")

        click(workspace, aCard, R.string.touch_tools_done)
        assertEquals(setOf("controls", "control:${b.id}"), windowKeys(workspace))
        click(workspace, bCard, R.string.touch_tools_done)
        assertNames(workspace, a, "A committed", b, "B committed")
        toolbarClick(workspace, R.string.touch_tools_undo)
        assertNames(workspace, a, "A committed", b, b.name)
        toolbarClick(workspace, R.string.touch_tools_undo)
        assertNames(workspace, a, a.name, b, b.name)
        toolbarClick(workspace, R.string.touch_tools_redo)
        assertNames(workspace, a, "A committed", b, b.name)
        toolbarClick(workspace, R.string.touch_tools_redo)
        assertNames(workspace, a, "A committed", b, "B committed")
    }

    @Test
    fun completingOrCancellingAnUneditedPropertyFormPreservesRedo() = withWorkspace { workspace ->
        val a = buttons(workspace).first
        val list = openControls(workspace)
        val properties = appearance(workspace, openFromList(workspace, list, a))
        nameField(properties, a.name).setText("Redo survives")
        click(workspace, properties, R.string.touch_tools_done)
        toolbarClick(workspace, R.string.touch_tools_undo)
        assertEquals(a.name, control(workspace, a.id).name)

        for (action in listOf(R.string.touch_tools_done, R.string.touch_tools_cancel)) {
            val unedited = openFromList(workspace, list, a)
            click(workspace, unedited, action)
            toolbarClick(workspace, R.string.touch_tools_redo)
            assertEquals("Redo survives", control(workspace, a.id).name)
            toolbarClick(workspace, R.string.touch_tools_undo)
            assertEquals(a.name, control(workspace, a.id).name)
        }
    }

    @Test
    fun twoCanvasGesturesRemainSeparateUndoStepsWhilePropertiesStayOpen() = withWorkspace { workspace ->
        val stick = workspace.template.controls.first { it.type == ControlType.STICK }
        tapControl(workspace, stick.id)
        assertEquals(stick.id, workspace.selectedId)
        val original = workspace.placements.getValue(stick.id)
        assertEquals(setOf("control:${stick.id}"), windowKeys(workspace))

        dragControl(workspace, stick.id, 80f, -40f)
        val first = workspace.placements.getValue(stick.id)
        assertEquals(original.copy(x = original.x + 80f, y = original.y - 40f), first)
        dragControl(workspace, stick.id, 60f, 40f)
        val second = workspace.placements.getValue(stick.id)
        assertEquals(first.copy(x = first.x + 60f, y = first.y + 40f), second)

        for ((action, expected) in listOf(
            R.string.touch_tools_undo to first,
            R.string.touch_tools_undo to original,
            R.string.touch_tools_redo to first,
            R.string.touch_tools_redo to second
        )) {
            toolbarClick(workspace, action)
            assertRect(expected, workspace.placements.getValue(stick.id))
            assertEquals(setOf("control:${stick.id}"), windowKeys(workspace))
        }
        click(workspace, topCard(workspace), R.string.touch_tools_cancel)
        assertRect(second, workspace.placements.getValue(stick.id))
    }

    @Test
    fun cancellingPropertiesPreservesTheSameControlsListEnabledChange() = withWorkspace { workspace ->
        val a = buttons(workspace).first
        val list = openControls(workspace)
        val properties = appearance(workspace, openFromList(workspace, list, a))
        nameField(properties, a.name).setText("Discard only this property edit")
        val row = action(list, controlRowLabel(workspace, a))
        val container = row.parent as ViewGroup
        val enabled = descendants(container).filterIsInstance<MaterialSwitch>().first()
        assertTrue(enabled.isChecked)
        assertTrue(enabled.performClick())
        settle(workspace)
        assertFalse(control(workspace, a.id).enabled)
        assertEquals("Discard only this property edit", control(workspace, a.id).name)

        click(workspace, properties, R.string.touch_tools_cancel)
        assertEquals(a.name, control(workspace, a.id).name)
        assertFalse(control(workspace, a.id).enabled)
        toolbarClick(workspace, R.string.touch_tools_undo)
        assertTrue(control(workspace, a.id).enabled)
        assertEquals(a.name, control(workspace, a.id).name)
        toolbarClick(workspace, R.string.touch_tools_redo)
        assertFalse(control(workspace, a.id).enabled)
        assertEquals(a.name, control(workspace, a.id).name)
    }

    @Test
    fun mainActionsAreFullyVisibleAndClosingOneWindowKeepsTheOthers() = withWorkspace { workspace ->
        val required = listOf(
            R.string.touch_tools_add,
            R.string.touch_tools_undo,
            R.string.touch_tools_redo,
            R.string.touch_tools_controls,
            R.string.touch_tools_test,
            R.string.touch_tools_save,
            R.string.touch_tools_set_base,
            R.string.touch_tools_tool_opacity
        )
        for (id in required) {
            val button = action(toolbar(workspace), workspace.context.getString(id))
            assertFullyVisible(workspace, button)
            assertTrue(button.isEnabled)
        }

        val list = openControls(workspace)
        val a = buttons(workspace).first
        val properties = appearance(workspace, openFromList(workspace, list, a))
        nameField(properties, a.name).setText("X commits this form only")
        toolbarClick(workspace, R.string.touch_tools_add)
        toolbarClick(workspace, R.string.touch_tools_tool_opacity)
        assertEquals(setOf("controls", "control:${a.id}", "add", "opacity"), windowKeys(workspace))
        val close = action(properties, workspace.context.getString(R.string.touch_tools_close))
        assertEquals("×", close.text.toString())
        assertTrue(close.performClick())
        settle(workspace)
        assertEquals(setOf("controls", "add", "opacity"), windowKeys(workspace))
        assertEquals("X commits this form only", control(workspace, a.id).name)
        assertTrue(list.parent === workspace.tools)
        toolbarClick(workspace, R.string.touch_tools_undo)
        assertEquals(a.name, control(workspace, a.id).name)
        assertEquals(setOf("controls", "add", "opacity"), windowKeys(workspace))
    }

    private fun withWorkspace(test: (TouchWorkspace) -> Unit) {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_Eden_Main)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        try {
            controller.setup().visible()
            assertEquals("en", activity.resources.configuration.locales[0].language)
            assertEquals(2f, activity.resources.displayMetrics.density, 0.01f)
            val library = TouchDefaultLibrary.create(activity)
            val workspace = TouchWorkspace(activity)
            workspace.bind(library, library.templates.first().id, false, object : InputSink {})
            workspace.setEditing(true)
            activity.setContentView(workspace)
            settle(workspace)
            assertFalse(workspace.gameAvailable)
            test(workspace)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun settle(workspace: TouchWorkspace) {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            workspace.measure(
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
            )
            workspace.layout(0, 0, 1920, 1080)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1920, workspace.width)
        assertEquals(1080, workspace.height)
    }

    private fun buttons(workspace: TouchWorkspace): Pair<TouchControl, TouchControl> {
        val controls = workspace.template.controls.filter { it.type == ControlType.BUTTON }
        return controls[0] to controls[1]
    }

    private fun control(workspace: TouchWorkspace, id: String): TouchControl =
        workspace.template.controls.single { it.id == id }

    private fun assertNames(workspace: TouchWorkspace, a: TouchControl, aName: String, b: TouchControl, bName: String) {
        assertEquals(aName, control(workspace, a.id).name)
        assertEquals(bName, control(workspace, b.id).name)
    }

    private fun windowKeys(workspace: TouchWorkspace): Set<String> =
        workspace.tools.exportState().windows.map { it.key }.toSet()

    private fun toolbar(workspace: TouchWorkspace): View = workspace.tools.getChildAt(0)

    private fun topCard(workspace: TouchWorkspace): MaterialCardView =
        workspace.tools.getChildAt(workspace.tools.childCount - 1) as MaterialCardView

    private fun openControls(workspace: TouchWorkspace): MaterialCardView {
        toolbarClick(workspace, R.string.touch_tools_controls)
        return topCard(workspace)
    }

    private fun controlRowLabel(workspace: TouchWorkspace, control: TouchControl): String =
        workspace.context.getString(
            R.string.touch_tools_control_row,
            control.name,
            workspace.context.getString(R.string.touch_tools_enum_controltype_button)
        )

    private fun openFromList(workspace: TouchWorkspace, list: View, control: TouchControl): MaterialCardView {
        assertTrue(action(list, controlRowLabel(workspace, control)).performClick())
        settle(workspace)
        assertEquals(control.id, workspace.selectedId)
        return topCard(workspace)
    }

    private fun appearance(workspace: TouchWorkspace, properties: View): MaterialCardView {
        val tabs = descendants(properties).filterIsInstance<TabLayout>().single()
        tabs.getTabAt(1)!!.select()
        settle(workspace)
        return topCard(workspace)
    }

    private fun nameField(properties: View, originalName: String): TextInputEditText {
        val field = descendants(properties).filterIsInstance<TextInputEditText>().first()
        assertEquals("Appearance must start with the control name", originalName, field.text.toString())
        return field
    }

    private fun action(root: View, label: String): MaterialButton =
        descendants(root).filterIsInstance<MaterialButton>().single {
            it.text.toString() == label || it.contentDescription?.toString() == label
        }

    private fun click(workspace: TouchWorkspace, root: View, stringId: Int) {
        assertTrue(action(root, workspace.context.getString(stringId)).performClick())
        settle(workspace)
    }

    private fun toolbarClick(workspace: TouchWorkspace, stringId: Int) = click(workspace, toolbar(workspace), stringId)

    private fun tapControl(workspace: TouchWorkspace, id: String) = gesture(workspace, id, 0f, 0f)

    private fun dragControl(workspace: TouchWorkspace, id: String, dx: Float, dy: Float) {
        val before = workspace.placements.getValue(id)
        gesture(workspace, id, dx, dy)
        assertNotEquals("Canvas drag was intercepted or rejected", before, workspace.placements.getValue(id))
    }

    private fun gesture(workspace: TouchWorkspace, id: String, dx: Float, dy: Float) {
        val rect = workspace.placements.getValue(id)
        val x = workspace.safe.left + rect.x + rect.width / 2f
        val y = workspace.safe.top + rect.y + rect.height / 2f
        val downTime = SystemClock.uptimeMillis()
        val events = mutableListOf(Triple(MotionEvent.ACTION_DOWN, x, y))
        if (dx != 0f || dy != 0f) events += Triple(MotionEvent.ACTION_MOVE, x + dx, y + dy)
        events += Triple(MotionEvent.ACTION_UP, x + dx, y + dy)
        events.forEachIndexed { index, (action, eventX, eventY) ->
            val event = MotionEvent.obtain(downTime, downTime + index * 16L, action, eventX, eventY, 0)
            try {
                assertTrue(workspace.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
        settle(workspace)
    }

    private fun assertRect(expected: TouchRect, actual: TouchRect) {
        assertEquals(expected.x, actual.x, 0.01f)
        assertEquals(expected.y, actual.y, 0.01f)
        assertEquals(expected.width, actual.width, 0.01f)
        assertEquals(expected.height, actual.height, 0.01f)
    }

    private fun assertFullyVisible(workspace: TouchWorkspace, view: View) {
        assertTrue("Action has no measured size", view.width > 0 && view.height > 0)
        val bounds = Rect(0, 0, view.width, view.height)
        workspace.offsetDescendantRectToMyCoords(view, bounds)
        assertTrue("Action extends beyond workspace: $bounds", Rect(0, 0, workspace.width, workspace.height).contains(bounds))
        val visible = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        var child = view
        while (child !== workspace) {
            assertEquals("Action is hidden by an ancestor", View.VISIBLE, child.visibility)
            val parent = child.parent as ViewGroup
            visible.offset(child.x - parent.scrollX, child.y - parent.scrollY)
            if (parent.clipChildren) {
                assertTrue("Action is clipped away", visible.intersect(0f, 0f, parent.width.toFloat(), parent.height.toFloat()))
            }
            child = parent
        }
        assertEquals("Action is horizontally clipped", view.width.toFloat(), visible.width(), 0.5f)
        assertEquals("Action is vertically clipped", view.height.toFloat(), visible.height(), 0.5f)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
