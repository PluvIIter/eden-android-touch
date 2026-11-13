// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.touch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import org.yuzu.yuzu_emu.overlay.touch.model.StickTarget
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
import org.yuzu.yuzu_emu.overlay.touch.ui.TouchWorkspace
import org.yuzu.yuzu_emu.utils.ThemeHelper
import java.io.ByteArrayOutputStream
import java.io.File

class TouchTemplateActivity : AppCompatActivity() {
    private lateinit var repository: TouchRepository
    private lateinit var library: TouchLibrary
    private var workspace: TouchWorkspace? = null
    private var exporting: String? = null
    private var selectedTab = 0
    private var searchQuery = ""
    private val gameId get() = intent.getStringExtra(EXTRA_GAME_ID)?.let { TouchReferences.baseGameId(it) }

    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val value = contentResolver.openInputStream(uri)?.use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_IMPORT_BYTES) { "Template file exceeds 4 MB" }
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                } ?: error("Cannot read template")
                showImportPreview(value)
            }.onFailure { report(it) }
        }
    }

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val value = exporting
        exporting = null
        if (uri != null && value != null) {
            runCatching {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(value) }
                    ?: error("Cannot write template")
                toast("Exported")
            }.onFailure { report(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.setTheme(this)
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        repository = repository(this)
        runCatching { library = repository.load() }
            .onFailure { report(it); finish(); return }
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
            if (file.isFile) exporting = file.readText(Charsets.UTF_8)
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
                val token = java.util.UUID.randomUUID().toString()
                File(directory, token).writeText(value, Charsets.UTF_8)
                outState.putString("touch_export_token", token)
            }
        }.onFailure { report(it) }
        super.onSaveInstanceState(outState)
    }

    private fun showManager() {
        workspace?.releaseInputs()
        workspace = null
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        setContentView(ScrollView(this).apply { addView(column) })
        column.addView(MaterialTextView(this).apply { text = "Touch templates"; textSize = 24f })
        val tabs = LinearLayout(this)
        tabs.addView(button("Layouts") { selectedTab = 0; showManager() })
        tabs.addView(button("Camera parameters") { selectedTab = 1; showManager() })
        column.addView(tabs)
        val search = EditText(this).apply {
            hint = "Search by name"
            setSingleLine()
            setText(searchQuery)
        }
        column.addView(search)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(results)
        fun renderResults() {
            results.removeAllViews()
            if (selectedTab == 0) {
                val matches = library.templates.filter { it.name.contains(searchQuery, ignoreCase = true) }
                matches.forEach { template ->
                    val used = TouchReferences.template(library, template.id).gameIds.size
                    val defaults = if (library.defaultTemplateId == template.id) " · Default" else ""
                    results.addView(button("${template.name}$defaults\n${template.controls.size} controls · ${template.screens.size} screens · $used games") { templateMenu(template) })
                }
                if (matches.isEmpty()) results.addView(MaterialTextView(this).apply { text = "No layouts match. Clear search or create a layout." })
            } else {
                val matches = library.presets.filter { it.name.contains(searchQuery, ignoreCase = true) }
                matches.forEach { preset ->
                    val count = TouchReferences.preset(library, preset.id).size
                    results.addView(button("${preset.name}\n$count areas · X ${preset.sensitivityX} / Y ${preset.sensitivityY}") { presetMenu(preset) })
                }
                if (matches.isEmpty()) results.addView(MaterialTextView(this).apply { text = "No parameter templates match. Clear search or create parameters." })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                renderResults()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        renderResults()
        column.addView(button("Clear search") { search.setText("") })
        if (gameId != null) {
            column.addView(
                button("Follow global default for this game") {
                    confirm("Change game layout?", "This game will follow the global default layout.") {
                        persist(TouchReferences.associateGame(library, gameId!!, null))
                    }
                }
            )
        }
        if (selectedTab == 0) {
            column.addView(
                button("Create layout") {
                    nameDialog("Create layout", "New layout") { name ->
                        val template = TouchTemplate(name = name)
                        if (persist(
                                library.copy(
                                        templates = library.templates + template,
                                        defaultTemplateId = library.defaultTemplateId ?: template.id
                                    )
                            )
                        ) {
                            edit(template.id)
                        }
                    }
                }
            )
        } else {
            column.addView(
                button("Create camera parameters") {
                    nameDialog("Create parameters", "Camera") { name ->
                        val preset = SwipePreset(name = name)
                        if (persist(library.copy(presets = library.presets + preset))) editPreset(preset.id)
                    }
                }
            )
        }
        column.addView(button("Import templates") { importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
    }

    private fun templateMenu(template: TouchTemplate) {
        val options = mutableListOf("Edit offline", "View game references", "Rename", "Duplicate", "Use as default", "Export", "Delete")
        if (gameId != null) options.addAll(listOf("Use for this game", "Copy and use for this game"))
        MaterialAlertDialogBuilder(this).setTitle(template.name).setItems(options.toTypedArray()) { _, which ->
            runCatching {
                when (which) {
                    0 -> edit(template.id)
                    1 -> showDetails("Games using ${template.name}", templateImpact(template.id))
                    2 -> nameDialog("Rename layout", template.name) { name ->
                        if (name != template.name) {
                            confirm("Rename shared layout?", templateImpact(template.id)) {
                                persist(library.copy(templates = library.templates.map { if (it.id == template.id) it.copy(name = name) else it }))
                            }
                        }
                    }
                    3 -> persist(library.copy(templates = library.templates + TouchReferences.copyTemplate(template)))
                    4 -> confirm("Change global default?", "Games without an explicit layout association will use ${template.name}.") {
                        persist(library.copy(defaultTemplateId = template.id))
                    }
                    5 -> { exporting = repository.exportTemplate(template.id); exportFile.launch("${template.name}.json") }
                    6 -> deleteTemplate(template)
                    7 -> confirm("Use ${template.name}?", "Apply this layout to game $gameId. Other games are unchanged.") {
                        persist(TouchReferences.associateGame(library, gameId!!, template.id))
                    }
                    8 -> nameDialog("Copy for this game", "${template.name} copy") { name ->
                        val duplicate = TouchReferences.copyTemplate(template, name)
                        persist(TouchReferences.associateGame(library.copy(templates = library.templates + duplicate), gameId!!, duplicate.id))
                    }
                    else -> Unit
                }
            }.onFailure { report(it) }
        }.show()
    }

    private fun presetMenu(preset: SwipePreset) {
        MaterialAlertDialogBuilder(this).setTitle(preset.name)
            .setItems(arrayOf("Edit parameters", "View area references", "Rename", "Duplicate", "Export", "Delete")) { _, which ->
                runCatching {
                    when (which) {
                        0 -> editPreset(preset.id)
                        1 -> showDetails("Areas using ${preset.name}", presetImpact(preset.id))
                        2 -> nameDialog("Rename parameters", preset.name) { name ->
                            if (name != preset.name) {
                                confirm("Rename shared parameters?", presetImpact(preset.id)) {
                                    persist(library.copy(presets = library.presets.map { if (it.id == preset.id) it.copy(name = name) else it }))
                                }
                            }
                        }
                        3 -> persist(library.copy(presets = library.presets + preset.copy(id = newTouchId(), name = "${preset.name} copy")))
                        4 -> { exporting = repository.exportPreset(preset.id); exportFile.launch("${preset.name}.json") }
                        5 -> deletePreset(preset)
                        else -> Unit
                    }
                }.onFailure { report(it) }
            }.show()
    }

    private fun deleteTemplate(template: TouchTemplate) {
        val replacements = library.templates.filterNot { it.id == template.id }
        if (replacements.isEmpty()) { toast("Keep at least one layout"); return }
        val choices = arrayOf("Unlink games; clear default if selected") + replacements.map { "Replace with ${it.name}" }
        MaterialAlertDialogBuilder(this).setTitle("Delete ${template.name}")
            .setItems(choices) { _, index ->
                val replacement = if (index == 0) null else replacements[index - 1].id
                confirm("Delete layout?", templateImpact(template.id) + "\n\n" + choices[index]) {
                    persist(TouchReferences.deleteTemplate(library, template.id, replacement))
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun deletePreset(preset: SwipePreset) {
        val references = TouchReferences.preset(library, preset.id)
        val replacements = library.presets.filterNot { it.id == preset.id }
        if (references.isEmpty()) {
            confirm("Delete ${preset.name}?", "This parameter template has no area references.") {
                persist(TouchReferences.deletePreset(library, preset.id))
            }
            return
        }
        val choices = replacements.map { "Replace with ${it.name}" } + "Delete all referencing swipe areas"
        MaterialAlertDialogBuilder(this).setTitle("Replace references before deleting")
            .setItems(choices.toTypedArray()) { _, index ->
                val replacement = replacements.getOrNull(index)?.id
                confirm("Delete shared parameters?", presetImpact(preset.id) + "\n\n" + choices[index]) {
                    persist(TouchReferences.deletePreset(library, preset.id, replacement, deleteReferencedControls = replacement == null))
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun templateImpact(id: String): String {
        val references = TouchReferences.template(library, id)
        return buildString {
            append("${references.gameIds.size} explicit game associations")
            if (references.isDefault) append("\nGlobal default: also affects games without an explicit association")
            references.gameIds.forEach { append("\n• $it") }
        }
    }

    private fun presetImpact(id: String): String {
        val references = TouchReferences.preset(library, id)
        return buildString {
            append("${references.size} referenced swipe areas")
            references.forEach { reference ->
                val template = library.templates.first { it.id == reference.templateId }
                val control = template.controls.first { it.id == reference.controlId }
                append("\n• ${template.name} / ${control.name.ifBlank { control.id }}")
            }
        }
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message)
            .setPositiveButton("Confirm") { _, _ -> runCatching(action).onFailure { report(it) } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showDetails(title: String, message: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setPositiveButton("Close", null).show()
    }

    private fun editPreset(presetId: String) {
        edit(library.templates.first().id)
        workspace?.editPreset(presetId)
    }

    private fun edit(templateId: String) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        workspace = TouchWorkspace(this).apply {
            this.gameId = this@TouchTemplateActivity.gameId
            bind(
                library,
                templateId,
                false,
                object : InputSink {
                    override fun buttons(pressed: Set<Int>) {}
                    override fun stick(target: StickTarget, x: Float, y: Float) {}
                    override fun touchDown(pointerId: Int, x: Float, y: Float) {}
                    override fun touchMove(pointerId: Int, x: Float, y: Float) {}
                    override fun touchUp(pointerId: Int) {}
                }
            )
            onSave = { draft, _ ->
                runCatching {
                    library = repository.save(draft, draftBaselineRevision)
                    workspace?.acceptSaved(library)
                    toast("Saved")
                }.onFailure { report(it); throw it }
            }
            onExit = { showManager() }
            setEditing(true)
        }
        setContentView(workspace!!)
    }

    private fun showImportPreview(value: String) {
        library = repository.load()
        val preview = TouchImportExport.preview(library, value)
        val summary = importSummary(preview)
        val builder = MaterialAlertDialogBuilder(this).setTitle("Import preview")
            .setMessage(summary + "\n\nGame associations will not be imported or changed.")
            .setPositiveButton(if (preview.conflicts.isEmpty()) "Import" else "Import copies") { _, _ -> import(value, ImportConflictResolution.COPY) }
            .setNegativeButton("Cancel", null)
        if (preview.conflicts.isNotEmpty()) {
            builder.setNeutralButton("Replace shared") { _, _ ->
                confirm("Replace shared objects?", summary) { import(value, ImportConflictResolution.REPLACE) }
            }
        }
        builder.show()
    }

    private fun importSummary(preview: TouchImportPreview): String = buildString {
        append("${preview.bundle.templates.size} layouts · ${preview.bundle.presets.size} parameter templates")
        preview.bundle.templates.forEach { append("\n• ${it.name}: ${it.controls.size} controls, ${it.screens.size} screens") }
        preview.bundle.presets.forEach { append("\n• Parameters: ${it.name}") }
        append("\n\n${preview.conflicts.size} conflicting IDs")
        preview.conflicts.forEach { conflict ->
            val name = if (conflict.kind == TouchObjectKind.TEMPLATE) {
                preview.bundle.templates.first { it.id == conflict.id }.name
            } else {
                preview.bundle.presets.first { it.id == conflict.id }.name
            }
            append("\n\n$name (${conflict.kind.name.lowercase()})")
            append("\n" + if (conflict.kind == TouchObjectKind.TEMPLATE) templateImpact(conflict.id) else presetImpact(conflict.id))
        }
        if (preview.conflicts.isEmpty()) {
            append("\nMatching IDs with identical content will be reused.")
        } else {
            append("\n\nCopies preserve existing shared objects; replace synchronizes all references listed above.")
        }
    }

    private fun import(value: String, resolution: ImportConflictResolution) {
        runCatching {
            val result = repository.importBundle(value, library.revision, resolution)
            library = result.library
            showManager()
            val imported = library.templates.filter { it.id in result.templateIds.values }
            if (imported.isNotEmpty()) {
                MaterialAlertDialogBuilder(this).setTitle("Import complete")
                    .setMessage("Layouts were imported without changing game associations.")
                    .setPositiveButton(if (gameId == null) "Open layout" else "Use for this game") { _, _ ->
                        MaterialAlertDialogBuilder(this).setTitle("Choose imported layout")
                            .setItems(imported.map { it.name }.toTypedArray()) { _, index ->
                                if (gameId == null) {
                                    edit(imported[index].id)
                                } else {
                                    persist(TouchReferences.associateGame(library, gameId!!, imported[index].id))
                                }
                            }.setNegativeButton("Cancel", null).show()
                    }.setNegativeButton("Done", null).show()
            } else {
                toast("Parameter templates imported")
            }
        }.onFailure { report(it) }
    }

    private fun persist(draft: TouchLibrary): Boolean = runCatching {
        library = repository.save(draft, library.revision)
        showManager()
        true
    }.getOrElse { report(it); false }

    private fun nameDialog(title: String, initial: String, apply: (String) -> Unit) {
        val input = EditText(this).apply { setText(initial); setSingleLine() }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(title).setView(input)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank()) input.error = "Enter a name" else { apply(name); dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun button(text: String, click: () -> Unit): MaterialButton = MaterialButton(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { click() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    private fun report(error: Throwable) = toast(error.message ?: "Cannot update touch templates")

    override fun onPause() {
        workspace?.suspendInputs()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        workspace?.resumeInputs()
    }

    companion object {
        const val EXTRA_GAME_ID = "touch_game_id"
        private const val MAX_IMPORT_BYTES = 4_000_000
        fun repository(context: Context): TouchRepository = TouchRepository(File(context.filesDir, "touch/templates.json"))
        fun intent(context: Context, gameId: String? = null): Intent = Intent(context, TouchTemplateActivity::class.java)
            .putExtra(EXTRA_GAME_ID, gameId)
    }
}
