package com.nikac.nikstore.install

import org.junit.Assert.*
import org.junit.Test

class ApkValidationTest {
    private val certificate = "b".repeat(64)
    private val spec = ApkSpec("example.app", 2, "https://example.com/app.apk", "a".repeat(64), certificate)
    private val release = ApkMetadata("example.app", 2, 26, setOf(certificate))

    @Test fun acceptsNewInstallAndUpdateWithTheSameSigningKey() {
        ApkValidation.validate(spec, release, 35, null)
        ApkValidation.validate(spec, release, 35, release.copy(versionCode = 1))
    }

    @Test fun rejectsWrongPackageVersionDebugAndUnsignedApks() {
        listOf(release.copy(packageName = "example.other") to InstallFailure.PACKAGE,
            release.copy(versionCode = 3) to InstallFailure.VERSION,
            release.copy(debuggable = true) to InstallFailure.INVALID_APK,
            release.copy(certificates = emptySet()) to InstallFailure.INVALID_APK).forEach { (apk, expected) ->
            assertEquals(expected, assertThrows(ApkException::class.java) { ApkValidation.validate(spec, apk, 35, null) }.failure)
        }
    }

    @Test fun rejectsSigningKeyMismatchAgainstCatalogOrInstalledApp() {
        val different = release.copy(certificates = setOf("c".repeat(64)))
        assertEquals(InstallFailure.SIGNATURE, assertThrows(ApkException::class.java) {
            ApkValidation.validate(spec, different, 35, null)
        }.failure)
        assertEquals(InstallFailure.SIGNATURE, assertThrows(ApkException::class.java) {
            ApkValidation.validate(spec, release, 35, different.copy(versionCode = 1))
        }.failure)
    }

    @Test fun rejectsIncompatibleDeviceAndDowngrade() {
        assertEquals(InstallFailure.INCOMPATIBLE, assertThrows(ApkException::class.java) {
            ApkValidation.validate(spec, release, 25, null)
        }.failure)
        assertEquals(InstallFailure.VERSION, assertThrows(ApkException::class.java) {
            ApkValidation.validate(spec, release, 35, release.copy(versionCode = 3))
        }.failure)
    }
}
