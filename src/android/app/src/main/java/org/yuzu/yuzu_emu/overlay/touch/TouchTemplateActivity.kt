// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.overlay.touch.model.SwipePreset
import org.yuzu.yuzu_emu.overlay.touch.model.TouchLibrary
import org.yuzu.yuzu_emu.overlay.touch.model.TouchTemplate
import org.yuzu.yuzu_emu.overlay.touch.model.newTouchId
import org.yuzu.yuzu_emu.overlay.touch.runtime.InputSink
import org.yuzu.yuzu_emu.overlay.touch.store.ImportConflictResolution
import org.yuzu.yuzu_emu.overlay.touch.store.TouchImportExport
import org.yuzu.yuzu_emu.overlay.touch.store.TouchImportPreview
import org.yuzu.yuzu_emu.overlay.touch.store.TouchObjectKind
import org.yuzu.yuzu_emu.overlay.touch.store.TouchReferences
import org.yuzu.yuzu_emu.overlay.touch.store.TouchRepository
import org.yuzu.yuzu_emu.overlay.touch.store.TouchRevisionConflictException
import org.yuzu.yuzu_emu.overlay.touch.store.TouchValidationException
import org.yuzu.yuzu_emu.overlay.touch.ui.TouchWorkspace
import org.yuzu.yuzu_emu.utils.ThemeHelper

class TouchTemplateActivity : AppCompatActivity() {
    private lateinit var repository: TouchRepository
    private lateinit var library: TouchLibrary
    private var workspace: TouchWorkspace? = null
    private var exporting: String? = null
    private var exportRecovery: File? = null
    private var selectedTab = 0
    private var searchQuery = ""
    private var busy = false
    private val gameId get() = intent.getStringExtra(EXTRA_GAME_ID)?.let { TouchReferences.baseGameId(it) }

