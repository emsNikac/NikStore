package com.nikac.nikstore

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.nikac.nikstore.catalog.AppState
import com.nikac.nikstore.catalog.CatalogApp
import com.nikac.nikstore.catalog.CatalogRepository
import com.nikac.nikstore.catalog.CatalogUnavailableException
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var repository: CatalogRepository
    private lateinit var cards: LinearLayout
    private lateinit var status: TextView
    private lateinit var refreshButton: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private var refreshing = false
    private var apps: List<CatalogApp>? = null
    private val periodicRefresh = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.catalog_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        repository = CatalogRepository(applicationContext, BuildConfig.CATALOG_URL)
        cards = findViewById(R.id.app_cards)
        status = findViewById(R.id.catalog_status)
        refreshButton = findViewById(R.id.refresh_button)
        progress = findViewById(R.id.catalog_progress)
        refreshButton.setOnClickListener { refresh() }
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

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        refreshButton.isEnabled = false
        progress.visibility = View.VISIBLE
        status.setText(R.string.catalog_refreshing)
        val restoreCached = apps == null
        worker.execute {
            if (restoreCached) {
                repository.cached()?.let { saved ->
                    runOnUiThread {
                        if (!isDestroyed) {
                            apps = saved
                            renderApps()
                        }
                    }
                }
            }
            val result = runCatching { repository.refresh() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                refreshing = false
                refreshButton.isEnabled = true
                progress.visibility = View.GONE
                result.fold(
                    onSuccess = { loaded ->
                        apps = loaded
                        renderApps()
                        status.setText(if (loaded.isEmpty()) R.string.catalog_empty else R.string.catalog_current)
                    },
                    onFailure = { error ->
                        status.setText(when {
                            apps != null -> R.string.catalog_offline
                            error is CatalogUnavailableException -> R.string.catalog_not_published
                            else -> R.string.catalog_error
                        })
                    },
                )
            }
        }
    }

    private fun renderApps() {
        cards.removeAllViews()
        apps.orEmpty().forEach { app ->
            val card = layoutInflater.inflate(R.layout.item_catalog_app, cards, false) as MaterialCardView
            card.findViewById<TextView>(R.id.card_name).text = app.name
            card.findViewById<TextView>(R.id.card_description).text = app.description
            card.findViewById<TextView>(R.id.card_version).text = getString(
                R.string.available_version, app.versionName, app.versionCode,
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
            val state = app.state(installedCode, Build.VERSION.SDK_INT)
            card.findViewById<TextView>(R.id.card_state).text = when (state) {
                AppState.NOT_INSTALLED -> getString(R.string.app_available)
                AppState.UPDATE_AVAILABLE -> getString(R.string.app_update_available)
                AppState.UP_TO_DATE -> getString(R.string.app_up_to_date)
                AppState.NEWER_INSTALLED -> getString(R.string.app_newer_installed)
                AppState.INCOMPATIBLE -> getString(R.string.app_incompatible, app.minSdk)
            }
            card.findViewById<TextView>(R.id.card_notes).apply {
                text = app.releaseNotes
                visibility = if (app.releaseNotes.isBlank()) View.GONE else View.VISIBLE
            }
            cards.addView(card)
        }
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 60_000L
    }
}
