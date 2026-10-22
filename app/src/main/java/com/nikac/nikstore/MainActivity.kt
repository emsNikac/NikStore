package com.nikac.nikstore

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
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
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.nikac.nikstore.catalog.AppState
import com.nikac.nikstore.catalog.CatalogApp
import com.nikac.nikstore.catalog.CatalogRepository
import com.nikac.nikstore.catalog.CatalogUnavailableException
import com.nikac.nikstore.updates.DailyReleaseScheduler
import com.nikac.nikstore.updates.ReleaseNotifications
import com.nikac.nikstore.updates.ReleaseSettings
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val expandedApps = mutableSetOf<String>()
    private lateinit var repository: CatalogRepository
    private lateinit var releaseSettings: ReleaseSettings
    private lateinit var cards: LinearLayout
    private lateinit var status: TextView
    private lateinit var count: TextView
    private lateinit var emptyState: View
    private lateinit var refreshButton: MaterialButton
    private lateinit var retryButton: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private var refreshing = false
    private var cacheLoaded = false
    private var apps: List<CatalogApp>? = null
    private var loadError: Int? = null
    private val catalogChanges = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ReleaseSettings.LAST_CATALOG_CHECK) {
            runOnUiThread { if (!refreshing && !isDestroyed) loadCachedCatalog() }
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
        releaseSettings = ReleaseSettings(this)
        cards = findViewById(R.id.app_cards)
        status = findViewById(R.id.catalog_status)
        count = findViewById(R.id.app_count)
        emptyState = findViewById(R.id.catalog_empty_state)
        refreshButton = findViewById(R.id.refresh_button)
        retryButton = findViewById(R.id.retry_button)
        progress = findViewById(R.id.catalog_progress)
        refreshButton.setOnClickListener { refresh() }
        retryButton.setOnClickListener { refresh() }
        findViewById<MaterialButton>(R.id.settings_button).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            TooltipCompat.setTooltipText(this, getString(R.string.settings))
        }
        TooltipCompat.setTooltipText(refreshButton, getString(R.string.refresh))
        ViewCompat.setAccessibilityHeading(findViewById(R.id.collection_heading), true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(EXPANDED_APPS, ArrayList(expandedApps))
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (releaseSettings.enabled && !ReleaseNotifications.allowed(this)) DailyReleaseScheduler.disable(this)
        DailyReleaseScheduler.sync(this)
        loadCachedCatalog()
    }

    override fun onStart() {
        super.onStart()
        releaseSettings.preferences.registerOnSharedPreferenceChangeListener(catalogChanges)
    }

    override fun onStop() {
        releaseSettings.preferences.unregisterOnSharedPreferenceChangeListener(catalogChanges)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadCachedCatalog()
    }

    override fun onDestroy() {
        repository.cancel()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun loadCachedCatalog() {
        if (refreshing || isDestroyed) return
        worker.execute {
            val saved = repository.cached()
            runOnUiThread {
                if (isDestroyed || refreshing) return@runOnUiThread
                cacheLoaded = true
                apps = saved
                loadError = null
                renderApps()
                when {
                    !releaseSettings.enabled -> showStatus(R.string.catalog_automatic_off)
                    releaseSettings.lastCatalogCheck == 0L -> showStatus(R.string.catalog_daily_enabled)
                    else -> {
                        val relative = DateUtils.getRelativeTimeSpanString(releaseSettings.lastCatalogCheck,
                            System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                        showStatusText(getString(R.string.catalog_last_checked, relative), R.drawable.ic_check)
                    }
                }
            }
        }
    }

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        refreshButton.isEnabled = false
        retryButton.isEnabled = false
        // This is an explicit user action. Automatic network access belongs only
        // to the opted-in daily worker, never to activity lifecycle callbacks.
        progress.visibility = View.VISIBLE
        showStatus(R.string.catalog_refreshing)
        if (apps == null) {
            loadError = null
            renderEmptyState()
        }
        val restoreCached = apps == null
        worker.execute {
            if (restoreCached) {
                repository.cached()?.let { saved ->
                    runOnUiThread {
                        if (!isDestroyed) {
                            apps = saved
                            cacheLoaded = true
                            renderApps()
                            showStatus(R.string.catalog_saved)
                        }
                    }
                }
            }
            val result = runCatching { repository.refresh() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                progress.visibility = View.INVISIBLE
                result.fold(
                    onSuccess = { loaded ->
                        loadError = null
                        val changed = apps != loaded
                        apps = loaded
                        cacheLoaded = true
                        if (changed) renderApps() else renderEmptyState()
                        releaseSettings.catalogChecked()
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
                refreshing = false
                refreshButton.isEnabled = true
                retryButton.isEnabled = true
            }
        }
    }

    private fun showStatus(message: Int, icon: Int = 0, error: Boolean = false) {
        showStatusText(getString(message), icon, error)
    }

    private fun showStatusText(text: String, icon: Int = 0, error: Boolean = false) {
        if (status.text.toString() != text) status.text = text
        val color = ContextCompat.getColor(this, if (error) R.color.store_error else R.color.store_text_secondary)
        status.setTextColor(color)
        val drawable = if (icon == 0) null else AppCompatResources.getDrawable(this, icon)?.mutate()?.also {
            DrawableCompat.setTint(it, color)
        }
        status.setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
        retryButton.visibility = if (error) View.VISIBLE else View.GONE
        refreshButton.visibility = if (error) View.GONE else View.VISIBLE
    }

    private fun renderEmptyState() {
        emptyState.visibility = if (apps.isNullOrEmpty()) View.VISIBLE else View.GONE
        val failed = apps == null && loadError != null
        findViewById<TextView>(R.id.empty_title).setText(when {
            failed -> R.string.catalog_error_title
            apps == null && (!cacheLoaded || refreshing) -> R.string.catalog_loading_title
            else -> R.string.catalog_empty_title
        })
        findViewById<TextView>(R.id.empty_description).setText(when {
            failed -> loadError!!
            apps == null && (!cacheLoaded || refreshing) -> R.string.catalog_loading
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
            val notificationTarget = intent.getStringExtra(ReleaseNotifications.EXTRA_PACKAGE) == app.packageName
            if (notificationTarget) expandedApps.add(app.packageName)
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
            if (notificationTarget) {
                card.post { findViewById<NestedScrollView>(R.id.catalog_scroll).smoothScrollTo(0, card.top) }
                intent.removeExtra(ReleaseNotifications.EXTRA_PACKAGE)
            }
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
        const val EXPANDED_APPS = "expanded_apps"
    }
}
