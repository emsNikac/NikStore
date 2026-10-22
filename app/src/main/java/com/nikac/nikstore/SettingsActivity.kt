package com.nikac.nikstore

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.nikac.nikstore.updates.DailyReleaseScheduler
import com.nikac.nikstore.updates.ReleaseNotifications
import com.nikac.nikstore.updates.ReleaseSettings

class SettingsActivity : AppCompatActivity() {
    private lateinit var releaseSwitch: MaterialSwitch
    private var binding = false
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableNotifications() else DailyReleaseScheduler.disable(this)
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)
        val root = findViewById<View>(R.id.settings_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val lightTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = lightTheme
            isAppearanceLightNavigationBars = lightTheme
        }
        findViewById<MaterialButton>(R.id.settings_back).setOnClickListener { finish() }
        ViewCompat.setAccessibilityHeading(findViewById(R.id.settings_heading), true)
        releaseSwitch = findViewById(R.id.release_notifications_switch)
        releaseSwitch.setOnCheckedChangeListener { _, checked ->
            if (!binding) {
                if (!checked) DailyReleaseScheduler.disable(this)
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else enableNotifications()
                render()
            }
        }
        findViewById<MaterialButton>(R.id.android_notifications_button).setOnClickListener {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:" + packageName).toUri())
            }
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        if (ReleaseSettings(this).enabled && !ReleaseNotifications.allowed(this)) DailyReleaseScheduler.disable(this)
        render()
    }

    private fun enableNotifications() {
        ReleaseNotifications.createChannel(this)
        if (ReleaseNotifications.allowed(this)) {
            ReleaseSettings(this).enabled = true
            DailyReleaseScheduler.sync(this, realign = true)
        }
    }

    private fun render() {
        val settings = ReleaseSettings(this)
        binding = true
        releaseSwitch.isChecked = settings.enabled && ReleaseNotifications.allowed(this)
        binding = false
        findViewById<TextView>(R.id.release_notifications_status).setText(
            if (settings.enabled) R.string.settings_notifications_on else R.string.settings_notifications_off,
        )
        val permissionBlocked = !ReleaseNotifications.allowed(this)
        findViewById<View>(R.id.notification_permission_notice).visibility = if (permissionBlocked) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.last_catalog_check).text = if (settings.lastCatalogCheck == 0L) {
            getString(R.string.settings_no_checks_yet)
        } else {
            getString(R.string.settings_last_checked, DateUtils.formatDateTime(this, settings.lastCatalogCheck,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME))
        }
    }
}
