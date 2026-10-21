import base64
import copy
import hashlib
import json
import unittest
from urllib.error import HTTPError
from urllib.request import Request

import publish_release as publisher


APK = b"release APK fixture"
DIGEST = hashlib.sha256(APK).hexdigest()
CERTIFICATE = "b" * 64
CONFIG = {"id": "jsptime", "packageName": "mk.jsp.live", "name": "JspTime", "description": "Bus arrivals"}
METADATA = {"packageName": "mk.jsp.live", "versionName": "2.0.0", "versionCode": 2,
            "minSdk": 26, "signingCertificateSha256": CERTIFICATE}
RELEASE = {"id": 10, "draft": False, "prerelease": False, "body": "Better arrivals",
           "published_at": "2026-10-21T12:00:00Z",
           "assets": [{"id": 20, "name": "JspTime.apk", "state": "uploaded", "digest": "sha256:" + DIGEST}]}


def app_entry():
    return dict(METADATA, name="JspTime", description="Bus arrivals", sha256=DIGEST,
                apkUrl="https://github.com/emsNikac/NikStore/releases/download/jsptime-2/jsptime-2.apk",
                releaseNotes="Better arrivals", publishedAt=RELEASE["published_at"], sizeBytes=len(APK))


class FakeSource:
    def download(self, asset_id, repository, destination):
        if repository != "emsNikac/JspTime" or asset_id != 20:
            raise AssertionError("Unexpected private asset")
        destination.write_bytes(APK)


class FakeDestination:
    def __init__(self):
        self.catalog = {"schemaVersion": 1, "apps": []}
        self.release = None
        self.uploads = 0
        self.writes = 0
        self.private = False
        self.conflict_once = False

    def request(self, method, path, payload=None, allow_missing=False):
        prefix = "/repos/emsNikac/NikStore"
        if method == "GET" and path == prefix:
            return {"private": self.private, "default_branch": "main"}
        if method == "GET" and path.startswith(prefix + "/contents/"):
            return {"content": base64.b64encode(json.dumps(self.catalog).encode()).decode(), "sha": str(self.writes)}
        if method == "GET" and path == prefix + "/releases/tags/jsptime-2":
            return copy.deepcopy(self.release)
        if method == "POST" and path == prefix + "/releases":
            self.release = dict(payload, id=30, assets=[], upload_url="https://uploads.github.com/release{?name}")
            return copy.deepcopy(self.release)
        if method == "PATCH" and path == prefix + "/releases/30":
            self.release.update(payload)
            return copy.deepcopy(self.release)
        if method == "PUT" and path == prefix + "/contents/distribution/catalog.json":
            if self.release is not None and self.release["draft"]:
                raise AssertionError("Catalog must not reference an unpublished APK")
            if self.conflict_once:
                self.conflict_once = False
                self.catalog["apps"].append({"packageName": "com.example.other", "versionCode": 1})
                raise HTTPError(path, 409, "Catalog changed", {}, None)
            self.catalog = json.loads(base64.b64decode(payload["content"]))
            self.writes += 1
            return {}
        raise AssertionError((method, path))

    def upload(self, upload_url, apk, name):
        self.uploads += 1
        asset = {"id": 40, "name": name, "digest": "sha256:" + hashlib.sha256(apk.read_bytes()).hexdigest(),
                 "browser_download_url": "https://github.com/emsNikac/NikStore/releases/download/jsptime-2/" + name}
        self.release["assets"].append(asset)
        return copy.deepcopy(asset)


