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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.utils.GameHelper
import org.yuzu.yuzu_emu.overlay.touch.model.AppearanceGroup
import org.yuzu.yuzu_emu.overlay.touch.model.AppearancePreset
import org.yuzu.yuzu_emu.overlay.touch.model.SwipePreset
import org.yuzu.yuzu_emu.overlay.touch.model.TouchDefaults
import org.yuzu.yuzu_emu.overlay.touch.model.TouchLibrary
import org.yuzu.yuzu_emu.overlay.touch.model.TouchTemplate
import org.yuzu.yuzu_emu.overlay.touch.model.newTouchId
import org.yuzu.yuzu_emu.overlay.touch.runtime.InputSink
import org.yuzu.yuzu_emu.overlay.touch.store.ImportConflictResolution
import org.yuzu.yuzu_emu.overlay.touch.store.TouchImportExport
import org.yuzu.yuzu_emu.overlay.touch.store.TouchImportPreview
import org.yuzu.yuzu_emu.overlay.touch.store.RemovedControl
import org.yuzu.yuzu_emu.overlay.touch.store.TouchSanitizer
import org.yuzu.yuzu_emu.overlay.touch.store.TouchValidator
import org.yuzu.yuzu_emu.overlay.touch.store.TouchObjectKind
import org.yuzu.yuzu_emu.overlay.touch.store.TouchReferences
import org.yuzu.yuzu_emu.overlay.touch.store.TouchRepository
import org.yuzu.yuzu_emu.overlay.touch.store.TouchRevisionConflictException
import org.yuzu.yuzu_emu.overlay.touch.store.TouchValidationException
import org.yuzu.yuzu_emu.overlay.touch.ui.TouchDraftRevisionConflict
import org.yuzu.yuzu_emu.overlay.touch.ui.TouchWorkspace
import org.yuzu.yuzu_emu.utils.ThemeHelper
import kotlin.math.roundToInt

class TouchTemplateActivity : AppCompatActivity() {
    private lateinit var repository: TouchRepository
    private lateinit var library: TouchLibrary
    private var workspace: TouchWorkspace? = null
    private var pendingRemoved: List<RemovedControl> = emptyList()
    private var exporting: String? = null
    private var exportRecovery: File? = null
    private var pendingDraftToken: String? = null
    private var selectedTab = 0
    private var searchQuery = ""
    private var renderedColumns = 1
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

