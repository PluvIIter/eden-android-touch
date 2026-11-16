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
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
import org.robolectric.shadows.ShadowDialog
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.overlay.touch.TouchDefaultLibrary
import org.yuzu.yuzu_emu.overlay.touch.model.ControlShape
import org.yuzu.yuzu_emu.overlay.touch.model.ControlType
import org.yuzu.yuzu_emu.overlay.touch.model.GestureDirection
import org.yuzu.yuzu_emu.overlay.touch.model.StickTarget
import org.yuzu.yuzu_emu.overlay.touch.model.TouchControl
import org.yuzu.yuzu_emu.overlay.touch.model.TouchRect
import org.yuzu.yuzu_emu.overlay.touch.runtime.InputSink
import kotlin.math.roundToInt

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
    fun switchingToolsCommitsTheCurrentFormAndKeepsEdits() = withWorkspace { workspace ->
        val (a, _) = buttons(workspace)
        val list = openControls(workspace)
        val aCard = appearance(workspace, openFromList(workspace, list, a))
        nameField(aCard, a.name).setText("A committed on switch")

        // Opening another tool finishes the previous one instead of stacking windows.
        toolbarClick(workspace, R.string.touch_tools_add)
        assertEquals(setOf("add"), windowKeys(workspace))
        assertEquals("A committed on switch", control(workspace, a.id).name)

        toolbarClick(workspace, R.string.touch_tools_controls)
        assertEquals(setOf("controls"), windowKeys(workspace))
        val aCardAgain = appearance(workspace, openFromList(workspace, topCard(workspace), control(workspace, a.id)))
        nameField(aCardAgain, "A committed on switch")
        click(workspace, aCardAgain, R.string.touch_tools_cancel)
        assertEquals("A committed on switch", control(workspace, a.id).name)
    }

    @Test
    fun sequentialFormsCreateIndependentUndoSteps() = withWorkspace { workspace ->
        val (a, b) = buttons(workspace)
        val list = openControls(workspace)
        val aCard = appearance(workspace, openFromList(workspace, list, a))
        nameField(aCard, a.name).setText("A committed")
        toolbarClick(workspace, R.string.touch_tools_controls)
        val bCard = appearance(workspace, openFromList(workspace, topCard(workspace), b))
        nameField(bCard, b.name).setText("B committed")

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
        // This test asserts exact finger deltas, so keep raw coordinates rather than the grid.
        workspace.toggleSnapToGrid()
        val stick = workspace.template.controls.first { it.type == ControlType.BUTTON }
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
        enabled.performClick()
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
    fun switchingGameTemplateChangesTheActiveEditingTemplate() = withWorkspace(gameAvailable = true) { workspace ->
        val original = workspace.draft.templates.first()
        val alternate = original.copy(
            id = "alternate-template",
            name = "Alternate",
            controls = original.controls.take(1)
        )
        workspace.replaceDraft(
            workspace.draft.copy(templates = workspace.draft.templates + alternate)
        )
        workspace.setEditing(false)
        workspace.switchGameTemplate(alternate.id)
        assertEquals(alternate.id, workspace.templateId)
        assertEquals("Alternate", workspace.template.name)
    }

    @Test
    fun mainActionsAreFullyVisibleAndOnlyOneToolWindowStaysOpen() = withWorkspace { workspace ->
        val required = listOf(
            R.string.touch_tools_add,
            R.string.touch_tools_undo,
            R.string.touch_tools_redo,
            R.string.touch_tools_controls,
            R.string.touch_tools_test,
            R.string.touch_tools_save,
            R.string.touch_tools_set_base,
            R.string.touch_tools_appearance,
            R.string.touch_tools_grid_on
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
        assertEquals(setOf("add"), windowKeys(workspace))
        toolbarClick(workspace, R.string.touch_tools_appearance)
        assertEquals(setOf("appearance"), windowKeys(workspace))
        assertEquals("X commits this form only", control(workspace, a.id).name)
    }

    @Test
    fun exitActionExistsAndInGameTestingIsUnavailable() = withWorkspace(gameAvailable = true) { workspace ->
        val actions = descendants(toolbar(workspace)).filterIsInstance<MaterialButton>().toList()
        assertTrue(actions.any { it.contentDescription?.toString() == workspace.context.getString(R.string.touch_tools_exit_game) })
        assertTrue(actions.none { it.text.toString() == workspace.context.getString(R.string.touch_tools_test) })
        workspace.startTest(false)
        assertFalse(workspace.isTesting)
    }

    @Test
    fun triggerOnlyStickSizeIsEditableOnPositionPage() = withWorkspace { workspace ->
        val stick = workspace.template.controls.first { it.type == ControlType.STICK && it.triggerOnly }
        tapControl(workspace, stick.id)
        val tabs = descendants(topCard(workspace)).filterIsInstance<TabLayout>().single()
        tabs.getTabAt(2)!!.select()
        settle(workspace)
        val page = topCard(workspace)
        val size = descendants(page).filterIsInstance<TextInputLayout>()
            .first { it.hint?.toString() == workspace.context.getString(R.string.touch_tools_stick_size) }
        (size.editText as TextInputEditText).setText("150")
        settle(workspace)
        assertEquals(150f, control(workspace, stick.id).stickDiameter, 0.01f)
    }

    @Test
    fun newButtonFollowsItsMappingName() = withWorkspace { workspace ->
        toolbarClick(workspace, R.string.touch_tools_add)
        click(workspace, topCard(workspace), R.string.touch_tools_button_description)
        assertEquals("", descendants(topCard(workspace)).filterIsInstance<TextInputEditText>().first().text.toString())

        action(topCard(workspace), workspace.context.getString(R.string.touch_tools_select_mapping)).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog()
        val root = dialog.window!!.decorView
        action(root, "L").performClick()
        action(root, "R").performClick()
        action(root, workspace.context.getString(R.string.touch_tools_done)).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("L + R", descendants(topCard(workspace)).filterIsInstance<TextInputEditText>().first().text.toString())
    }

    @Test
    fun liveTuningDraftIsSavedByTheOuterSave() = withWorkspace(gameAvailable = true) { workspace ->
        var saved: org.yuzu.yuzu_emu.overlay.touch.model.TouchLibrary? = null
        workspace.onSave = { library, _ -> saved = library }
        val swipe = workspace.template.controls.first { it.type == ControlType.SWIPE }
        workspace.select(swipe.id)
        workspace.startTest(true)
        val preset = workspace.draft.presets.first { it.id == swipe.swipePresetId }
        workspace.updatePreset(preset.copy(sensitivityX = 2.5f))
        workspace.finishTest(false)
        assertEquals(2.5f, workspace.draft.presets.first { it.id == preset.id }.sensitivityX, 0.001f)

        workspace.save()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2.5f, saved!!.presets.first { it.id == preset.id }.sensitivityX, 0.001f)
    }

    @Test
    fun placedButtonUsesMappingNameTextAndDefaultShape() = withWorkspace { workspace ->
        toolbarClick(workspace, R.string.touch_tools_add)
        click(workspace, topCard(workspace), R.string.touch_tools_button_description)
        action(topCard(workspace), workspace.context.getString(R.string.touch_tools_select_mapping)).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog()
        val dialogRoot = dialog.window!!.decorView
        action(dialogRoot, "L").performClick()
        action(dialogRoot, "R").performClick()
        action(dialogRoot, workspace.context.getString(R.string.touch_tools_done)).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("L + R", descendants(topCard(workspace)).filterIsInstance<TextInputEditText>().first().text.toString())

        click(workspace, topCard(workspace), R.string.touch_tools_place)
        settle(workspace)
        val downTime = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to downTime, MotionEvent.ACTION_UP to downTime + 16)) {
            val event = MotionEvent.obtain(downTime, time, action, workspace.safe.left + 700f, workspace.safe.top + 900f, 0)
            try { workspace.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        settle(workspace)

        val placed = workspace.template.controls.single { it.name == "L + R" }
        assertEquals("L + R", placed.text)
        assertEquals(ControlShape.RECTANGLE, placed.shape)
        assertEquals(setOf(6, 7), placed.binding.buttons)
        assertEquals(setOf("control:${placed.id}"), windowKeys(workspace))
        assertEquals(View.VISIBLE, workspace.tools.visibility)
    }

    @Test
    fun clickPlacementReusesTheSelectedSameTypeSize() = withWorkspace { workspace ->
        val existing = workspace.template.controls.first { it.type == ControlType.BUTTON }
        val expected = workspace.placements.getValue(existing.id)
        val beforeIds = workspace.template.controls.map { it.id }.toSet()
        tapControl(workspace, existing.id)
        toolbarClick(workspace, R.string.touch_tools_add)
        click(workspace, topCard(workspace), R.string.touch_tools_button_description)
        action(topCard(workspace), workspace.context.getString(R.string.touch_tools_select_mapping)).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialogRoot = ShadowDialog.getLatestDialog().window!!.decorView
        action(dialogRoot, "L").performClick()
        action(dialogRoot, workspace.context.getString(R.string.touch_tools_done)).performClick()
        click(workspace, topCard(workspace), R.string.touch_tools_place)
        settle(workspace)
        val downTime = SystemClock.uptimeMillis()
        for ((eventAction, time) in listOf(MotionEvent.ACTION_DOWN to downTime, MotionEvent.ACTION_UP to downTime + 16)) {
            val event = MotionEvent.obtain(downTime, time, eventAction, workspace.safe.left + 700f, workspace.safe.top + 900f, 0)
            try { workspace.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        settle(workspace)
        val placed = workspace.template.controls.first { it.id !in beforeIds }
        val rect = workspace.placements.getValue(placed.id)
        assertEquals(expected.width, rect.width, 0.5f)
        assertEquals(expected.height, rect.height, 0.5f)
    }

    @Test
    fun newButtonCanUseVisibilityActionFromTheMappingGrid() = withWorkspace { workspace ->
        val before = workspace.template.controls.map { it.id }.toSet()
        toolbarClick(workspace, R.string.touch_tools_add)
        click(workspace, topCard(workspace), R.string.touch_tools_button_description)
        action(topCard(workspace), workspace.context.getString(R.string.touch_tools_select_mapping)).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialogRoot = ShadowDialog.getLatestDialog().window!!.decorView
        action(dialogRoot, workspace.context.getString(R.string.touch_tools_action_toggle_short)).performClick()
        click(workspace, topCard(workspace), R.string.touch_tools_place)
        settle(workspace)
        val downTime = SystemClock.uptimeMillis()
        for ((eventAction, time) in listOf(MotionEvent.ACTION_DOWN to downTime, MotionEvent.ACTION_UP to downTime + 16)) {
            val event = MotionEvent.obtain(downTime, time, eventAction, workspace.safe.left + 700f, workspace.safe.top + 900f, 0)
            try { workspace.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        settle(workspace)
        val placed = workspace.template.controls.first { it.id !in before }
        assertEquals(org.yuzu.yuzu_emu.overlay.touch.model.ButtonAction.TOGGLE_VISIBILITY, placed.buttonAction)
    }

    @Test
    fun shapeTogglesFromThePropertyButton() = withWorkspace { workspace ->
        val control = workspace.template.controls.first { it.type == ControlType.BUTTON }
        tapControl(workspace, control.id)
        descendants(topCard(workspace)).filterIsInstance<TabLayout>().single().getTabAt(1)!!.select()
        settle(workspace)
        val shape = descendants(topCard(workspace)).filterIsInstance<MaterialButton>()
            .first { it.text.toString().startsWith(workspace.context.getString(R.string.touch_tools_shape).substringBefore(" ")) }
        assertEquals(ControlShape.CIRCLE, control(workspace, control.id).shape)
        shape.performClick()
        settle(workspace)
        assertEquals(ControlShape.RECTANGLE, control(workspace, control.id).shape)
    }

    @Test
    fun gridSnappingQuantizesADrag() = withWorkspace { workspace ->
        val control = buttons(workspace).first
        tapControl(workspace, control.id)
        dragControl(workspace, control.id, 80f, -40f)
        val moved = workspace.placements.getValue(control.id)
        val step = workspace.dp(16)
        assertEquals(moved.x, (moved.x / step).roundToInt() * step, 0.01f)
        assertEquals(moved.y, (moved.y / step).roundToInt() * step, 0.01f)
    }

    @Test
    fun propertiesCardAvoidsTheSelectedControl() = withWorkspace { workspace ->
        val control = workspace.template.controls.first { it.type == ControlType.BUTTON }
        tapControl(workspace, control.id)
        settle(workspace)
        val card = topCard(workspace)
        val rect = workspace.placements.getValue(control.id)
        val overlaps = card.x < rect.x + rect.width && card.x + card.width > rect.x &&
            card.y < rect.y + rect.height && card.y + card.height > rect.y
        assertFalse("Properties must not cover the selected control", overlaps)
    }

    @Test
    fun placementCanBeCancelledBackToTheAddPanel() = withWorkspace { workspace ->
        toolbarClick(workspace, R.string.touch_tools_add)
        click(workspace, topCard(workspace), R.string.touch_tools_gesture_description)
        val before = workspace.template.controls.size
        click(workspace, topCard(workspace), R.string.touch_tools_place)
        settle(workspace)

        val cancel = descendants(workspace).filterIsInstance<MaterialButton>()
            .single { it.text.toString() == workspace.context.getString(R.string.touch_workspace_cancel_placement) }
        assertEquals(View.VISIBLE, cancel.visibility)
        cancel.performClick()
        settle(workspace)

        assertEquals(setOf("add"), windowKeys(workspace))
        assertEquals(before, workspace.template.controls.size)
        val downTime = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to downTime, MotionEvent.ACTION_UP to downTime + 16)) {
            val event = MotionEvent.obtain(downTime, time, action, workspace.safe.left + 700f, workspace.safe.top + 900f, 0)
            try { workspace.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        settle(workspace)
        assertEquals(before, workspace.template.controls.size)
    }

    @Test
    fun gestureAreaNeedsNoPreconfiguredOutput() = withWorkspace { workspace ->
        placeGestureArea(workspace)
        val gesture = workspace.template.controls.last { it.type == ControlType.GESTURE }
        assertTrue(gesture.gestures.isEmpty())
        assertEquals(setOf("control:${gesture.id}"), windowKeys(workspace))
    }

    @Test
    fun unifiedGesturePickerMapsAStickDirection() = withWorkspace { workspace ->
        placeGestureArea(workspace)
        descendants(topCard(workspace)).filterIsInstance<MaterialButton>()
            .first { it.text.toString().startsWith(workspace.context.getString(R.string.touch_tools_tap)) }
            .performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog()
        val root = dialog.window!!.decorView
        descendants(root).filterIsInstance<MaterialButton>()
            .first { it.contentDescription?.toString() == "↑" }
            .performClick()
        descendants(root).filterIsInstance<MaterialButton>()
            .first { it.text.toString() == workspace.context.getString(R.string.touch_tools_done) }
            .performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val gesture = workspace.template.controls.last { it.type == ControlType.GESTURE }
        val binding = gesture.gestures.getValue(GestureDirection.TAP)
        assertEquals(StickTarget.LEFT, binding.stick)
        assertEquals(GestureDirection.UP, binding.direction)
    }

    @Test
    fun stickModeAndTargetToggleFromButtons() = withWorkspace { workspace ->
        val stick = workspace.template.controls.first { it.type == ControlType.STICK }
        tapControl(workspace, stick.id)
        settle(workspace)
        fun mode() = descendants(topCard(workspace)).filterIsInstance<MaterialButton>().first {
            it.text.toString().startsWith(workspace.context.getString(R.string.touch_tools_stick_mode_value, ""))
        }
        assertTrue(mode().text.toString().contains(workspace.context.getString(R.string.touch_tools_stick_free)))
        mode().performClick()
        settle(workspace)
        assertFalse(control(workspace, stick.id).freeStick)
        assertTrue(control(workspace, stick.id).triggerOnly)
        assertTrue(mode().text.toString().contains(workspace.context.getString(R.string.touch_tools_stick_fixed)))
        assertTrue(
            descendants(topCard(workspace)).filterIsInstance<MaterialSwitch>()
                .any { it.text.toString() == workspace.context.getString(R.string.touch_tools_trigger_only) }
        )

        val before = ShadowDialog.getLatestDialog()
        val target = descendants(topCard(workspace)).filterIsInstance<MaterialButton>().first {
            it.text.toString().startsWith(workspace.context.getString(R.string.touch_tools_target_stick))
        }
        val original = control(workspace, stick.id).stickTarget
        target.performClick()
        settle(workspace)
        assertNotEquals(original, control(workspace, stick.id).stickTarget)
        assertTrue(before === ShadowDialog.getLatestDialog())
        target.performClick()
        settle(workspace)
        assertEquals(original, control(workspace, stick.id).stickTarget)

        mode().performClick()
        settle(workspace)
        assertTrue(control(workspace, stick.id).freeStick)
    }

    @Test
    fun visibleStickUsesOnlyItsCircleSize() = withWorkspace { workspace ->
        val stick = workspace.template.controls.first { it.type == ControlType.STICK }
        tapControl(workspace, stick.id)
        settle(workspace)
        descendants(topCard(workspace)).filterIsInstance<MaterialSwitch>()
            .first { it.text.toString() == workspace.context.getString(R.string.touch_tools_trigger_only) }
            .performClick()
        settle(workspace)

        val visible = control(workspace, stick.id)
        assertFalse(visible.triggerOnly)
        val density = workspace.resources.displayMetrics.density
        assertEquals(visible.stickDiameter * density, visible.bounds.width, 0.01f)
        assertEquals(visible.stickDiameter * density, visible.bounds.height, 0.01f)

        descendants(topCard(workspace)).filterIsInstance<TabLayout>().single().getTabAt(2)!!.select()
        settle(workspace)
        val hints = descendants(topCard(workspace)).filterIsInstance<TextInputLayout>()
            .mapNotNull { it.hint?.toString() }
        assertTrue(hints.contains(workspace.context.getString(R.string.touch_tools_x)))
        assertTrue(hints.contains(workspace.context.getString(R.string.touch_tools_stick_size)))
        assertFalse(hints.contains(workspace.context.getString(R.string.touch_tools_width)))
        assertFalse(hints.contains(workspace.context.getString(R.string.touch_tools_height)))
    }

    @Test
    fun bindingInvalidControlsRemovesAndReportsThem() = withWorkspace { workspace ->
        val bad = TouchControl(type = ControlType.STICK, bounds = TouchRect(0f, 0f, 100f, 100f), stickDiameter = 80f)
        val template = workspace.template
        val library = workspace.draft.copy(templates = listOf(template.copy(controls = template.controls + bad)))
        workspace.bind(library, template.id, false, object : InputSink {})
        settle(workspace)
        assertTrue(workspace.template.controls.none { it.id == bad.id })
        assertTrue(ShadowDialog.getLatestDialog() != null)
    }

    @Test
    fun unifiedGesturePickerSupportsButtonCombinations() = withWorkspace { workspace ->
        placeGestureArea(workspace)
        descendants(topCard(workspace)).filterIsInstance<MaterialButton>()
            .first { it.text.toString().startsWith(workspace.context.getString(R.string.touch_tools_tap)) }
            .performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog()
        val root = dialog.window!!.decorView
        descendants(root).filterIsInstance<MaterialButton>().first { it.text.toString() == "A" }.performClick()
        descendants(root).filterIsInstance<MaterialButton>().first { it.text.toString() == "B" }.performClick()
        descendants(root).filterIsInstance<MaterialButton>()
            .first { it.text.toString() == workspace.context.getString(R.string.touch_tools_done) }
            .performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val gesture = workspace.template.controls.last { it.type == ControlType.GESTURE }
        assertEquals(setOf(0, 1), gesture.gestures.getValue(GestureDirection.TAP).buttons)
    }

    @Test
    fun areaBorderEnablesTextOverlay() = withWorkspace { workspace ->
        placeGestureArea(workspace)
        descendants(topCard(workspace)).filterIsInstance<TabLayout>().single().getTabAt(1)!!.select()
        settle(workspace)
        descendants(topCard(workspace)).filterIsInstance<MaterialSwitch>()
            .first { it.text.toString() == workspace.context.getString(R.string.touch_tools_area_border) }
            .performClick()
        settle(workspace)
        var gesture = workspace.template.controls.last { it.type == ControlType.GESTURE }
        assertTrue(gesture.showBorder)
        descendants(topCard(workspace)).filterIsInstance<MaterialSwitch>()
            .first { it.text.toString() == workspace.context.getString(R.string.touch_tools_area_text) }
            .performClick()
        settle(workspace)
        gesture = workspace.template.controls.last { it.type == ControlType.GESTURE }
        assertTrue(gesture.showText)
        descendants(topCard(workspace)).filterIsInstance<MaterialSwitch>()
            .first { it.text.toString() == workspace.context.getString(R.string.touch_tools_area_fill) }
            .performClick()
        settle(workspace)
        gesture = workspace.template.controls.last { it.type == ControlType.GESTURE }
        assertTrue(gesture.showFill)
    }

    @Test
    fun globalAppearanceColorAndOpacityAreApplied() = withWorkspace { workspace ->
        val color = 0xFF12AB34.toInt()
        workspace.applyAllColors(color)
        assertTrue(workspace.template.controls.all { it.color == color })

        val initialDefaultOpacity = workspace.template.defaultOpacity
        workspace.updateGlobalOpacity(1.5f)
        assertEquals(1.5f, workspace.template.globalOpacity, 0.001f)
        assertEquals(initialDefaultOpacity, workspace.template.defaultOpacity)
        workspace.updateControlOpacity(0.5f)
        assertEquals(0.5f, workspace.template.controlOpacity ?: -1f, 0.001f)
        assertEquals(0.5f, workspace.template.defaultOpacity ?: -1f, 0.001f)
        assertTrue(workspace.template.controls.all { kotlin.math.abs(it.opacity - 0.5f) < 0.001f })
    }

    @Test
    fun appearanceGroupsCycleColorAndOverlay() = withWorkspace(gameAvailable = true) { workspace ->
        workspace.addAppearanceGroup()
        val group = workspace.template.appearanceGroups[1]
        val color = 0xFF12AB34.toInt()
        workspace.updateAppearanceGroup(1, group.copy(color = color, overlayOpacity = 1.5f))
        workspace.cycleAppearance()
        assertEquals(1.5f, workspace.effectiveGlobalOpacity(), 0.001f)
        assertEquals(color, workspace.effectiveColor(workspace.template.controls.first()))
        workspace.cycleAppearance()
        assertEquals(workspace.template.globalOpacity, workspace.effectiveGlobalOpacity(), 0.001f)
    }

    @Test
    fun controlOpacitySyncSkipsEndpointControls() = withWorkspace { workspace ->
        val controls = workspace.template.controls
        val intermediate = controls[0]
        val opaque = controls[1]
        workspace.updateControl(intermediate.copy(opacity = 0.4f))
        workspace.updateControl(opaque.copy(opacity = 1f))
        workspace.updateControlOpacity(0.25f)
        assertEquals(0.25f, workspace.template.controls.first { it.id == intermediate.id }.opacity, 0.001f)
        assertEquals(1f, workspace.template.controls.first { it.id == opaque.id }.opacity, 0.001f)
    }

    @Test
    fun newControlUsesTheLastAppliedGlobalOpacity() = withWorkspace { workspace ->
        val before = workspace.template.controls.map { it.id }.toSet()
        workspace.updateControlOpacity(0.15f)
        workspace.updateGlobalOpacity(1.5f)
        toolbarClick(workspace, R.string.touch_tools_add)
        click(workspace, topCard(workspace), R.string.touch_tools_button_description)
        action(topCard(workspace), workspace.context.getString(R.string.touch_tools_select_mapping)).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialogRoot = ShadowDialog.getLatestDialog().window!!.decorView
        action(dialogRoot, "L").performClick()
        action(dialogRoot, workspace.context.getString(R.string.touch_tools_done)).performClick()
        click(workspace, topCard(workspace), R.string.touch_tools_place)
        settle(workspace)
        val downTime = SystemClock.uptimeMillis()
        for ((eventAction, time) in listOf(MotionEvent.ACTION_DOWN to downTime, MotionEvent.ACTION_UP to downTime + 16)) {
            val event = MotionEvent.obtain(downTime, time, eventAction, workspace.safe.left + 700f, workspace.safe.top + 900f, 0)
            try { workspace.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        settle(workspace)
        val placed = workspace.template.controls.first { it.id !in before }
        assertEquals(0.15f, placed.opacity, 0.001f)
    }

    @Test
    fun imeHeightRestoresEvenAfterFocusIsLost() = withWorkspace { workspace ->
        toolbarClick(workspace, R.string.touch_tools_add)
        click(workspace, topCard(workspace), R.string.touch_tools_button_description)
        settle(workspace)
        val card = topCard(workspace)
        val start = card.height
        descendants(card).filterIsInstance<TextInputEditText>().first().requestFocus()
        workspace.tools.onImeInsets(700)
        settle(workspace)
        assertTrue(card.height < start)
        descendants(card).filterIsInstance<TextInputEditText>().first().clearFocus()
        workspace.tools.onImeInsets(0)
        settle(workspace)
        assertEquals(start, card.height)
    }

    @Test
    fun panelResizeIsPersistedInTheToolState() = withWorkspace { workspace ->
        val control = workspace.template.controls.first { it.type == ControlType.BUTTON }
        tapControl(workspace, control.id)
        settle(workspace)
        val card = topCard(workspace)
        val grip = descendants(card).filterIsInstance<ImageView>()
            .single { it.contentDescription?.toString() == workspace.context.getString(R.string.touch_tools_resize) }
        val startWidth = card.width
        val startHeight = card.height
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, time: Long, dx: Float, dy: Float) {
            val event = MotionEvent.obtain(downTime, time, action, dx, dy, 0)
            try { grip.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, downTime, 0f, 0f)
        send(MotionEvent.ACTION_MOVE, downTime + 16, 80f, 80f)
        send(MotionEvent.ACTION_UP, downTime + 32, 80f, 80f)
        settle(workspace)
        assertTrue(card.width > startWidth)
        assertTrue(card.height > startHeight)

        workspace.tools.closeAll()
        settle(workspace)
        tapControl(workspace, control.id)
        settle(workspace)
        assertEquals(card.width, topCard(workspace).width)
        assertEquals(card.height, topCard(workspace).height)

        val state = workspace.tools.exportState()
        workspace.tools.restoreState(state)
        settle(workspace)
        val reopened = topCard(workspace)
        assertEquals(card.width, reopened.width)
        assertEquals(card.height, reopened.height)
    }

    private fun placeGestureArea(workspace: TouchWorkspace) {
        toolbarClick(workspace, R.string.touch_tools_add)
        click(workspace, topCard(workspace), R.string.touch_tools_gesture_description)
        click(workspace, topCard(workspace), R.string.touch_tools_place)
        settle(workspace)
        val downTime = SystemClock.uptimeMillis()
        val x = workspace.safe.left + 700f
        val y = workspace.safe.top + 700f
        val points = listOf(
            Triple(MotionEvent.ACTION_DOWN, x, y),
            Triple(MotionEvent.ACTION_MOVE, x + 80f, y + 80f),
            Triple(MotionEvent.ACTION_UP, x + 80f, y + 80f)
        )
        points.forEachIndexed { index, (action, px, py) ->
            val event = MotionEvent.obtain(downTime, downTime + index * 16L, action, px, py, 0)
            try { workspace.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        settle(workspace)
    }

    private fun withWorkspace(gameAvailable: Boolean = false, test: (TouchWorkspace) -> Unit) {
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
            workspace.bind(library, library.templates.first().id, gameAvailable, object : InputSink {})
            workspace.setEditing(true)
            activity.setContentView(workspace)
            settle(workspace)
            assertEquals(gameAvailable, workspace.gameAvailable)
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
        workspace.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        workspace.layout(0, 0, 1920, 1080)
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

    private fun toolbar(workspace: TouchWorkspace): View {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            descendants(workspace).forEach { it.forceLayout() }
            workspace.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            workspace.layout(0, 0, 1920, 1080)
        }
        return workspace.tools.getChildAt(0)
    }

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
        assertTrue("Action ${view.contentDescription ?: (view as? android.widget.TextView)?.text} has no measured size ${view.width}x${view.height}; parent=${(view.parent as View).width}x${(view.parent as View).height}", view.width > 0 && view.height > 0)
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