class PublisherTest(unittest.TestCase):
    def metadata(self, badging=None, certificates=None, package="mk.jsp.live"):
        return publisher.parse_apk_metadata(
            badging or "package: name='mk.jsp.live' versionCode='2' versionName='2.0.0'\nsdkVersion:'26'\n",
            certificates or "Signer #1 certificate DN: CN=NikApps\nSigner #1 certificate SHA-256 digest: " + CERTIFICATE,
            package)

    def publish(self, destination, release=None):
        publisher.publish(FakeSource(), destination, "emsNikac/JspTime", "emsNikac/NikStore",
                          release or RELEASE, CONFIG, inspect=lambda _: dict(METADATA))

    def test_reads_apk_versions(self):
        self.assertEqual(self.metadata(), METADATA)
        result = self.metadata("package: name='mk.jsp.live' versionCode='2' versionCodeMajor='1' "
                               "versionName='2.0.0'\nsdkVersion:'26'\n")
        self.assertEqual(result["versionCode"], 4294967298)

    def test_rejects_wrong_package_and_debug_builds(self):
        with self.assertRaises(publisher.PublishError):
            self.metadata(package="com.example.other")
        with self.assertRaises(publisher.PublishError):
            self.metadata("package: name='mk.jsp.live' versionCode='2' versionName='2.0.0'\n"
                          "sdkVersion:'26'\napplication-debuggable\n")
        with self.assertRaises(publisher.PublishError):
            self.metadata(certificates="Signer #1 certificate DN: CN=Android Debug, O=Android, C=US\n"
                          "Signer #1 certificate SHA-256 digest: " + CERTIFICATE)

    def test_rejects_unpublished_and_ambiguous_releases(self):
        for changes in [{"draft": True}, {"prerelease": True}, {"assets": []},
                        {"assets": RELEASE["assets"] * 2}]:
            with self.subTest(changes=changes), self.assertRaises(publisher.PublishError):
                publisher.select_asset(dict(RELEASE, **changes))

    def test_selects_configured_asset(self):
        release = copy.deepcopy(RELEASE)
        release["assets"].append(dict(release["assets"][0], name="other.apk"))
        self.assertEqual(publisher.select_asset(release, "JspTime.apk")["id"], 20)

    def test_publishes_apk_before_catalog_and_is_idempotent(self):
        destination = FakeDestination()
        self.publish(destination)
        self.assertFalse(destination.release["draft"])
        self.assertEqual(destination.catalog["apps"], [app_entry()])
        self.publish(destination)
        self.assertEqual(destination.uploads, 1)
        self.assertEqual(destination.writes, 1)

    def test_old_release_cannot_roll_back_catalog(self):
        destination = FakeDestination()
        newer = app_entry()
        newer["versionCode"] = 3
        destination.catalog["apps"] = [newer]
        self.publish(destination)
        self.assertEqual(destination.catalog["apps"][0]["versionCode"], 3)
        self.assertEqual(destination.uploads, 0)
        self.assertIsNone(destination.release)

    def test_rejects_changed_binary_with_same_version_code(self):
        destination = FakeDestination()
        previous = app_entry()
        previous["sha256"] = "a" * 64
        destination.catalog["apps"] = [previous]
        with self.assertRaises(publisher.PublishError):
            self.publish(destination)
        self.assertEqual(destination.uploads, 0)

    def test_rejects_changed_signing_certificate(self):
        destination = FakeDestination()
        previous = app_entry()
        previous.update(versionCode=1, signingCertificateSha256="c" * 64)
        destination.catalog["apps"] = [previous]
        with self.assertRaises(publisher.PublishError):
            self.publish(destination)
        self.assertEqual(destination.uploads, 0)

    def test_catalog_conflict_preserves_another_apps_update(self):
        destination = FakeDestination()
        destination.conflict_once = True
        self.publish(destination)
        self.assertEqual([app["packageName"] for app in destination.catalog["apps"]],
                         ["com.example.other", "mk.jsp.live"])

    def test_rejects_private_destination_and_corrupt_download(self):
        destination = FakeDestination()
        destination.private = True
        with self.assertRaises(publisher.PublishError):
            self.publish(destination)
        destination.private = False
        corrupt = copy.deepcopy(RELEASE)
        corrupt["assets"][0]["digest"] = "sha256:" + "0" * 64
        with self.assertRaises(publisher.PublishError):
            self.publish(destination, corrupt)
        self.assertEqual(destination.uploads, 0)

    def test_strips_credentials_on_redirect_to_asset_storage(self):
        request = Request("https://api.github.com/asset", headers={"Authorization": "Bearer test"})
        handler = publisher.SafeRedirectHandler()
        redirected = handler.redirect_request(request, None, 302, "Found", {}, "https://assets.example.com/apk")
        self.assertIsNone(redirected.get_header("Authorization"))
        with self.assertRaises(publisher.PublishError):
            handler.redirect_request(request, None, 302, "Found", {}, "http://assets.example.com/apk")

    def test_duplicate_catalog_packages_are_rejected(self):
        destination = FakeDestination()
        destination.catalog["apps"] = [app_entry(), app_entry()]
        with self.assertRaises(publisher.PublishError):
            publisher.read_catalog(destination, "emsNikac/NikStore", "main")


if __name__ == "__main__":
    unittest.main()