    private val exportDraftFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val token = pendingDraftToken
        pendingDraftToken = null
        if (uri != null && token != null) {
            background {
                val value = TouchDraftState.read(this@TouchTemplateActivity, token)
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(value) }
                    ?: throw ManagerException(R.string.touch_manager_error_write)
                withContext(Dispatchers.Main) {
                    TouchDraftState.discardToken(this@TouchTemplateActivity, token)
                    toast(R.string.touch_manager_exported)
                }
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
        val sanitized = TouchSanitizer.sanitize(library, resources.displayMetrics.density)
        if (sanitized.removed.isNotEmpty()) {
            library = sanitized.library
            pendingRemoved = sanitized.removed
            if (TouchValidator.validate(library).isEmpty()) {
                runCatching { library = repository.save(library, library.revision) }
            }
        }
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
        showRemovedDialog()
        if (savedInstanceState?.containsKey("touch_workspace_draft") == true) {
            runCatching {
                val initial = library.templateFor(gameId) ?: library.templates.first()
                edit(initial.id)
                TouchDraftState.restore(this, savedInstanceState, workspace!!)
            }.onFailure { error ->
                showManager()
                val token = TouchDraftState.token(savedInstanceState)
                if (error is TouchDraftRevisionConflict && token != null) {
                    showDraftConflict(error, token)
                } else {
                    report(error)
                }
            }
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

    private fun showRemovedDialog() {
        val removed = pendingRemoved
        pendingRemoved = emptyList()
        if (removed.isEmpty()) return
        val details = removed.joinToString("\n") { "「${it.name}」：${it.reason}" }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.touch_workspace_removed_title)
            .setMessage(getString(R.string.touch_workspace_removed_message, details))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showManager() {
        workspace?.releaseInputs()
        workspace = null
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        val root = FrameLayout(this).apply {
            setBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
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
            addTab(newTab().setText(R.string.touch_manager_appearances), selectedTab == 2)
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
        val list = column().apply { setPadding(dp(8), dp(8), dp(8), dp(112)) }
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
            create.setText(
                when (selectedTab) {
                    0 -> R.string.touch_manager_create_layout
                    1 -> R.string.touch_manager_create_preset
                    else -> R.string.touch_manager_create_appearance
                }
            )
            val hint = when (selectedTab) {
                0 -> R.string.touch_manager_shared_layout_hint
                1 -> R.string.touch_manager_shared_preset_hint
                else -> R.string.touch_manager_shared_appearance_hint
            }
            val cards = mutableListOf<View>()
            when (selectedTab) {
                0 -> library.templates.filter { it.name.contains(searchQuery, true) }.forEach { template ->
                    cards += layoutCard(template)
                }
                1 -> library.presets.filter { it.name.contains(searchQuery, true) }.forEach { preset ->
                    cards += presetCard(preset)
                }
                else -> library.appearancePresets.filter { it.name.contains(searchQuery, true) }.forEach { preset ->
                    cards += appearanceCard(preset)
                }
            }
            addCardRows(list, cards)
            if (cards.isEmpty()) {
                list.addView(
                    label(getString(if (searchQuery.isBlank()) R.string.touch_manager_empty else R.string.touch_manager_no_matches)).apply {
                        gravity = Gravity.CENTER; setPadding(dp(24), dp(48), dp(24), dp(16))
                    }
                )
                if (searchQuery.isNotBlank()) list.addView(textButton(R.string.touch_manager_clear_search) { search.setText("") })
            }
            list.addView(label(getString(hint), true).apply { setPadding(dp(4), dp(4), dp(4), dp(16)) })
        }
        renderedColumns = columnsForWidth(resources.displayMetrics.widthPixels)
        root.addOnLayoutChangeListener { _, _, _, right, _, _, _, oldRight, _ ->
            val columns = columnsForWidth(right)
            if (columns != renderedColumns) {
                renderedColumns = columns
                render()
            }
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { selectedTab = tab.position; render() }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        search.doAfterTextChanged { searchQuery = it?.toString().orEmpty(); render() }
        render()
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun showDraftConflict(error: TouchDraftRevisionConflict, token: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.touch_draft_conflict_title)
            .setMessage(getString(R.string.touch_draft_conflict_message, error.draftRevision, error.currentRevision))
            .setPositiveButton(R.string.touch_draft_export) { _, _ ->
                pendingDraftToken = token
                exportDraftFile.launch("touch-draft-${System.currentTimeMillis()}.json")
            }
            .setNeutralButton(R.string.touch_draft_discard) { _, _ ->
                TouchDraftState.discardToken(this, token)
                toast(R.string.touch_draft_discarded)
            }
            .setNegativeButton(R.string.touch_manager_cancel, null)
            .show()
    }

    private fun columnsForWidth(widthPx: Int): Int {
        val widthDp = widthPx / resources.displayMetrics.density
        return when {
            widthDp >= 960f -> 3
            widthDp >= 600f -> 2
            else -> 1
        }
    }

    private fun addCardRows(list: LinearLayout, cards: List<View>) {
        val columns = renderedColumns.coerceAtLeast(1)
        cards.chunked(columns).forEach { rowCards ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            rowCards.forEach { card ->
                card.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                    bottomMargin = dp(16)
                }
                row.addView(card)
            }
            repeat(columns - rowCards.size) {
                row.addView(
                    android.widget.Space(this),
                    LinearLayout.LayoutParams(0, -2, 1f).apply {
                        marginStart = dp(4)
                        marginEnd = dp(4)
                    }
                )
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun appearanceCard(preset: AppearancePreset): MaterialCardView = objectCard(
        preset.name,
        getString(R.string.touch_manager_appearance_metadata, preset.groups.size),
        { editAppearancePreset(preset) },
        { anchor -> appearanceMenu(preset, anchor) }
    ) { tags ->
        val refs = TouchReferences.appearancePreset(library, preset.id)
        tags.addView(
            tag(getString(R.string.touch_manager_appearance_references, refs.size)) {
                showDetails(
                    getString(R.string.touch_manager_appearance_using, preset.name),
                    refs.joinToString("\n") { reference ->
                        val template = library.templates.firstOrNull { it.id == reference.templateId }
                        val control = template?.controls?.firstOrNull { it.id == reference.controlId }
                        "${template?.name ?: reference.templateId} / ${control?.name?.ifBlank { control.id } ?: reference.controlId}"
                    }
                )
            }
        )
    }

    private fun appearanceMenu(preset: AppearancePreset, anchor: View) = overflow(
        anchor,
        listOf(
            R.string.touch_manager_appearance_using to {
                showDetails(
                    getString(R.string.touch_manager_appearance_using, preset.name),
                    TouchReferences.appearancePreset(library, preset.id).size.toString()
                )
            },
            R.string.touch_manager_rename to {
                nameDialog(getString(R.string.touch_manager_rename_appearance), preset.name) { name ->
                    if (name != preset.name) {
                        persist(library.copy(appearancePresets = library.appearancePresets.map {
                            if (it.id == preset.id) it.copy(name = name) else it
                        }))
                    }
                }
            },
            R.string.touch_manager_duplicate to {
                persist(library.copy(appearancePresets = library.appearancePresets + preset.copy(id = newTouchId(), name = copyName(preset.name))))
            },
            R.string.touch_manager_export to {
                exporting = repository.exportAppearancePreset(preset.id)
                exportFile.launch("${preset.name}.json")
            },
            R.string.touch_manager_delete to {
                val refs = TouchReferences.appearancePreset(library, preset.id)
                val message = if (refs.isEmpty()) {
                    getString(R.string.touch_manager_no_appearance_references)
                } else {
                    refs.joinToString("\n") { "${it.templateId} / ${it.controlId}" }
                }
                confirm(getString(R.string.touch_manager_delete_named, preset.name), message) {
                    persist(TouchReferences.deleteAppearancePreset(library, preset.id))
                }
            }
        )
    )

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
            buttons.addView(
                MaterialButton(
                    ContextThemeWrapper(this, R.style.TouchButton_Theme_Secondary),
                    null,
                    com.google.android.material.R.attr.materialButtonStyle
                ).apply {
                    setText(R.string.touch_manager_use_game)
                    isAllCaps = false
                    setOnClickListener { applyToGame() }
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) }
            )
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
        setEnsureMinTouchTargetSize(false)
        chipMinHeight = dp(36).toFloat()
        gravity = Gravity.CENTER
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
                confirm(getString(R.string.touch_manager_apply_named, template.name), getString(R.string.touch_manager_apply_message, gameName(gameId!!))) {
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
            MaterialAlertDialogBuilder(this@TouchTemplateActivity)
                .setTitle(R.string.touch_manager_create_layout)
                .setItems(
                    arrayOf(
                        getString(R.string.touch_manager_blank_layout),
                        getString(R.string.touch_manager_default_layout)
                    )
                ) { _, which ->
                    nameDialog(getString(R.string.touch_manager_create_layout), getString(R.string.touch_manager_new_layout)) { name ->
                        val builtin = TouchDefaults.library()
                        val template = if (which == 0) {
                            TouchTemplate(name = name)
                        } else {
                            TouchReferences.copyTemplate(builtin.templates.first(), name)
                        }
                        val referenced = builtin.templates.first().controls.mapNotNull { it.swipePresetId }.toSet()
                        val presets = library.presets + builtin.presets.filter { preset ->
                            preset.id in referenced && library.presets.none { it.id == preset.id }
                        }
                        if (
                            persist(
                                library.copy(
                                    templates = library.templates + template,
                                    presets = presets,
                                    defaultTemplateId = library.defaultTemplateId ?: template.id
                                )
                            )
                        ) {
                            edit(template.id)
                        }
                    }
                }.show()
        } else if (selectedTab == 1) {
            nameDialog(getString(R.string.touch_manager_create_preset), getString(R.string.touch_manager_new_preset)) { name ->
                val preset = SwipePreset(name = name)
                if (persist(library.copy(presets = library.presets + preset))) editPreset(preset.id)
            }
        } else {
            nameDialog(getString(R.string.touch_manager_create_appearance), getString(R.string.touch_manager_new_appearance)) { name ->
                val preset = AppearancePreset(name = name)
                if (persist(library.copy(appearancePresets = library.appearancePresets + preset))) {
                    editAppearancePreset(preset)
                }
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
            refs.gameIds.forEach { append("\n• ${gameName(it)}") }
        }
    }

    private fun gameName(gameId: String): String {
        GameHelper.cachedGameList.firstOrNull { TouchGameOverlay.gameId(it.programId) == gameId }
            ?.title?.takeIf { it.isNotBlank() }?.let { return it }
        val cached = PreferenceManager.getDefaultSharedPreferences(this)
            .getStringSet(GameHelper.KEY_GAMES, emptySet())
            .orEmpty()
        cached.forEach { raw ->
            runCatching { Json.decodeFromString<Game>(raw) }
                .getOrNull()
                ?.takeIf { TouchGameOverlay.gameId(it.programId) == gameId }
                ?.title?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return gameId
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

    private fun appearanceImpact(id: String): String = buildString {
        val refs = TouchReferences.appearancePreset(library, id)
        append(getString(R.string.touch_manager_appearance_references, refs.size))
        refs.forEach { reference ->
            val template = library.templates.firstOrNull { it.id == reference.templateId }
            val control = template?.controls?.firstOrNull { it.id == reference.controlId }
            append("\n• ${template?.name ?: reference.templateId} / ${control?.name?.ifBlank { control.id } ?: reference.controlId}")
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

    private fun appearanceButton(text: String, action: () -> Unit) =
        MaterialButton(ContextThemeWrapper(this, R.style.TouchButton_Theme_Secondary), null, com.google.android.material.R.attr.materialButtonStyle).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun editAppearancePreset(preset: AppearancePreset) {
        var groups = preset.groups.ifEmpty { listOf(AppearanceGroup()) }
        var dismissCurrent: (() -> Unit)? = null
        fun reopen() {
            dismissCurrent?.invoke()
            editAppearancePreset(preset.copy(groups = groups))
        }
        fun editColor(index: Int) {
            val group = groups[index]
            val input = TextInputEditText(this).apply {
                hint = getString(R.string.touch_manager_appearance_color_prompt)
                setText(group.color?.let { String.format(java.util.Locale.ROOT, "%08X", it) }.orEmpty())
                setSingleLine()
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.touch_manager_appearance_color)
                .setView(input)
                .setPositiveButton(R.string.touch_manager_save) { _, _ ->
                    val text = input.text?.toString()?.trim()?.removePrefix("#").orEmpty()
                    val normalized = if (text.length == 6) "FF$text" else text
                    val color = if (normalized.length == 8) normalized.toLongOrNull(16)?.toInt() else null
                    groups = groups.toMutableList().also { it[index] = group.copy(color = color) }
                    reopen()
                }
                .setNeutralButton(R.string.touch_manager_appearance_clear_color) { _, _ ->
                    groups = groups.toMutableList().also { it[index] = group.copy(color = null) }
                    reopen()
                }
                .setNegativeButton(R.string.touch_manager_cancel, null)
                .show()
        }
        fun editOverlay(index: Int) {
            val group = groups[index]
            val input = TextInputEditText(this).apply {
                hint = getString(R.string.touch_manager_appearance_overlay_prompt)
                setText(group.overlayOpacity?.let { (it * 100f).roundToInt().toString() }.orEmpty())
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.touch_manager_appearance_overlay)
                .setView(input)
                .setPositiveButton(R.string.touch_manager_save) { _, _ ->
                    val value = input.text?.toString()?.toFloatOrNull()?.div(100f)
                    groups = groups.toMutableList().also { it[index] = group.copy(overlayOpacity = value?.coerceIn(0f, 3f)) }
                    reopen()
                }
                .setNeutralButton(R.string.touch_manager_appearance_clear_overlay) { _, _ ->
                    groups = groups.toMutableList().also { it[index] = group.copy(overlayOpacity = null) }
                    reopen()
                }
                .setNegativeButton(R.string.touch_manager_cancel, null)
                .show()
        }
        val content = column().apply { setPadding(dp(20), dp(12), dp(20), dp(8)) }
        content.addView(label(getString(R.string.touch_manager_appearance_original)))
        for (index in 1 until groups.size) {
            val group = groups[index]
            content.addView(label(getString(R.string.touch_manager_appearance_group, index + 1)))
            content.addView(
                appearanceButton(
                    getString(
                        R.string.touch_manager_appearance_color,
                        group.color?.let { String.format(java.util.Locale.ROOT, "#%08X", it) }
                            ?: getString(R.string.touch_manager_appearance_unset)
                    )
                ) { editColor(index) }
            )
            content.addView(
                appearanceButton(
                    getString(
                        R.string.touch_manager_appearance_overlay,
                        group.overlayOpacity?.let { "${(it * 100f).roundToInt()}%" }
                            ?: getString(R.string.touch_manager_appearance_unset)
                    )
                ) { editOverlay(index) }
            )
            content.addView(appearanceButton(getString(R.string.touch_manager_appearance_delete_group)) {
                groups = groups.filterIndexed { current, _ -> current != index }
                if (groups.isEmpty()) groups = listOf(AppearanceGroup())
                reopen()
            })
        }
        content.addView(appearanceButton(getString(R.string.touch_manager_appearance_add_group)) {
            val template = library.templateFor(gameId) ?: library.templates.firstOrNull()
            groups = groups + AppearanceGroup(template?.globalColor, template?.globalOpacity)
            reopen()
        })
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.touch_manager_appearance_editor, preset.name))
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(R.string.touch_manager_save) { _, _ ->
                persist(
                    library.copy(
                        appearancePresets = library.appearancePresets.map {
                            if (it.id == preset.id) it.copy(groups = groups) else it
                        }
                    )
                )
            }
            .setNegativeButton(R.string.touch_manager_cancel, null)
            .create()
        dialog.show()
        dismissCurrent = { dialog.dismiss() }
    }

    private fun editPreset(presetId: String) {
        val owner = library.templates.firstOrNull { template -> template.controls.any { it.swipePresetId == presetId } }
            ?: library.templates.first()
        edit(owner.id)
        workspace?.editPreset(presetId, standalone = true)
    }

    private fun edit(templateId: String) {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
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
            val name = when (conflict.kind) {
                TouchObjectKind.TEMPLATE -> preview.bundle.templates.first { it.id == conflict.id }.name
                TouchObjectKind.PRESET -> preview.bundle.presets.first { it.id == conflict.id }.name
                TouchObjectKind.APPEARANCE -> preview.bundle.appearancePresets.first { it.id == conflict.id }.name
            }
            append("\n\n" + getString(if (layout) R.string.touch_manager_conflicting_layout else R.string.touch_manager_conflicting_preset, name))
            append(
                "\n" + when (conflict.kind) {
                    TouchObjectKind.TEMPLATE -> templateImpact(conflict.id)
                    TouchObjectKind.PRESET -> presetImpact(conflict.id)
                    TouchObjectKind.APPEARANCE -> appearanceImpact(conflict.id)
                }
            )
        }
        append("\n\n" + getString(if (preview.conflicts.isEmpty()) R.string.touch_manager_reuse_identical else R.string.touch_manager_copy_replace_hint))
    }

    private fun import(value: String, resolution: ImportConflictResolution) {
        val revision = library.revision
        background {
            val result = repository.importBundle(value, revision, resolution)
            withContext(Dispatchers.Main) {
                val sanitized = TouchSanitizer.sanitize(result.library, resources.displayMetrics.density)
                library = sanitized.library
                if (sanitized.removed.isNotEmpty()) {
                    pendingRemoved = sanitized.removed
                    runCatching { library = repository.save(library, library.revision) }.onFailure { report(it) }
                }
                showManager()
                showRemovedDialog()
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
        fun repository(context: Context): TouchRepository {
            val appContext = context.applicationContext
            val target = TouchStorage.file(appContext)
            val file = if (TouchStorage.migrate(appContext)) {
                target
            } else {
                TouchStorage.legacyFile(appContext)
            }
            return TouchRepository(file) { TouchDefaultLibrary.create(appContext) }
        }
        fun intent(context: Context, gameId: String? = null): Intent = Intent(context, TouchTemplateActivity::class.java).putExtra(EXTRA_GAME_ID, gameId)
    }
}