    private class ManagerException(val messageId: Int) : IOException()

    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            background {
                val value = contentResolver.openInputStream(uri)?.use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_IMPORT_BYTES) throw ManagerException(R.string.touch_manager_error_too_large)
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                } ?: throw ManagerException(R.string.touch_manager_error_read)
                val current = repository.load()
                val preview = TouchImportExport.preview(current, value)
                withContext(Dispatchers.Main) {
                    library = current
                    showImportPreview(value, preview)
                }
            }
        }
    }

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val value = exporting
        exporting = null
        clearExportRecovery()
        if (uri != null && value != null) {
            background {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(value) }
                    ?: throw ManagerException(R.string.touch_manager_error_write)
                withContext(Dispatchers.Main) { toast(R.string.touch_manager_exported) }
            }
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(YuzuApplication.applyLanguage(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.setTheme(this)
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        repository = repository(this)
        runCatching { library = repository.load() }.onFailure { report(it); finish(); return }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val current = workspace
                    if (current == null) finish() else if (!current.handleBack()) showManager()
                }
            }
        )
        selectedTab = savedInstanceState?.getInt("touch_manager_tab") ?: 0
        searchQuery = savedInstanceState?.getString("touch_manager_search").orEmpty()
        val pendingExport = savedInstanceState?.getString("touch_export_token")
        if (pendingExport?.matches(Regex("[0-9a-f-]{36}")) == true) {
            val file = File(filesDir, "touch/exports/$pendingExport")
            if (file.isFile) {
                exporting = file.readText(Charsets.UTF_8)
                exportRecovery = file
            }
        }
        showManager()
        if (savedInstanceState?.containsKey("touch_workspace_draft") == true) {
            runCatching {
                val initial = library.templateFor(gameId) ?: library.templates.first()
                edit(initial.id)
                TouchDraftState.restore(this, savedInstanceState, workspace!!)
            }.onFailure { report(it); showManager() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        runCatching {
            TouchDraftState.save(this, outState, workspace)
            outState.putInt("touch_manager_tab", selectedTab)
            outState.putString("touch_manager_search", searchQuery)
            exporting?.let { value ->
                val directory = File(filesDir, "touch/exports")
                check(directory.isDirectory || directory.mkdirs())
                val file = exportRecovery ?: File(directory, java.util.UUID.randomUUID().toString()).also { exportRecovery = it }
                file.writeText(value, Charsets.UTF_8)
                outState.putString("touch_export_token", file.name)
            }
        }.onFailure { report(it) }
        super.onSaveInstanceState(outState)
    }

    private fun showManager() {
        workspace?.releaseInputs()
        workspace = null
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        val root = FrameLayout(this).apply {
            setBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
        }
        val page = column()
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        val toolbar = MaterialToolbar(this).apply {
            setTitle(R.string.touch_manager_title)
            setNavigationIcon(R.drawable.ic_back)
            setNavigationContentDescription(R.string.touch_manager_back)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            menu.add(0, MENU_IMPORT, 0, R.string.touch_manager_import).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            if (gameId != null) {
                menu.add(0, MENU_DEFAULT, 1, R.string.touch_manager_follow_default)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            }
            setOnMenuItemClickListener {
                when (it.itemId) {
                    MENU_IMPORT -> importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    MENU_DEFAULT -> confirm(getString(R.string.touch_manager_change_game), getString(R.string.touch_manager_follow_default_message)) {
                        persist(TouchReferences.associateGame(library, gameId!!, null))
                    }
                }
                true
            }
        }
        page.addView(toolbar, LinearLayout.LayoutParams(-1, dp(64)))
        val tabs = TabLayout(this).apply {
            tabMode = TabLayout.MODE_FIXED
            addTab(newTab().setText(R.string.touch_manager_layouts), selectedTab == 0)
            addTab(newTab().setText(R.string.touch_manager_presets), selectedTab == 1)
        }
        page.addView(tabs, LinearLayout.LayoutParams(-1, -2))
        val searchLayout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = getString(R.string.touch_manager_search)
            setStartIconDrawable(R.drawable.ic_search)
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
        }
        val search = TextInputEditText(searchLayout.context).apply { setSingleLine(); setText(searchQuery) }
        searchLayout.addView(search, LinearLayout.LayoutParams(-1, -2))
        page.addView(searchLayout, margins(-1, -2, 16, 12, 16, 4))
        val list = column().apply { setPadding(dp(16), dp(8), dp(16), dp(112)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; addView(list) }
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val create = ExtendedFloatingActionButton(this).apply {
            setIconResource(R.drawable.ic_add)
            setOnClickListener { createObject() }
        }
        root.addView(
            create,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(dp(16), dp(16), dp(16), dp(24))
            }
        )
        fun render() {
            list.removeAllViews()
            create.setText(if (selectedTab == 0) R.string.touch_manager_create_layout else R.string.touch_manager_create_preset)
            val hint = if (selectedTab == 0) R.string.touch_manager_shared_layout_hint else R.string.touch_manager_shared_preset_hint
            var count = 0
            if (selectedTab == 0) {
                library.templates.filter { it.name.contains(searchQuery, true) }.forEach { template ->
                    list.addView(layoutCard(template)); count++
                }
            } else {
                library.presets.filter { it.name.contains(searchQuery, true) }.forEach { preset ->
                    list.addView(presetCard(preset)); count++
                }
            }
            if (count == 0) {
                list.addView(
                    label(getString(if (searchQuery.isBlank()) R.string.touch_manager_empty else R.string.touch_manager_no_matches)).apply {
                        gravity = Gravity.CENTER; setPadding(dp(24), dp(48), dp(24), dp(16))
                    }
                )
                if (searchQuery.isNotBlank()) list.addView(textButton(R.string.touch_manager_clear_search) { search.setText("") })
            }
            list.addView(label(getString(hint), true).apply { setPadding(dp(4), dp(4), dp(4), dp(16)) })
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { selectedTab = tab.position; render() }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        search.doAfterTextChanged { searchQuery = it?.toString().orEmpty(); render() }
        render()
        setContentView(root)
    }

    private fun layoutCard(template: TouchTemplate): MaterialCardView {
        val refs = TouchReferences.template(library, template.id)
        return objectCard(
            template.name,
            getString(R.string.touch_manager_layout_metadata, template.controls.size, template.screens.size),
            { edit(template.id) },
            { anchor -> templateMenu(template, anchor) },
            if (gameId == null) null else ({ persist(TouchReferences.associateGame(library, gameId!!, template.id)) })
        ) { tags ->
            if (refs.isDefault) tags.addView(tag(getString(R.string.touch_manager_global_default)))
            tags.addView(
                tag(getString(R.string.touch_manager_games_count, refs.gameIds.size)) {
                    showDetails(getString(R.string.touch_manager_games_using, template.name), templateImpact(template.id))
                }
            )
            if (gameId != null && library.templateFor(gameId)?.id == template.id) tags.addView(tag(getString(R.string.touch_manager_current_game)))
        }
    }

    private fun presetCard(preset: SwipePreset): MaterialCardView = objectCard(
        preset.name,
        getString(R.string.touch_manager_preset_metadata, preset.sensitivityX, preset.sensitivityY),
        { editPreset(preset.id) },
        { anchor -> presetMenu(preset, anchor) }
    ) { tags ->
        tags.addView(
            tag(getString(R.string.touch_manager_areas_count, TouchReferences.preset(library, preset.id).size)) {
                showDetails(getString(R.string.touch_manager_areas_using, preset.name), presetImpact(preset.id))
            }
        )
        tags.addView(tag(getString(R.string.touch_manager_shared_reference)))
    }

    private fun objectCard(name: String, metadata: String, edit: () -> Unit, more: (View) -> Unit, applyToGame: (() -> Unit)? = null, tags: (ChipGroup) -> Unit): MaterialCardView {
        val card = MaterialCardView(ContextThemeWrapper(this, R.style.EdenCard)).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            setStrokeColor(color(com.google.android.material.R.attr.colorOutline))
            setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
            layoutParams = margins(-1, -2, 0, 0, 0, 16)
        }
        val body = column().apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label(name).apply { textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(
            AppCompatImageButton(this, null, android.R.attr.borderlessButtonStyle).apply {
                setImageResource(R.drawable.ic_more_vert)
                imageTintList = android.content.res.ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                contentDescription = getString(R.string.touch_manager_more_for, name)
                setOnClickListener { more(this) }
            },
            LinearLayout.LayoutParams(dp(48), dp(48))
        )
        body.addView(heading)
        body.addView(label(metadata, true))
        body.addView(ChipGroup(this).apply { chipSpacingHorizontal = dp(8); tags(this) }, margins(-1, -2, 0, 8, 0, 8))
        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false }
        buttons.addView(
            MaterialButton(ContextThemeWrapper(this, R.style.TouchButton_Theme_Primary), null, com.google.android.material.R.attr.materialButtonStyle).apply {
                setText(R.string.touch_manager_edit)
                setIconResource(R.drawable.ic_edit)
                isAllCaps = false
                setOnClickListener { edit() }
                contentDescription = getString(R.string.touch_manager_edit_named, name)
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        if (applyToGame != null) {
            buttons.addView(textButton(R.string.touch_manager_use_game) { applyToGame() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        }
        body.addView(buttons)
        card.addView(body)
        return card
    }

    private fun tag(text: String, action: (() -> Unit)? = null): Chip = Chip(this).apply {
        this.text = text
        isCheckable = false
        isClickable = action != null
        isFocusable = action != null
        setEnsureMinTouchTargetSize(action != null)
        if (action != null) setOnClickListener { action() }
    }

    private fun templateMenu(template: TouchTemplate, anchor: View) {
        val options = mutableListOf<Pair<Int, () -> Unit>>(
            R.string.touch_manager_view_games to { showDetails(getString(R.string.touch_manager_games_using, template.name), templateImpact(template.id)) },
            R.string.touch_manager_rename to {
                nameDialog(getString(R.string.touch_manager_rename_layout), template.name) { name ->
                    if (name != template.name) {
                        confirm(getString(R.string.touch_manager_rename_shared_layout), templateImpact(template.id)) {
                            persist(library.copy(templates = library.templates.map { if (it.id == template.id) it.copy(name = name) else it }))
                        }
                    }
                }
            },
            R.string.touch_manager_duplicate to { persist(library.copy(templates = library.templates + TouchReferences.copyTemplate(template, copyName(template.name)))) },
            R.string.touch_manager_use_default to {
                confirm(getString(R.string.touch_manager_change_default), getString(R.string.touch_manager_default_message, template.name)) {
                    persist(library.copy(defaultTemplateId = template.id))
                }
            },
            R.string.touch_manager_export to { exporting = repository.exportTemplate(template.id); exportFile.launch("${template.name}.json") },
            R.string.touch_manager_delete to { deleteTemplate(template) }
        )
        if (gameId != null) {
            options += R.string.touch_manager_use_game to {
                confirm(getString(R.string.touch_manager_apply_named, template.name), getString(R.string.touch_manager_apply_message, gameId)) {
                    persist(TouchReferences.associateGame(library, gameId!!, template.id))
                }
            }
            options += R.string.touch_manager_copy_game to {
                nameDialog(getString(R.string.touch_manager_copy_game), copyName(template.name)) { name ->
                    val duplicate = TouchReferences.copyTemplate(template, name)
                    persist(TouchReferences.associateGame(library.copy(templates = library.templates + duplicate), gameId!!, duplicate.id))
                }
            }
        }
        overflow(anchor, options)
    }

    private fun presetMenu(preset: SwipePreset, anchor: View) = overflow(
        anchor,
        listOf(
            R.string.touch_manager_view_areas to { showDetails(getString(R.string.touch_manager_areas_using, preset.name), presetImpact(preset.id)) },
            R.string.touch_manager_rename to {
                nameDialog(getString(R.string.touch_manager_rename_preset), preset.name) { name ->
                    if (name != preset.name) {
                        confirm(getString(R.string.touch_manager_rename_shared_preset), presetImpact(preset.id)) {
                            persist(library.copy(presets = library.presets.map { if (it.id == preset.id) it.copy(name = name) else it }))
                        }
                    }
                }
            },
            R.string.touch_manager_duplicate to { persist(library.copy(presets = library.presets + preset.copy(id = newTouchId(), name = copyName(preset.name)))) },
            R.string.touch_manager_export to { exporting = repository.exportPreset(preset.id); exportFile.launch("${preset.name}.json") },
            R.string.touch_manager_delete to { deletePreset(preset) }
        )
    )

    private fun overflow(anchor: View, options: List<Pair<Int, () -> Unit>>) {
        PopupMenu(this, anchor).apply {
            options.forEachIndexed { index, option -> menu.add(0, index, index, option.first) }
            setOnMenuItemClickListener { runCatching { options[it.itemId].second() }.onFailure { error -> report(error) }; true }
            show()
        }
    }

    private fun createObject() {
        if (selectedTab == 0) {
            nameDialog(getString(R.string.touch_manager_create_layout), getString(R.string.touch_manager_new_layout)) { name ->
                val template = TouchTemplate(name = name)
                if (persist(library.copy(templates = library.templates + template, defaultTemplateId = library.defaultTemplateId ?: template.id))) edit(template.id)
            }
        } else {
            nameDialog(getString(R.string.touch_manager_create_preset), getString(R.string.touch_manager_new_preset)) { name ->
                val preset = SwipePreset(name = name)
                if (persist(library.copy(presets = library.presets + preset))) editPreset(preset.id)
            }
        }
    }

    private fun deleteTemplate(template: TouchTemplate) {
        val replacements = library.templates.filterNot { it.id == template.id }
        if (replacements.isEmpty()) { toast(R.string.touch_manager_keep_layout); return }
        val choices = listOf(getString(R.string.touch_manager_unlink_games)) + replacements.map { getString(R.string.touch_manager_replace_with, it.name) }
        MaterialAlertDialogBuilder(this@TouchTemplateActivity).setTitle(getString(R.string.touch_manager_delete_named, template.name))
            .setItems(choices.toTypedArray()) { _, index ->
                val replacement = replacements.getOrNull(index - 1)?.id
                confirm(getString(R.string.touch_manager_delete_layout), templateImpact(template.id) + "\n\n" + choices[index]) {
                    persist(TouchReferences.deleteTemplate(library, template.id, replacement))
                }
            }.setNegativeButton(R.string.touch_manager_cancel, null).show()
    }

    private fun deletePreset(preset: SwipePreset) {
        if (TouchReferences.preset(library, preset.id).isEmpty()) {
            confirm(getString(R.string.touch_manager_delete_named, preset.name), getString(R.string.touch_manager_no_area_references)) {
                persist(TouchReferences.deletePreset(library, preset.id))
            }
            return
        }
        val replacements = library.presets.filterNot { it.id == preset.id }
        val choices = replacements.map { getString(R.string.touch_manager_replace_with, it.name) } + getString(R.string.touch_manager_delete_areas)
        MaterialAlertDialogBuilder(this@TouchTemplateActivity).setTitle(R.string.touch_manager_replace_references)
            .setItems(choices.toTypedArray()) { _, index ->
                val replacement = replacements.getOrNull(index)?.id
                confirm(getString(R.string.touch_manager_delete_shared_preset), presetImpact(preset.id) + "\n\n" + choices[index]) {
                    persist(TouchReferences.deletePreset(library, preset.id, replacement, deleteReferencedControls = replacement == null))
                }
            }.setNegativeButton(R.string.touch_manager_cancel, null).show()
    }

    private fun templateImpact(id: String): String = TouchReferences.template(library, id).let { refs ->
        buildString {
            append(getString(R.string.touch_manager_explicit_games, refs.gameIds.size))
            if (refs.isDefault) append("\n" + getString(R.string.touch_manager_default_impact))
            refs.gameIds.forEach { append("\n• $it") }
        }
    }

    private fun presetImpact(id: String): String = buildString {
        val refs = TouchReferences.preset(library, id)
        append(getString(R.string.touch_manager_areas_count, refs.size))
        refs.forEach { reference ->
            val template = library.templates.first { it.id == reference.templateId }
            val control = template.controls.first { it.id == reference.controlId }
            append("\n• ${template.name} / ${control.name.ifBlank { control.id }}")
        }
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(this@TouchTemplateActivity).setTitle(title).setMessage(message)
            .setPositiveButton(R.string.touch_manager_confirm) { _, _ -> runCatching(action).onFailure { report(it) } }
            .setNegativeButton(R.string.touch_manager_cancel, null).show()
    }

    private fun showDetails(title: String, message: String) {
        MaterialAlertDialogBuilder(this@TouchTemplateActivity).setTitle(title).setMessage(message).setPositiveButton(R.string.touch_manager_close, null).show()
    }

    private fun editPreset(presetId: String) {
        val owner = library.templates.firstOrNull { template -> template.controls.any { it.swipePresetId == presetId } }
            ?: library.templates.first()
        edit(owner.id)
        workspace?.editPreset(presetId, standalone = true)
    }

    private fun edit(templateId: String) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        workspace = TouchWorkspace(this).apply {
            this.gameId = this@TouchTemplateActivity.gameId
            bind(library, templateId, false, object : InputSink {})
            onSave = { draft, _ ->
                runCatching {
                    library = repository.save(draft, draftBaselineRevision)
                    workspace?.acceptSaved(library)
                    TouchDraftState.discard(this@TouchTemplateActivity, workspace)
                    toast(R.string.touch_manager_saved)
                }.onFailure { report(it); throw it }
            }
            onExit = { TouchDraftState.discard(this@TouchTemplateActivity, workspace); showManager() }
            setEditing(true)
        }
        setContentView(workspace!!)
    }

    private fun showImportPreview(value: String, preview: TouchImportPreview) {
        val summary = importSummary(preview)
        val builder = MaterialAlertDialogBuilder(this@TouchTemplateActivity).setTitle(R.string.touch_manager_import_preview)
            .setMessage(summary + "\n\n" + getString(R.string.touch_manager_import_no_association))
            .setPositiveButton(if (preview.conflicts.isEmpty()) R.string.touch_manager_import else R.string.touch_manager_import_copies) { _, _ -> import(value, ImportConflictResolution.COPY) }
            .setNegativeButton(R.string.touch_manager_cancel, null)
        if (preview.conflicts.isNotEmpty()) {
            builder.setNeutralButton(R.string.touch_manager_replace_shared) { _, _ ->
                confirm(getString(R.string.touch_manager_replace_shared_question), summary) { import(value, ImportConflictResolution.REPLACE) }
            }
        }
        builder.show()
    }

    private fun importSummary(preview: TouchImportPreview): String = buildString {
        append(getString(R.string.touch_manager_import_counts, preview.bundle.templates.size, preview.bundle.presets.size))
        preview.bundle.templates.forEach { append("\n• " + getString(R.string.touch_manager_import_layout, it.name, it.controls.size, it.screens.size)) }
        preview.bundle.presets.forEach { append("\n• " + getString(R.string.touch_manager_import_preset, it.name)) }
        append("\n\n" + getString(R.string.touch_manager_import_conflicts, preview.conflicts.size))
        preview.conflicts.forEach { conflict ->
            val layout = conflict.kind == TouchObjectKind.TEMPLATE
            val name = if (layout) preview.bundle.templates.first { it.id == conflict.id }.name else preview.bundle.presets.first { it.id == conflict.id }.name
            append("\n\n" + getString(if (layout) R.string.touch_manager_conflicting_layout else R.string.touch_manager_conflicting_preset, name))
            append("\n" + if (layout) templateImpact(conflict.id) else presetImpact(conflict.id))
        }
        append("\n\n" + getString(if (preview.conflicts.isEmpty()) R.string.touch_manager_reuse_identical else R.string.touch_manager_copy_replace_hint))
    }

    private fun import(value: String, resolution: ImportConflictResolution) {
        val revision = library.revision
        background {
            val result = repository.importBundle(value, revision, resolution)
            withContext(Dispatchers.Main) {
                library = result.library
                showManager()
                val imported = library.templates.filter { it.id in result.templateIds.values }
                if (imported.isNotEmpty()) {
                    MaterialAlertDialogBuilder(this@TouchTemplateActivity).setTitle(R.string.touch_manager_import_complete)
                        .setMessage(R.string.touch_manager_import_no_association)
                        .setPositiveButton(if (gameId == null) R.string.touch_manager_open_layout else R.string.touch_manager_use_game) { _, _ ->
                            MaterialAlertDialogBuilder(this@TouchTemplateActivity).setTitle(R.string.touch_manager_choose_imported)
                                .setItems(imported.map { it.name }.toTypedArray()) { _, index ->
                                    if (gameId == null) {
                                        edit(imported[index].id)
                                    } else {
                                        persist(TouchReferences.associateGame(library, gameId!!, imported[index].id))
                                    }
                                }.setNegativeButton(R.string.touch_manager_cancel, null).show()
                        }.setNegativeButton(R.string.touch_manager_done, null).show()
                } else {
                    toast(R.string.touch_manager_presets_imported)
                }
            }
        }
    }

    private fun clearExportRecovery() {
        val file = exportRecovery ?: return
        val directory = File(filesDir, "touch/exports").canonicalFile
        if (file.canonicalFile.parentFile == directory && file.name.matches(Regex("[0-9a-f-]{36}"))) file.delete()
        exportRecovery = null
    }

    private fun background(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        val progress = MaterialAlertDialogBuilder(this@TouchTemplateActivity)
            .setTitle(R.string.touch_manager_working)
            .setView(android.widget.ProgressBar(this).apply { setPadding(dp(24), dp(24), dp(24), dp(24)) })
            .setCancelable(false)
            .create()
        progress.show()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                report(error)
            } finally {
                busy = false
                progress.dismiss()
            }
        }
    }

    private fun persist(draft: TouchLibrary): Boolean = runCatching {
        library = repository.save(draft, library.revision)
        showManager()
        true
    }.getOrElse { report(it); false }

    private fun nameDialog(title: String, initial: String, apply: (String) -> Unit) {
        val container = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = getString(R.string.touch_manager_name)
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val input = TextInputEditText(container.context).apply { setText(initial); setSingleLine(); selectAll() }
        container.addView(input)
        val dialog = MaterialAlertDialogBuilder(this@TouchTemplateActivity).setTitle(title).setView(container)
            .setPositiveButton(R.string.touch_manager_save, null).setNegativeButton(R.string.touch_manager_cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    container.error = getString(R.string.touch_manager_enter_name)
                } else { runCatching { apply(name) }.onFailure { report(it) }; dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(value: String, secondary: Boolean = false) = MaterialTextView(this).apply {
        text = value
        setTextAppearance(if (secondary) R.style.SynthwaveText_Body else com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        setTextColor(color(if (secondary) com.google.android.material.R.attr.colorOnSurfaceVariant else com.google.android.material.R.attr.colorOnSurface))
        textSize = if (secondary) 14f else 16f
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    private fun textButton(title: Int, action: () -> Unit) = MaterialButton(ContextThemeWrapper(this, R.style.TouchButton_Theme_Text), null, com.google.android.material.R.attr.materialButtonStyle).apply {
        setText(title); isAllCaps = false; setOnClickListener { action() }
    }
    private fun margins(width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int) = LinearLayout.LayoutParams(width, height).apply {
        setMargins(dp(left), dp(top), dp(right), dp(bottom))
    }
    private fun copyName(name: String) = getString(R.string.touch_manager_copy_name, name)
    private fun color(attribute: Int) = MaterialColors.getColor(this, attribute, javaClass.simpleName)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(id: Int) = Toast.makeText(this, id, Toast.LENGTH_SHORT).show()
    private fun report(error: Throwable) = toast(
        when (error) {
            is ManagerException -> error.messageId
            is TouchRevisionConflictException -> R.string.touch_manager_error_conflict
            is TouchValidationException -> R.string.touch_manager_error_validation
            is IOException -> R.string.touch_manager_error_io
            is IllegalArgumentException -> R.string.touch_manager_error_format
            else -> R.string.touch_manager_error_generic
        }
    )

    override fun onPause() { workspace?.suspendInputs(); super.onPause() }
    override fun onResume() { super.onResume(); workspace?.resumeInputs() }

    companion object {
        const val EXTRA_GAME_ID = "touch_game_id"
        private const val MAX_IMPORT_BYTES = 4_000_000
        private const val MENU_IMPORT = 1
        private const val MENU_DEFAULT = 2
        fun repository(context: Context): TouchRepository = TouchRepository(File(context.filesDir, "touch/templates.json")) { TouchDefaultLibrary.create(context) }
        fun intent(context: Context, gameId: String? = null): Intent = Intent(context, TouchTemplateActivity::class.java).putExtra(EXTRA_GAME_ID, gameId)
    }
}
