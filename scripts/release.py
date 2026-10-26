"""Publish only verified main pushes; never overwrite tags, releases or assets."""
import json
import os
from pathlib import Path
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

from artifacts import ASSETS, FILES, ROOT, Digest, ReadVersion, Validate, CheckJar


class ReleaseError(RuntimeError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, newurl):
        return None


class GitHub:
    def __init__(self, repository, token):
        self.base = "https://api.github.com/repos/" + repository
        self.repository = repository
        self.token = token
        self.opener = build_opener(NoRedirect())

    def Request(self, method, path, data=None, binary=False, missing=False, upload=False):
        url = path if upload else self.base + path
        parsed = urlparse(url)
        if parsed.scheme != "https" or parsed.hostname != ("uploads.github.com" if upload else "api.github.com"):
            raise ReleaseError("Refusing untrusted GitHub endpoint")
        body = data if binary else (json.dumps(data).encode() if data is not None else None)
        headers = {"Authorization": "Bearer " + self.token, "User-Agent": "LoGreedy-release",
                   "X-GitHub-Api-Version": "2022-11-28", "Accept": "application/vnd.github+json"}
        if body is not None:
            headers["Content-Type"] = "application/octet-stream" if binary else "application/json"
        try:
            with self.opener.open(Request(url, body, headers, method=method), timeout=60) as response:
                return json.load(response)
        except HTTPError as error:
            if missing and error.code == 404:
                return None
            raise ReleaseError("GitHub API HTTP " + str(error.code) + "; retry the original run after diagnosis") from None
        except URLError:
            raise ReleaseError("GitHub API connection failure; preserve draft/tag and retry original run") from None

    def All(self, path):
        records = []
        for page in range(1, 101):
            batch = self.Request("GET", path + "?per_page=100&page=" + str(page))
            records.extend(batch)
            if len(batch) < 100:
                return records
        raise ReleaseError("GitHub pagination incomplete; refusing ambiguous release state")

    def FindRelease(self, tag):
        published = self.Request("GET", "/releases/tags/" + tag, missing=True)
        if published is not None:
            return published
        matches = [release for release in self.All("/releases") if release["tag_name"] == tag]
        if len(matches) > 1:
            raise ReleaseError("Multiple drafts use the same tag; manual review required")
        return matches[0] if matches else None

    def Tag(self, tag):
        record = self.Request("GET", "/git/ref/tags/" + tag, missing=True)
        if record is None:
            return None
        target = record["object"]
        for unused in range(8):
            if target["type"] == "commit":
                return target["sha"]
            if target["type"] != "tag":
                break
            target = self.Request("GET", "/git/tags/" + target["sha"])["object"]
        raise ReleaseError("Tag does not resolve to a commit")

    def Main(self):
        return self.Request("GET", "/git/ref/heads/main")["object"]["sha"]

    def OnMain(self, sha):
        comparison = self.Request("GET", "/compare/" + sha + "...main")
        return comparison["status"] in ("ahead", "identical") and comparison["merge_base_commit"]["sha"] == sha

    def Latest(self):
        return self.Request("GET", "/releases/latest", missing=True)

    def CreateTag(self, tag, sha):
        self.Request("POST", "/git/refs", {"ref": "refs/tags/" + tag, "sha": sha})

    def CreateDraft(self, tag, sha, name, body):
        return self.Request("POST", "/releases", {"tag_name": tag, "target_commitish": sha,
                            "name": name, "body": body, "draft": True, "prerelease": False})

    def Assets(self, release):
        return self.All("/releases/" + str(release["id"]) + "/assets")

    def Upload(self, release, name, data):
        # Construct the official destination; do not trust an arbitrary upload_url from JSON.
        url = "https://uploads.github.com/repos/" + self.repository + "/releases/" + str(release["id"]) + "/assets?name=" + quote(name)
        return self.Request("POST", url, data, binary=True, upload=True)

    def Download(self, asset):
        request = Request(self.base + "/releases/assets/" + str(asset["id"]), headers={
            "Authorization": "Bearer " + self.token, "Accept": "application/octet-stream",
            "User-Agent": "LoGreedy-release", "X-GitHub-Api-Version": "2022-11-28"})
        try:
            response = self.opener.open(request, timeout=60)
        except HTTPError as error:
            if error.code not in (301, 302, 303, 307, 308):
                raise ReleaseError("Cannot download release asset; HTTP " + str(error.code)) from None
            url = error.headers.get("Location", "")
            parsed = urlparse(url)
            if parsed.scheme != "https" or parsed.hostname not in ("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github.com"):
                raise ReleaseError("Unsafe asset download redirect")
            # Deliberately no Authorization on the signed download URL.
            response = self.opener.open(Request(url, headers={"User-Agent": "LoGreedy-release"}), timeout=60)
        with response:
            data = response.read(67108865)
        if len(data) > 67108864:
            raise ReleaseError("Release asset exceeds size limit")
        return data

    def Publish(self, release):
        self.Request("PATCH", "/releases/" + str(release["id"]), {"draft": False, "make_latest": "true"})


