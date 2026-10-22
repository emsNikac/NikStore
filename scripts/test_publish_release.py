import base64
import copy
import hashlib
import io
import json
import subprocess
import unittest
from unittest.mock import patch
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

    def test_reads_scheme_and_sdk_signer_labels(self):
        for label in ["V1 Signer:", "V2 Signer:", "V2 Signer #1:", "V3.0 Signer:",
                      "V3.1 Signer:", "V4 Signer:",
                      "Signer (minSdkVersion=33, maxSdkVersion=2147483647)"]:
            with self.subTest(label=label):
                certificates = ("Number of signers: 1\n" + label + " certificate DN: CN=NikApps\n"
                                + label + " certificate SHA-256 digest: " + CERTIFICATE.upper())
                self.assertEqual(self.metadata(certificates=certificates), METADATA)

    def test_same_certificate_across_schemes_is_one_signing_identity(self):
        certificates = ("Number of signers: 1\nV2 Signer: certificate SHA-256 digest: " + CERTIFICATE
                        + "\nV3.0 Signer: certificate SHA-256 digest: " + CERTIFICATE.upper()
                        + "\nSource Stamp Signer certificate SHA-256 digest: " + "c" * 64)
        self.assertEqual(self.metadata(certificates=certificates), METADATA)

    def test_rejects_debug_certificate_in_other_output_formats(self):
        for label in ["V2 Signer:", "V3.0 Signer:",
                      "Signer (minSdkVersion=33, maxSdkVersion=2147483647)"]:
            with self.subTest(label=label), self.assertRaisesRegex(publisher.PublishError, "debug"):
                self.metadata(certificates=label + " certificate DN: CN=Android Debug, O=Android, C=US\n"
                              + label + " certificate SHA-256 digest: " + CERTIFICATE)

    def test_rejects_missing_malformed_and_unknown_signing_certificates(self):
        for certificates in ["Verifies\n", "Source Stamp Signer certificate SHA-256 digest: " + CERTIFICATE,
                             "Signer #1 public key SHA-256 digest: " + CERTIFICATE,
                             "V2 Signer: certificate SHA-256 digest: invalid",
                             "Unknown Signer certificate SHA-256 digest: " + CERTIFICATE]:
            with self.subTest(certificates=certificates), self.assertRaises(publisher.PublishError):
                self.metadata(certificates=certificates)

    def test_rejects_multiple_signers_and_rotated_certificates(self):
        for certificates in [
                "Signer #1 certificate SHA-256 digest: " + CERTIFICATE
                + "\nSigner #2 certificate SHA-256 digest: " + "c" * 64,
                "V3.0 Signer: certificate SHA-256 digest: " + CERTIFICATE
                + "\nV3.1 Signer: certificate SHA-256 digest: " + "c" * 64,
                "Number of signers: 2\nV2 Signer: certificate SHA-256 digest: " + CERTIFICATE]:
            with self.subTest(certificates=certificates), self.assertRaises(publisher.PublishError):
                self.metadata(certificates=certificates)

    def test_failed_signature_verification_cannot_publish_even_with_certificate_output(self):
        badging = "package: name='mk.jsp.live' versionCode='2' versionName='2.0.0'\nsdkVersion:'26'\n"
        failed_verification = subprocess.CompletedProcess(
            [], 1, "V2 Signer: certificate SHA-256 digest: " + CERTIFICATE, "DOES NOT VERIFY")
        destination = FakeDestination()
        with patch.object(publisher, "find_android_tool", side_effect=lambda name: name), \
                patch.object(publisher.subprocess, "run", side_effect=[
                    subprocess.CompletedProcess([], 0, badging, ""), failed_verification]):
            with self.assertRaisesRegex(publisher.PublishError, "apksigner could not validate"):
                publisher.publish(FakeSource(), destination, "emsNikac/JspTime", "emsNikac/NikStore",
                                  RELEASE, CONFIG)
        self.assertEqual(destination.uploads, 0)
        self.assertEqual(destination.writes, 0)

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


class GitHubErrorTest(unittest.TestCase):
    def client(self, body=None, code=403):
        client = publisher.GitHub("test-secret", "NIKSTORE_PUBLISH_TOKEN")
        error = HTTPError("https://redirect.example/apk?signature=private-value", code, "Forbidden",
                          {"X-Accepted-GitHub-Permissions": "contents=write"},
                          io.BytesIO(body or b'{"message":"Resource not accessible by personal access token"}'))
        client.opener = unittest.mock.Mock()
        client.opener.open.side_effect = error
        return client

    def test_rejected_operations_identify_request_and_credential_without_signed_urls(self):
        with publisher.tempfile.TemporaryDirectory(prefix=".nikstore-test-", dir=publisher.Path.cwd()) as directory:
            apk = publisher.Path(directory) / "release.apk"
            apk.write_bytes(APK)
            for operation, expected in [
                    (lambda client: client.request("POST", "/repos/emsNikac/NikStore/releases", {}),
                     "POST /repos/emsNikac/NikStore/releases"),
                    (lambda client: client.request("PUT", "/repos/emsNikac/NikStore/contents/distribution/catalog.json", {}),
                     "PUT /repos/emsNikac/NikStore/contents/distribution/catalog.json"),
                    (lambda client: client.upload("https://uploads.github.com/repos/emsNikac/NikStore/releases/30/assets{?name}",
                                                  apk, "release.apk"),
                     "POST /repos/emsNikac/NikStore/releases/30/assets"),
                    (lambda client: client.download(20, "emsNikac/JspTime", apk),
                     "GET /repos/emsNikac/JspTime/releases/assets/20")]:
                with self.subTest(operation=expected), self.assertRaises(HTTPError) as raised:
                    operation(self.client())
                message = raised.exception.nikstore_message
                for text in (expected, "HTTP 403", "NIKSTORE_PUBLISH_TOKEN",
                             "Resource not accessible by personal access token", "contents=write"):
                    self.assertIn(text, message)
                for text in ("test-secret", "signature", "private-value", "redirect.example"):
                    self.assertNotIn(text, message)

    def test_error_messages_redact_credentials_and_ignore_non_json(self):
        with patch.dict(publisher.os.environ, {"SOURCE_GITHUB_TOKEN": "source-secret"}):
            client = self.client(json.dumps({"message": "test-secret source-secret github_pat_other_secret"}).encode())
            with self.assertRaises(HTTPError) as raised:
                client.request("POST", "/repos/emsNikac/NikStore/releases", {})
            for text in ("test-secret", "source-secret", "github_pat_other_secret"):
                self.assertNotIn(text, raised.exception.nikstore_message)
            self.assertIn("[REDACTED]", raised.exception.nikstore_message)
        with self.assertRaises(HTTPError) as raised:
            self.client(b"<html>private response body</html>").request("POST", "/repos/emsNikac/NikStore/releases", {})
        self.assertNotIn("private response body", raised.exception.nikstore_message)

    def test_missing_release_and_conflicts_keep_existing_http_error_behavior(self):
        self.assertIsNone(self.client(code=404).request("GET", "/repos/emsNikac/NikStore/releases/tags/jsptime-2",
                                                     allow_missing=True))
        with self.assertRaises(HTTPError) as raised:
            self.client(code=409).request("PUT", "/repos/emsNikac/NikStore/contents/distribution/catalog.json", {})
        self.assertEqual(raised.exception.code, 409)


if __name__ == "__main__":
    unittest.main()
