package com.nikac.nikstore

import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.nikac.nikstore.catalog.AppState
import com.nikac.nikstore.catalog.CatalogApp
import com.nikac.nikstore.catalog.CatalogRepository
import com.nikac.nikstore.catalog.CatalogUnavailableException
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val expandedApps = mutableSetOf<String>()
    private lateinit var repository: CatalogRepository
    private lateinit var cards: LinearLayout
    private lateinit var status: TextView
    private lateinit var count: TextView
    private lateinit var emptyState: View
    private lateinit var refreshButton: MaterialButton
    private lateinit var retryButton: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private var refreshing = false
    private var apps: List<CatalogApp>? = null
    private var loadError: Int? = null
    private val periodicRefresh = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getStringArrayList(EXPANDED_APPS)?.let(expandedApps::addAll)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.catalog_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val lightTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = lightTheme
            isAppearanceLightNavigationBars = lightTheme
        }
        repository = CatalogRepository(applicationContext, BuildConfig.CATALOG_URL)
        cards = findViewById(R.id.app_cards)
        status = findViewById(R.id.catalog_status)
        count = findViewById(R.id.app_count)
        emptyState = findViewById(R.id.catalog_empty_state)
        refreshButton = findViewById(R.id.refresh_button)
        retryButton = findViewById(R.id.retry_button)
        progress = findViewById(R.id.catalog_progress)
        refreshButton.setOnClickListener { refresh(userInitiated = true) }
        retryButton.setOnClickListener { refresh(userInitiated = true) }
        TooltipCompat.setTooltipText(refreshButton, getString(R.string.refresh))
        ViewCompat.setAccessibilityHeading(findViewById(R.id.collection_heading), true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(EXPANDED_APPS, ArrayList(expandedApps))
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        renderApps()
        refresh()
        handler.postDelayed(periodicRefresh, REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(periodicRefresh)
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun refresh(userInitiated: Boolean = false) {
        if (refreshing) return
        refreshing = true
        refreshButton.isEnabled = false
        retryButton.isEnabled = false
        // Automatic checks leave the collection and its status quiet while browsing.
        if (userInitiated || apps == null) {
            progress.visibility = View.VISIBLE
            showStatus(R.string.catalog_refreshing)
            if (apps == null) {
                loadError = null
                renderEmptyState()
            }
        }
        val restoreCached = apps == null
        worker.execute {
            if (restoreCached) {
                repository.cached()?.let { saved ->
                    runOnUiThread {
                        if (!isDestroyed) {
                            apps = saved
                            renderApps()
                            showStatus(R.string.catalog_saved)
                        }
                    }
                }
            }
            val result = runCatching { repository.refresh() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                refreshing = false
                refreshButton.isEnabled = true
                retryButton.isEnabled = true
                progress.visibility = View.INVISIBLE
                result.fold(
                    onSuccess = { loaded ->
                        loadError = null
                        val changed = apps != loaded
                        apps = loaded
                        if (changed) renderApps() else renderEmptyState()
                        showStatus(R.string.catalog_current, R.drawable.ic_check)
                    },
                    onFailure = { error ->
                        loadError = when {
                            apps != null -> R.string.catalog_offline
                            error is CatalogUnavailableException -> R.string.catalog_not_published
                            else -> R.string.catalog_error
                        }
                        showStatus(loadError!!, R.drawable.ic_info, error = true)
                        renderEmptyState()
                    },
                )
            }
        }
    }

    private fun showStatus(message: Int, icon: Int = 0, error: Boolean = false) {
        val text = getString(message)
        if (status.text.toString() != text) status.text = text
        val color = ContextCompat.getColor(this, if (error) R.color.store_error else R.color.store_text_secondary)
        status.setTextColor(color)
        val drawable = if (icon == 0) null else AppCompatResources.getDrawable(this, icon)?.mutate()?.also {
            DrawableCompat.setTint(it, color)
        }
        status.setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
        retryButton.visibility = if (error) View.VISIBLE else View.GONE
    }

    private fun renderEmptyState() {
        emptyState.visibility = if (apps.isNullOrEmpty()) View.VISIBLE else View.GONE
        val failed = apps == null && loadError != null
        findViewById<TextView>(R.id.empty_title).setText(when {
            failed -> R.string.catalog_error_title
            apps == null -> R.string.catalog_loading_title
            else -> R.string.catalog_empty_title
        })
        findViewById<TextView>(R.id.empty_description).setText(when {
            failed -> loadError!!
            apps == null -> R.string.catalog_loading
            else -> R.string.catalog_empty
        })
    }

    private fun renderApps() {
        cards.removeAllViews()
        count.visibility = if (apps == null) View.GONE else View.VISIBLE
        val appCount = apps.orEmpty().size
        count.text = resources.getQuantityString(R.plurals.catalog_app_count, appCount, appCount)
        renderEmptyState()
        apps.orEmpty().forEach { app ->
            val card = layoutInflater.inflate(R.layout.item_catalog_app, cards, false) as MaterialCardView
            card.findViewById<TextView>(R.id.card_name).apply {
                text = app.name
                ViewCompat.setAccessibilityHeading(this, true)
            }
            card.findViewById<TextView>(R.id.card_description).text = app.description
            card.findViewById<TextView>(R.id.card_version).text = getString(R.string.available_version, app.versionName)
            card.findViewById<TextView>(R.id.card_build).text = getString(
                R.string.release_build, app.versionName, app.versionCode,
            )
            val installed = try {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(app.packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            val installedCode = installed?.let { PackageInfoCompat.getLongVersionCode(it) }
            card.findViewById<TextView>(R.id.card_installed).text = if (installed == null) {
                getString(R.string.app_not_installed)
            } else {
                getString(R.string.installed_version, installed.versionName ?: installedCode.toString(), installedCode)
            }
            val icon = if (installed == null) null else try {
                packageManager.getApplicationIcon(app.packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            card.findViewById<ImageView>(R.id.card_icon).apply {
                setImageDrawable(icon)
                visibility = if (icon == null) View.GONE else View.VISIBLE
            }
            card.findViewById<TextView>(R.id.card_initial).apply {
                text = String(Character.toChars(app.name.trim().codePointAt(0))).uppercase(Locale.getDefault())
                visibility = if (icon == null) View.VISIBLE else View.GONE
            }
            bindAppState(card.findViewById(R.id.card_state), app.state(installedCode, Build.VERSION.SDK_INT))
            val versions = resources.getStringArray(R.array.android_version_names)
            val androidVersion = versions.getOrNull(app.minSdk - Build.VERSION_CODES.N)
                ?: getString(R.string.android_api_version, app.minSdk)
            card.findViewById<TextView>(R.id.card_requirement).text = getString(R.string.android_requirement, androidVersion)
            card.findViewById<TextView>(R.id.card_notes).apply {
                text = app.releaseNotes
                visibility = if (app.releaseNotes.isBlank()) View.GONE else View.VISIBLE
            }
            card.findViewById<TextView>(R.id.card_notes_heading).visibility =
                if (app.releaseNotes.isBlank()) View.GONE else View.VISIBLE
            val details = card.findViewById<View>(R.id.card_details)
            val detailsButton = card.findViewById<MaterialButton>(R.id.card_details_button)
            fun updateDetails() {
                val expanded = app.packageName in expandedApps
                details.visibility = if (expanded) View.VISIBLE else View.GONE
                detailsButton.setText(if (expanded) R.string.hide_details else R.string.release_details)
                detailsButton.setIconResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
                detailsButton.contentDescription = getString(
                    if (expanded) R.string.hide_details_for else R.string.show_details_for, app.name,
                )
                ViewCompat.setStateDescription(detailsButton, getString(
                    if (expanded) R.string.details_expanded else R.string.details_collapsed,
                ))
            }
            detailsButton.setOnClickListener {
                if (!expandedApps.add(app.packageName)) expandedApps.remove(app.packageName)
                updateDetails()
            }
            updateDetails()
            cards.addView(card)
        }
    }

    private fun bindAppState(view: TextView, state: AppState) {
        val (label, foreground, background) = when (state) {
            AppState.NOT_INSTALLED -> Triple(R.string.app_available, R.color.store_primary, R.color.store_primary_container)
            AppState.UPDATE_AVAILABLE -> Triple(R.string.app_update_available, R.color.store_update, R.color.store_update_container)
            AppState.UP_TO_DATE -> Triple(R.string.app_up_to_date, R.color.store_success, R.color.store_success_container)
            AppState.NEWER_INSTALLED -> Triple(R.string.app_newer_installed, R.color.store_neutral, R.color.store_neutral_container)
            AppState.INCOMPATIBLE -> Triple(R.string.app_incompatible, R.color.store_error, R.color.store_error_container)
        }
        view.setText(label)
        view.setTextColor(ContextCompat.getColor(this, foreground))
        view.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, background))
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 60_000L
        const val EXPANDED_APPS = "expanded_apps"
    }
}
