package com.nikac.nikstore.updates

import com.nikac.nikstore.catalog.CatalogApp
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DailyReleaseCheckTest {
    private class State : ReleaseCheckState {
        override var enabled = true
        override var knownVersions: Map<String, Long>? = null
        val days = mutableSetOf<String>()
        var saveSucceeds = true
        override fun claimCheckDay(day: String) = enabled && days.add(day)
        override fun rememberVersions(versions: Map<String, Long>): Boolean {
            if (saveSucceeds) knownVersions = versions
            return saveSucceeds
        }
    }

    private class Check {
        val state = State()
        var permission = true
        var stopped = false
        var cached: List<CatalogApp>? = null
        var releases = listOf(app(1))
        var fetches = 0
        var changes = 0
        val notifications = mutableListOf<CatalogApp>()
        var duringCache: () -> Unit = {}
        var duringFetch: (() -> Boolean) -> Unit = {}
        val check = DailyReleaseCheck(state, { permission }, { stopped }, {
            duringCache()
            cached
        }, { allowed ->
            fetches++
            assertTrue(allowed())
            duringFetch(allowed)
            releases
        }, { changes++ }, { notifications.addAll(it) })
    }

    @Test fun disabledDoesNotFetchOrClaimADay() {
        val test = Check().apply { state.enabled = false }
        assertFalse(test.check.run("2026-10-22"))
        assertEquals(0, test.fetches)
        assertTrue(test.state.days.isEmpty())
    }

    @Test fun deniedPermissionAndStoppedWorkDoNotFetch() {
        listOf(Check().apply { permission = false }, Check().apply { stopped = true }).forEach {
            assertFalse(it.check.run("2026-10-22"))
            assertEquals(0, it.fetches)
            assertTrue(it.state.days.isEmpty())
        }
    }

    @Test fun disablingWhileReadingCachePreventsTheRequest() {
        val test = Check()
        test.duringCache = { test.state.enabled = false }
        assertFalse(test.check.run("2026-10-22"))
        assertEquals(0, test.fetches)
    }

    @Test fun disablingDuringFetchStopsPublishingAndVersionChanges() {
        val test = Check().apply { cached = listOf(app(1)); releases = listOf(app(2)) }
        test.duringFetch = { allowed -> test.state.enabled = false; assertFalse(allowed()) }
        assertFalse(test.check.run("2026-10-22"))
        assertTrue(test.notifications.isEmpty())
        assertEquals(0, test.changes)
        assertNull(test.state.knownVersions)
    }

    @Test fun firstCheckEstablishesBaselineWithoutHistoricalAlerts() {
        val test = Check()
        assertTrue(test.check.run("2026-10-22"))
        assertEquals(mapOf("example.app" to 1L), test.state.knownVersions)
        assertTrue(test.notifications.isEmpty())
        assertEquals(1, test.changes)
    }

    @Test fun comparesWithExistingCacheAndAnnouncesNewVersionsAndAppsOnce() {
        val test = Check().apply {
            cached = listOf(app(1))
            releases = listOf(app(2), app(1, "example.other"))
        }
        test.check.run("2026-10-22")
        assertEquals(test.releases, test.notifications)
        assertFalse(test.check.run("2026-10-22"))
        assertEquals(1, test.fetches)
        test.check.run("2026-10-23")
        assertEquals(2, test.notifications.size)
        assertEquals(2, test.fetches)
    }

    @Test fun removedAndRolledBackVersionsCannotRepeatOldAlerts() {
        val test = Check().apply {
            state.knownVersions = mapOf("example.app" to 3L, "example.other" to 2L)
            releases = listOf(app(2))
        }
        test.check.run("2026-10-22")
        assertEquals(mapOf("example.app" to 3L, "example.other" to 2L), test.state.knownVersions)
        test.releases = listOf(app(3), app(2, "example.other"))
        test.check.run("2026-10-23")
        assertTrue(test.notifications.isEmpty())
        test.releases = listOf(app(4))
        test.check.run("2026-10-24")
        assertEquals(listOf(app(4)), test.notifications)
    }

    @Test fun failedAttemptIsNotFetchedAgainUntilNextNight() {
        val test = Check()
        test.duringFetch = { throw IOException("offline") }
        assertThrows(IOException::class.java) { test.check.run("2026-10-22") }
        assertFalse(test.check.run("2026-10-22"))
        assertEquals(1, test.fetches)
        test.duringFetch = {}
        assertTrue(test.check.run("2026-10-23"))
        assertEquals(2, test.fetches)
    }

    @Test fun failedVersionPersistenceDoesNotPostUntrackedNotifications() {
        val test = Check().apply { cached = listOf(app(1)); releases = listOf(app(2)); state.saveSucceeds = false }
        test.check.run("2026-10-22")
        assertTrue(test.notifications.isEmpty())
        assertNull(test.state.knownVersions)
    }

    private companion object {
        fun app(code: Long, pkg: String = "example.app") = CatalogApp(pkg, "Example", "An app",
            code.toString(), code, 24, "https://example.com/app.apk", "a".repeat(64), "Changes")
    }
}
