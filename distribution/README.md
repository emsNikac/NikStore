# NikStore catalog and JspTime releases

NikStore reads the public catalog at
[catalog.json](https://emsnikac.github.io/NikStore/catalog.json). The catalog and
Android application live together in `emsNikac/NikStore`. JspTime's source stays
in the private `emsNikac/JspTime` repository.

The release connection works as follows:

1. Attach a signed release APK to a stable GitHub release in private JspTime,
   then publish the release.
2. JspTime's workflow verifies the APK signature and reads its actual package,
   version name, version code, and minimum SDK. Debug builds and the wrong
   package are rejected.
3. It copies the APK into a public NikStore release, tagged `jsptime-2` for
   version code `2`, and updates `distribution/catalog.json` on `main`.
4. The catalog commit triggers the Pages workflow. Only `catalog.json` and
   `index.html` are published to Pages; draft records are excluded.
5. NikStore shows the saved catalog when opened. Tap **Refresh** to fetch it, or
   enable **Settings > Release notifications** for a daily background check.
   It shows available and installed versions, update status, and release notes.
   A valid catalog is saved for offline use.

NikStore can also download and install these APKs. Push messaging is a subsequent
feature.

## Installing and updating apps

Each compatible app has an **Install** button, an **Update** button when a newer
version is available, or **Open** when the installed version is current or newer.
The first installation asks the user to allow NikStore under Android's **Allow
from this source** setting. Android then presents its normal confirmation screen.

Downloads are user initiated and show progress with a Cancel button. They can
continue when the screen is closed; completed downloads can be installed on
return. Downloading remains available when release notifications are disabled.
No APKs are automatically downloaded or silently installed by the nightly check.

Before opening the installer, NikStore checks the downloaded APK's SHA-256,
expected size, package name, version code, Android requirement, and signing
certificate. An update must use the same signing certificate as the installed
app. Debug builds, downgrades, and mismatched APKs are rejected. Currently the
store accepts single universal release APKs up to 512 MB; split APK bundles are
not supported.

APKs stay in the app's private cache and are shared with Android's installer
through a temporary read grant. Failed partial downloads are removed, installed
APKs are cleaned up when the catalog screen sees the installed version, and
unused completed downloads expire after seven days. Canceling Android's installer
keeps the verified download available for another attempt.

## Daily release notifications

Release notifications are off by default. When enabled, Android WorkManager
checks the catalog around **23:00 in the device's local time zone**, including
after a reboot. The schedule adjusts when the clock or time zone changes.
Android can delay work to save battery or wait for a network connection; 23:00
is a target rather than an exact alarm.

Each nightly window gets one automatic attempt, with no repeated network retries
on failure. The app remembers version codes and announces each newer release
once. If no catalog has been saved yet, the first successful check establishes
a baseline. Notifications skip incompatible apps and versions already installed.
Tapping an alert opens that app's release details.

Turning the setting off cancels scheduled work and release alerts. Opening the
app then reads only its saved catalog; it makes no automatic update requests.
**Refresh** remains an explicit manual check. Android notification permission is
also required; blocked notifications prevent background fetching.

The trigger runs on GitHub's servers after a GitHub release is published. The
workflow file must be committed to the private JspTime repository before that
release. Generating an APK, building the project, and committing locally do not
trigger publishing. No local build hook is installed.

## One-time GitHub setup

### NikStore

1. Commit and push this project to **emsNikac/NikStore**, with `main` as the
   default branch. The repository and APK releases must be public for anonymous
   catalog access and downloads.
2. In **Settings > Pages > Build and deployment**, choose **GitHub Actions**.
   The workflow is `.github/workflows/catalog-pages.yml`.
3. Run **Actions > Publish NikStore catalog > Run workflow** for the first
   deployment. Open the catalog address above and confirm an empty app list.
   Later catalog commits deploy automatically.

### Private JspTime

1. Install `integrations/jsptime/publish-to-nikstore.yml` from NikStore as
   **.github/workflows/publish-to-nikstore.yml** in JspTime. Commit and push it
   there. The workflow reads the publisher script from NikStore's `main` branch.
2. Create a fine-grained GitHub personal access token scoped to **only
   emsNikac/NikStore**, with **Contents: Read and write**. This permits uploading
   release assets and updating the catalog through GitHub's API.
3. In **JspTime > Settings > Secrets and variables > Actions**, add a repository
   secret named **NIKSTORE_PUBLISH_TOKEN** containing that token. JspTime's own
   short-lived `GITHUB_TOKEN` reads its private release; no private-repository
   read token is stored in NikStore or sent to phones.
4. If a release has multiple APKs, add an Actions repository variable named
   **NIKSTORE_APK_ASSET** containing the exact APK asset filename. This is
   unnecessary with a single APK. Use a universal APK for this first integration.

Configure Pages before publishing the first release. Expired tokens or branch
rules preventing writes to `main` cause the publisher to fail; the existing
catalog remains available. Standard GitHub-hosted Ubuntu runners provide the
Android SDK tools used to inspect APKs.

The workflow makes the APK and its release notes public. Application source is
not copied. Keep signing keys, passwords, and publishing tokens private.

## First JspTime release

The local project checked during setup has package **mk.jsp.live**, version
**2.0.0**, version code **2**, and minimum SDK **26**. Its existing APK is a debug
build. Generate a signed release APK with your permanent release key first.

1. In JspTime, generate a **signed release APK**. Keep the same release key for
   subsequent updates. Existing debug-signed installations cannot be updated
   in place by a release APK signed with a different key.
2. Draft a GitHub release, for example `v2.0.0`. Attach the APK and add release
   notes, then publish after the upload completes.
3. Check **Actions > Publish JspTime to NikStore** in JspTime. A successful run
   creates a public NikStore release such as `jsptime-2` and a catalog commit.
4. After **Publish NikStore catalog** succeeds in NikStore, open the Android app
   and tap **Refresh**. JspTime should show version `2.0.0` and build `2`.

For later updates, increase Android's numeric `versionCode`. Release tags and
version names do not determine update order. Retries reuse identical mirrored
APKs. Older releases cannot roll back the catalog; reusing a version code with
different bytes or changing signing certificates fails explicitly.

To import an existing release or retry after fixing setup, use **JspTime >
Actions > Publish JspTime to NikStore > Run workflow** and enter the source
release tag. Prereleases are excluded. Releases created by another workflow using
`GITHUB_TOKEN` do not trigger a new release workflow. Run the manual publisher
for that tag, or explicitly dispatch it from the release pipeline after uploading
the APK.

## Configuration and validation

- `integrations/apps.json` registers source repositories and expected packages.
  Additional apps need a publisher workflow in their own repository and a
  package visibility declaration in NikStore's manifest for installed-version
  detection.
- `drafts/jsptime.json` is reference metadata and is excluded from the live feed.
- Override the Android catalog endpoint at build time with
  `-PnikstoreCatalogUrl=https://...` for a different HTTPS endpoint.
- Publisher tests: `python3 -B -m unittest discover -s scripts -p 'test_*.py'`.
- Android checks: `./gradlew testDebugUnitTest lintDebug assembleDebug`.

## References

- [Release workflow events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#release)
- [Release asset permissions](https://docs.github.com/en/rest/releases/assets)
- [Pages workflows](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages)