def AssetMap(api, release):
    result = {}
    for asset in api.Assets(release):
        name = asset["name"]
        if name in result:
            raise ReleaseError("Duplicate release asset: " + name)
        result[name] = asset
    return result


def VerifyRemote(api, assets, expected):
    for name in ASSETS:
        if name not in assets or assets[name].get("state") != "uploaded":
            raise ReleaseError("Missing/incomplete asset: " + name)
        actual = api.Download(assets[name])
        if actual != expected[name] or assets[name]["size"] != len(actual):
            raise ReleaseError("Existing asset differs; never overwriting: " + name)
        digest = assets[name].get("digest")
        if digest and digest != "sha256:" + Digest(actual):
            raise ReleaseError("GitHub asset digest mismatch: " + name)


def Publish(api, version, sha, files, notes):
    tag, name = "v" + version, "LoGreedy v" + version
    marker = "<!-- logreedy-release commit=" + sha + " -->"
    release, target = api.FindRelease(tag), api.Tag(tag)
    if release is not None and not release["draft"]:
        if target is None or not api.OnMain(target):
            raise ReleaseError("Published release tag is missing or outside main history")
        # Same-version dependency updates must not replace the previous distribution.
        assets = AssetMap(api, release)
        if not all(asset in assets for asset in ASSETS):
            raise ReleaseError("Published release is incomplete; manual repair required (no overwrite)")
        previous = {asset: api.Download(assets[asset]) for asset in ASSETS}
        checksums = "".join(Digest(previous[asset]) + "  " + asset + "\n" for asset in FILES).encode()
        if previous["SHA256SUMS"] != checksums:
            raise ReleaseError("Published release checksum mismatch; manual review required")
        CheckJar(previous[FILES[0]], version, False)
        CheckJar(previous[FILES[1]], version, True)
        VerifyRemote(api, assets, previous)
        return "Already published " + tag + "; unchanged version skipped"
    if target is not None and target != sha:
        raise ReleaseError("Tag conflict: rerun CI for the exact tagged commit; never move the tag")
    if release is not None and (release["name"] != name or release.get("target_commitish") != sha or marker not in release.get("body", "")):
        raise ReleaseError("Unowned/mismatched draft; manual review required")
    if not api.OnMain(sha):
        raise ReleaseError("Verified commit is outside main history")
    if target is None and release is None and api.Main() != sha:
        raise ReleaseError("Newer main commit exists; use its successful CI instead")
    latest = api.Latest()
    if latest is not None:
        previous = latest["tag_name"]
        if not re.fullmatch(r"v(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)", previous):
            raise ReleaseError("Latest release has an unrecognized version; manual review required")
        if tuple(map(int, version.split("."))) <= tuple(map(int, previous[1:].split("."))):
            raise ReleaseError("Version must increase beyond the latest published release")
    if target is None:
        api.CreateTag(tag, sha)
    if release is None:
        release = api.CreateDraft(tag, sha, name, notes + "\n\n" + marker)
    assets = AssetMap(api, release)
    for asset in ASSETS:
        if asset not in assets:
            api.Upload(release, asset, files[asset])
    VerifyRemote(api, AssetMap(api, release), files)
    # Check the ref again immediately before publication; do not publish moved tags.
    if api.Tag(tag) != sha:
        raise ReleaseError("Tag changed during upload; refusing publication")
    api.Publish(release)
    return "Published " + name + " at " + sha


def Allowed(environment):
    return environment.get("GITHUB_EVENT_NAME") == "push" and environment.get("GITHUB_REF") == "refs/heads/main" and environment.get("GITHUB_ACTIONS") == "true"


def Main():
    if not Allowed(os.environ):
        print("Publish skipped: only a verified GitHub main push may publish")
        return
    repository, sha = os.environ.get("GITHUB_REPOSITORY", ""), os.environ.get("GITHUB_SHA", "")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) or not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ReleaseError("Invalid GitHub repository/commit context")
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        raise ReleaseError("Default GITHUB_TOKEN is required in the publish job")
    version = ReadVersion()
    files = Validate(ROOT / "build/distributions/all", version)
    notes = (ROOT / "docs/RELEASE_NOTES.md").read_text(encoding="utf-8").replace("{{VERSION}}", version)
    print(Publish(GitHub(repository, token), version, sha, files, notes))


if __name__ == "__main__":
    try:
        Main()
    except (ReleaseError, ValueError, OSError) as error:
        print("Release stopped: " + str(error), file=sys.stderr)
        sys.exit(1)
