#!/usr/bin/env python3
"""Mirror a private app's signed release APK and update the public NikStore feed.

Runs in the source repository's Actions workflow. SOURCE_GITHUB_TOKEN can read
that private repository; NIKSTORE_TOKEN can write only the distribution repository.
Neither credential is included in the catalog or APK.
"""

import argparse
import base64
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
from urllib.error import HTTPError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


CATALOG_PATH = "distribution/catalog.json"
ROOT = Path(__file__).resolve().parents[1]
APK_SIGNER_LABEL = (r"(?:Signer #\d+|V[1-4](?:\.\d+)? Signer(?: #\d+)?:?"
                    r"|Signer \(minSdkVersion=[^\r\n]+\))")


class PublishError(Exception):
    pass


class SafeRedirectHandler(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        if urlsplit(new_url).scheme != "https":
            raise PublishError("Refusing a non-HTTPS download redirect")
        redirected = super().redirect_request(request, fp, code, message, headers, new_url)
        if redirected is not None and urlsplit(request.full_url).netloc != urlsplit(new_url).netloc:
            redirected.remove_header("Authorization")
        return redirected


class GitHub:
    def __init__(self, token):
        if not token:
            raise PublishError("A required GitHub Actions secret is missing")
        self.token = token
        self.opener = build_opener(SafeRedirectHandler())

    def request(self, method, path, payload=None, allow_missing=False):
        url = "https://api.github.com" + path
        data = None if payload is None else json.dumps(payload).encode()
        request = Request(url, data=data, method=method, headers=self.headers())
        if data is not None:
            request.add_header("Content-Type", "application/json")
        try:
            with self.opener.open(request, timeout=60) as response:
                body = response.read()
                return json.loads(body) if body else None
        except HTTPError as error:
            if error.code == 404 and allow_missing:
                return None
            raise

    def headers(self):
        return {
            "Authorization": "Bearer " + self.token,
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "NikStore-release-publisher",
        }

    def download(self, asset_id, repository, destination):
        headers = self.headers()
        headers["Accept"] = "application/octet-stream"
        request = Request(
            "https://api.github.com/repos/{}/releases/assets/{}".format(repository, asset_id),
            headers=headers,
        )
        with self.opener.open(request, timeout=120) as response, destination.open("wb") as output:
            shutil.copyfileobj(response, output)

    def upload(self, upload_url, apk, name):
        url = upload_url.split("{", 1)[0] + "?" + urlencode({"name": name})
        if urlsplit(url).scheme != "https" or urlsplit(url).hostname != "uploads.github.com":
            raise PublishError("Unexpected GitHub upload URL")
        headers = self.headers()
        headers.update({"Content-Type": "application/vnd.android.package-archive",
                        "Content-Length": str(apk.stat().st_size)})
        with apk.open("rb") as stream:
            request = Request(url, data=stream, method="POST", headers=headers)
            with self.opener.open(request, timeout=120) as response:
                return json.load(response)


def find_android_tool(name):
    executable = shutil.which(name)
    if executable:
        return executable
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        tools = [path for path in (Path(sdk) / "build-tools").glob("*/" + name)
                 if path.is_file() and "rc" not in path.parent.name]
        tools.sort(key=lambda path: tuple(int(n) for n in re.findall(r"\d+", path.parent.name)))
        if tools:
            return str(tools[-1])
    raise PublishError("Android SDK tool {} was not found; set ANDROID_HOME".format(name))


def tool_output(tool, *args):
    result = subprocess.run([find_android_tool(tool), *map(str, args)],
                            text=True, capture_output=True, check=False)
    if result.returncode:
        raise PublishError("{} could not validate the APK".format(tool))
    return result.stdout


def parse_apk_metadata(badging, certificates, expected_package):
    package_line = next((line for line in badging.splitlines() if line.startswith("package: ")), None)
    sdk = re.search(r"^sdkVersion:'(\d+)'$", badging, re.MULTILINE)
    if not package_line or not sdk:
        raise PublishError("The APK has no readable Android package metadata")
    fields = dict(item.split("=", 1) for item in shlex.split(package_line)[1:] if "=" in item)
    if fields.get("name") != expected_package:
        raise PublishError("APK package does not match the registered application")
    if "application-debuggable" in badging.splitlines() or re.search(
            r"^" + APK_SIGNER_LABEL + r" certificate DN:.*CN=Android Debug(?:,|$)",
            certificates, re.MULTILINE):
        raise PublishError("Publish a release-signed APK, not an Android debug build")
    certificate_lines = re.findall(r"^(.+?) certificate SHA-256 digest: (.*)$",
                                   certificates, re.MULTILINE)
    signers = set()
    for label, digest in certificate_lines:
        # A source stamp identifies the distributor, not the APK's signing key.
        if label == "Source Stamp Signer":
            continue
        if not re.fullmatch(APK_SIGNER_LABEL, label) or not re.fullmatch(r"[a-fA-F0-9]{64}", digest):
            raise PublishError("Unrecognized apksigner certificate output for {!r}".format(label))
        # Newer tools may print the same certificate for multiple signature schemes.
        signers.add(digest.lower())
    counts = re.findall(r"^Number of signers: (\d+)$", certificates, re.MULTILINE)
    if len(signers) != 1 or any(int(count) != 1 for count in counts):
        raise PublishError("Expected one verified APK signing certificate; found {} distinct "
                           "certificates (apksigner counts: {})".format(
                               len(signers), ", ".join(counts) or "not reported"))
    try:
        low = int(fields["versionCode"])
        major = int(fields.get("versionCodeMajor", "0"))
        version_code = (major << 32) | low
        version_name = fields["versionName"].strip()
        min_sdk = int(sdk.group(1))
    except (ValueError, KeyError) as error:
        raise PublishError("Invalid APK version metadata") from error
    if not version_name or low < 0 or major < 0 or version_code <= 0 or min_sdk <= 0:
        raise PublishError("Invalid APK version metadata")
    return {"packageName": expected_package, "versionName": version_name,
            "versionCode": version_code, "minSdk": min_sdk,
            "signingCertificateSha256": next(iter(signers))}


def select_asset(release, asset_name=""):
    if release.get("draft") or release.get("prerelease"):
        raise PublishError("Only published stable releases can enter the catalog")
    assets = [asset for asset in release.get("assets", [])
              if asset.get("name", "").lower().endswith(".apk") and asset.get("state") == "uploaded"
              and (not asset_name or asset["name"] == asset_name)]
    if len(assets) != 1:
        raise PublishError("Attach exactly one APK, or set the NIKSTORE_APK_ASSET repository variable")
    return assets[0]


def read_catalog(github, repository, branch):
    document = github.request("GET", "/repos/{}/contents/{}?{}".format(
        repository, CATALOG_PATH, urlencode({"ref": branch})))
    catalog = json.loads(base64.b64decode(document["content"], validate=False))
    if catalog.get("schemaVersion") != 1 or not isinstance(catalog.get("apps"), list):
        raise PublishError("Unsupported destination catalog format")
    packages = [entry["packageName"] for entry in catalog["apps"]]
    if len(packages) != len(set(packages)):
        raise PublishError("Duplicate packages in the destination catalog")
    return catalog, document["sha"]


def upsert_catalog(catalog, app):
    updated = copy.deepcopy(catalog)
    existing = next((entry for entry in updated["apps"]
                     if entry["packageName"] == app["packageName"]), None)
    if existing is not None:
        if app["versionCode"] < existing["versionCode"]:
            return updated, False
        if app["versionCode"] == existing["versionCode"] and app["sha256"] != existing["sha256"]:
            raise PublishError("This versionCode already identifies a different APK; increase versionCode")
        certificate = existing.get("signingCertificateSha256")
        if certificate and certificate != app["signingCertificateSha256"]:
            raise PublishError("APK signing certificate changed; review signing before publishing")
        replacement = dict(existing, **app)
        if replacement == existing:
            return updated, False
        updated["apps"][updated["apps"].index(existing)] = replacement
    else:
        updated["apps"].append(app)
    return updated, True


def write_catalog(github, repository, branch, app):
    for attempt in range(3):
        catalog, sha = read_catalog(github, repository, branch)
        updated, changed = upsert_catalog(catalog, app)
        if not changed:
            return False
        content = json.dumps(updated, indent=2, ensure_ascii=False) + "\n"
        try:
            github.request("PUT", "/repos/{}/contents/{}".format(repository, CATALOG_PATH), {
                "message": "Update {} to {} (build {})".format(
                    app["name"], app["versionName"], app["versionCode"]),
                "content": base64.b64encode(content.encode()).decode(),
                "sha": sha, "branch": branch,
            })
            return True
        except HTTPError as error:
            if error.code != 409 or attempt == 2:
                raise
    return False


def mirror_release(github, repository, app_config, metadata, source_release, apk, digest):
    tag = "{}-{}".format(app_config["id"], metadata["versionCode"])
    prefix = "/repos/" + repository
    release = github.request("GET", prefix + "/releases/tags/" + quote(tag, safe=""), allow_missing=True)
    if release is None:
        release = github.request("POST", prefix + "/releases", {
            "tag_name": tag, "name": app_config["name"] + " " + metadata["versionName"],
            "body": source_release.get("body") or "", "draft": True,
            "prerelease": False, "make_latest": "false",
        })
    filename = "{}-{}.apk".format(app_config["id"], metadata["versionCode"])
    asset = next((item for item in release.get("assets", []) if item["name"] == filename), None)
    if asset is None:
        asset = github.upload(release["upload_url"], apk, filename)
    else:
        # Do not replace an existing binary on a retry or reuse a version code.
        existing_digest = asset.get("digest")
        if existing_digest != "sha256:" + digest:
            with tempfile.TemporaryDirectory(prefix=".nikstore-verify-", dir=Path.cwd()) as directory:
                previous = Path(directory) / "previous.apk"
                github.download(asset["id"], repository, previous)
                if sha256(previous) != digest:
                    raise PublishError("The mirrored version already contains a different APK")
    if release.get("draft"):
        github.request("PATCH", prefix + "/releases/" + str(release["id"]),
                       {"draft": False, "make_latest": "false"})
    return asset["browser_download_url"]


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def publish(source, destination, source_repository, destination_repository, release, config,
            asset_name="", inspect=None):
    asset = select_asset(release, asset_name)
    repository_info = destination.request("GET", "/repos/" + destination_repository)
    if repository_info.get("private"):
        raise PublishError("NikStore's APK distribution repository must be public")
    branch = repository_info["default_branch"]
    with tempfile.TemporaryDirectory(prefix=".nikstore-release-", dir=Path.cwd()) as directory:
        apk = Path(directory) / "release.apk"
        source.download(asset["id"], source_repository, apk)
        if inspect is None:
            metadata = parse_apk_metadata(
                tool_output("aapt", "dump", "badging", apk),
                tool_output("apksigner", "verify", "--verbose", "--print-certs", apk),
                config["packageName"])
        else:
            metadata = inspect(apk)
        digest = sha256(apk)
        source_digest = asset.get("digest")
        if source_digest and source_digest != "sha256:" + digest:
            raise PublishError("Downloaded APK does not match GitHub's release asset checksum")
        app = dict(metadata, name=config["name"], description=config["description"],
                   apkUrl="pending", sha256=digest, releaseNotes=release.get("body") or "",
                   publishedAt=release["published_at"], sizeBytes=apk.stat().st_size)
        catalog, _ = read_catalog(destination, destination_repository, branch)
        # Validate versions and signing before making the downloaded APK public.
        upsert_catalog(catalog, app)
        existing = next((entry for entry in catalog["apps"]
                         if entry["packageName"] == app["packageName"]), None)
        if existing and existing["versionCode"] > app["versionCode"]:
            print("Skipped an older release; catalog already has a newer version.")
            return
        app["apkUrl"] = mirror_release(destination, destination_repository, config, metadata,
                                       release, apk, digest)
        changed = write_catalog(destination, destination_repository, branch, app)
        print("{} {}: {}".format(app["name"], app["versionName"],
                                "catalog updated" if changed else "already published"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", default="emsNikac/NikStore")
    parser.add_argument("--event", default=os.environ.get("GITHUB_EVENT_PATH"))
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.destination):
        raise PublishError("Invalid destination repository")
    if not args.event:
        raise PublishError("Run this publisher from the source repository's release workflow")
    event = json.loads(Path(args.event).read_text())
    repository = event["repository"]["full_name"]
    registry = json.loads((ROOT / "integrations/apps.json").read_text())
    if repository not in registry:
        raise PublishError("Source repository is not registered in integrations/apps.json")
    source = GitHub(os.environ.get("SOURCE_GITHUB_TOKEN"))
    destination = GitHub(os.environ.get("NIKSTORE_TOKEN"))
    if event.get("release"):
        endpoint = "/repos/{}/releases/{}".format(repository, int(event["release"]["id"]))
    else:
        tag = event.get("inputs", {}).get("release_tag", "").strip()
        if not tag:
            raise PublishError("Choose the source GitHub release tag to publish")
        endpoint = "/repos/{}/releases/tags/{}".format(repository, quote(tag, safe=""))
    release = source.request("GET", endpoint)
    publish(source, destination, repository, args.destination, release, registry[repository],
            os.environ.get("NIKSTORE_APK_ASSET", ""))


if __name__ == "__main__":
    try:
        main()
    except HTTPError as error:
        print("GitHub request failed (HTTP {}). Check repository permissions and release state."
              .format(error.code), file=sys.stderr)
        sys.exit(1)
    except (PublishError, ValueError, KeyError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
