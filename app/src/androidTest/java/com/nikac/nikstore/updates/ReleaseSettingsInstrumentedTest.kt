package com.nikac.nikstore.updates

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nikac.nikstore.R
import com.nikac.nikstore.SettingsActivity
import com.nikac.nikstore.catalog.CatalogApp
import com.nikac.nikstore.catalog.CatalogRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReleaseSettingsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val settings get() = ReleaseSettings(context)
    private val manager get() = WorkManager.getInstance(context)

    @Before fun reset() {
        DailyReleaseScheduler.disable(context)
        manager.cancelUniqueWork(DailyReleaseScheduler.WORK_NAME).result.get(10, TimeUnit.SECONDS)
        settings.preferences.edit().clear().commit()
        if (Build.VERSION.SDK_INT >= 33) {
            val command = "pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}"
            instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
        }
        ReleaseNotifications.createChannel(context)
    }

    @After fun cleanup() {
        DailyReleaseScheduler.disable(context)
        manager.cancelUniqueWork(DailyReleaseScheduler.WORK_NAME).result.get(10, TimeUnit.SECONDS)
        settings.preferences.edit().clear().commit()
    }

    @Test fun settingsSwitchSchedules23LocalAndCancelsWhenOff() {
        assertTrue(ReleaseNotifications.allowed(context))
        assertFalse(settings.enabled)
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withId(R.id.release_notifications_switch)).check(matches(isNotChecked()))
            onView(withId(R.id.release_notifications_switch)).perform(click())
            assertTrue(settings.enabled)
            val work = manager.getWorkInfosForUniqueWork(DailyReleaseScheduler.WORK_NAME)
                .get(10, TimeUnit.SECONDS).single { !it.state.isFinished }
            assertEquals(WorkInfo.State.ENQUEUED, work.state)
            assertTrue(kotlin.math.abs(work.nextScheduleTimeMillis -
                DailyReleasePolicy.nextCheckAt(System.currentTimeMillis())) < 2_000)
            onView(withId(R.id.release_notifications_switch)).check(matches(isChecked()))
            onView(withId(R.id.release_notifications_switch)).perform(click())
            assertFalse(settings.enabled)
            assertTrue(manager.getWorkInfosForUniqueWork(DailyReleaseScheduler.WORK_NAME)
                .get(10, TimeUnit.SECONDS).all { it.state.isFinished })
        }
    }

    @Test fun disabledGuardRejectsRequestBeforeOpeningAConnection() {
        // A malformed URL would fail validation if the network gate were bypassed.
        val repository = CatalogRepository(context, "not-a-url")
        assertThrows(CancellationException::class.java) { repository.refresh { settings.enabled } }
        assertEquals(0L, settings.lastCatalogCheck)
    }

    @Test fun releasesPostOnlyWhenEnabledAndDisablingClearsAlerts() {
        val app = CatalogApp("example.release", "Example", "Example app", "2.0", 2, 24,
            "https://example.com/app.apk", "a".repeat(64), "Changes")
        val notifications = context.getSystemService(NotificationManager::class.java)
        ReleaseNotifications.publish(context, listOf(app))
        assertTrue(notifications.activeNotifications.isEmpty())
        settings.enabled = true
        ReleaseNotifications.publish(context, listOf(app, app.copy(packageName = "example.future", minSdk = 99)))
        assertEquals(1, notifications.activeNotifications.size)
        assertEquals("release:example.release", notifications.activeNotifications.single().tag)
        assertNotNull(notifications.activeNotifications.single().notification.contentIntent)
        DailyReleaseScheduler.disable(context)
        assertTrue(notifications.activeNotifications.isEmpty())
    }
}
