import io
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch, Mock
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from artifacts import ASSETS, FILES, Digest, ReadVersion, Validate
from release import Allowed, Main, Publish, ReleaseError, GitHub
from urllib.error import HTTPError

SHA = "1" * 40
OLD = "0" * 40


def Jar(version, modern=False, suffix=b""):
    output = io.BytesIO()
    runtime = "jdk21" if modern else "jdk8"
    main = "com.dalae37.logreedy.runtime." + runtime + ".Main"
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nMain-Class: " + main + "\r\nImplementation-Version: " + version + "\r\n\r\n")
        archive.writestr("logreedy-version.properties", "version=" + version + "\n")
        for path, major in ((main.replace(".", "/"), 65 if modern else 52), ("com/dalae37/logreedy/Cli", 52)):
            archive.writestr(path + ".class", struct.pack(">IHH", 0xCAFEBABE, 0, major) + suffix)
    return output.getvalue()


def Files(version="0.1.0", suffix=b""):
    files = {FILES[0]: Jar(version, suffix=suffix), FILES[1]: Jar(version, True, suffix), FILES[2]: b"sources=app\n" + suffix}
    files["SHA256SUMS"] = "".join(Digest(files[name]) + "  " + name + "\n" for name in FILES).encode()
    return files


class API:
    def __init__(self):
        self.tags, self.release, self.assets = {}, None, {}
        self.main, self.history = SHA, {SHA, OLD}
        self.latest = None
        self.writes, self.uploads = [], []
        self.fail_asset = None
        self.move_tag_on_download = False

    def FindRelease(self, tag):
        return self.release if self.release and self.release["tag_name"] == tag else None

    def Tag(self, tag):
        return self.tags.get(tag)

    def Main(self):
        return self.main

    def OnMain(self, sha):
        return sha in self.history

    def Latest(self):
        return self.latest

    def CreateTag(self, tag, sha):
        if tag in self.tags:
            raise ReleaseError("tag already exists")
        self.tags[tag] = sha
        self.writes.append("tag")

    def CreateDraft(self, tag, sha, name, body):
        self.release = dict(id=1, draft=True, name=name, tag_name=tag, target_commitish=sha, body=body)
        self.writes.append("draft")
        return self.release

    def Assets(self, release):
        return [dict(id=name, name=name, size=len(data), state="uploaded", digest="sha256:" + Digest(data)) for name, data in self.assets.items()]

    def Upload(self, release, name, data):
        if name in self.assets:
            raise AssertionError("Must not overwrite assets")
        if name == self.fail_asset:
            self.fail_asset = None
            raise ReleaseError("Interrupted upload")
        self.assets[name] = data
        self.uploads.append(name)

    def Download(self, asset):
        if self.move_tag_on_download:
            self.tags["v0.1.0"] = OLD
        return self.assets[asset["name"]]

    def Publish(self, release):
        if set(self.assets) != set(ASSETS):
            raise AssertionError("Published before all assets")
        release["draft"] = False
        self.writes.append("publish")


class ReleaseTest(unittest.TestCase):
    def test_new_version_publishes_exact_commit_after_four_verified_assets(self):
        api = API()
        self.assertIn("Published", Publish(api, "0.1.0", SHA, Files(), "notes"))
        self.assertEqual(api.writes, ["tag", "draft", "publish"])
        self.assertEqual(api.tags["v0.1.0"], SHA)
        self.assertEqual(set(api.assets), set(ASSETS))
        self.assertFalse(api.release["draft"])

    def test_same_version_dependency_change_does_not_republish(self):
        api = API()
        Publish(api, "0.1.0", SHA, Files(suffix=b"previous"), "notes")
        api.main = "2" * 40
        api.history.add(api.main)
        before = list(api.writes), dict(api.assets)
        self.assertIn("skipped", Publish(api, "0.1.0", api.main, Files(suffix=b"dependency update"), "notes"))
        self.assertEqual(before, (api.writes, api.assets))

    def test_tag_only_resumes_without_moving_tag(self):
        api = API()
        api.tags["v0.1.0"] = SHA
        Publish(api, "0.1.0", SHA, Files(), "notes")
        self.assertEqual(api.writes, ["draft", "publish"])

    def test_tag_conflict_never_writes(self):
        api = API()
        api.tags["v0.1.0"] = OLD
        with self.assertRaisesRegex(ReleaseError, "Tag conflict"):
            Publish(api, "0.1.0", SHA, Files(), "notes")
        self.assertEqual(api.writes, [])

    def test_interrupted_upload_resumes_missing_assets_only(self):
        api = API()
        api.fail_asset = FILES[1]
        with self.assertRaisesRegex(ReleaseError, "Interrupted"):
            Publish(api, "0.1.0", SHA, Files(), "notes")
        self.assertTrue(api.release["draft"])
        self.assertEqual(api.uploads, [FILES[0]])
        Publish(api, "0.1.0", SHA, Files(), "notes")
        self.assertEqual(api.uploads, list(ASSETS))
        self.assertEqual(api.writes.count("tag"), 1)

    def test_corrupt_draft_asset_stays_draft_without_overwrite(self):
        api = API()
        api.fail_asset = FILES[1]
        with self.assertRaises(ReleaseError):
            Publish(api, "0.1.0", SHA, Files(), "notes")
        api.assets[FILES[0]] = b"damaged"
        with self.assertRaisesRegex(ReleaseError, "never overwriting"):
            Publish(api, "0.1.0", SHA, Files(), "notes")
        self.assertTrue(api.release["draft"])
        self.assertEqual(api.assets[FILES[0]], b"damaged")

    def test_existing_published_incomplete_release_is_not_overwritten(self):
        api = API()
        Publish(api, "0.1.0", SHA, Files(), "notes")
        del api.assets["SHA256SUMS"]
        before = list(api.writes)
        with self.assertRaisesRegex(ReleaseError, "incomplete"):
            Publish(api, "0.1.0", SHA, Files(), "notes")
        self.assertEqual(api.writes, before)

    def test_unowned_draft_is_not_adopted(self):
        api = API()
        api.release = dict(id=1, draft=True, name="LoGreedy v0.1.0", tag_name="v0.1.0", target_commitish=SHA, body="manual draft")
        with self.assertRaisesRegex(ReleaseError, "Unowned"):
            Publish(api, "0.1.0", SHA, Files(), "notes")
        self.assertEqual(api.writes, [])

    def test_moved_tag_during_upload_prevents_publication(self):
        api = API()
        api.move_tag_on_download = True
        with self.assertRaisesRegex(ReleaseError, "Tag changed"):
            Publish(api, "0.1.0", SHA, Files(), "notes")
        self.assertTrue(api.release["draft"])

    def test_stale_or_non_main_commit_never_creates_tag(self):
        for mode in ("stale", "outside"):
            api = API()
            if mode == "stale":
                api.main = OLD
            else:
                api.history = {OLD}
            with self.assertRaises(ReleaseError):
                Publish(api, "0.1.0", SHA, Files(), "notes")
            self.assertEqual(api.writes, [])

    def test_new_versions_work_and_downgrades_stop(self):
        for version in ("0.1.1", "0.2.0", "1.0.0"):
            api = API()
            api.latest = {"tag_name": "v0.1.0"}
            Publish(api, version, SHA, Files(version), "notes")
            self.assertIn("v" + version, api.tags)
        api = API()
        api.latest = {"tag_name": "v1.0.0"}
        with self.assertRaisesRegex(ReleaseError, "must increase"):
            Publish(api, "0.1.0", SHA, Files(), "notes")
        self.assertEqual(api.writes, [])

    def test_release_guard_rejects_pr_dispatch_branch_and_local(self):
        for event, ref, actions in (("pull_request", "refs/heads/main", "true"), ("workflow_dispatch", "refs/heads/main", "true"),
                                    ("push", "refs/heads/feature/project-setup", "true"), ("push", "refs/heads/main", "false")):
            environment = dict(GITHUB_EVENT_NAME=event, GITHUB_REF=ref, GITHUB_ACTIONS=actions)
            self.assertFalse(Allowed(environment))
            with patch.dict("os.environ", environment, clear=True), patch("release.GitHub") as constructor:
                Main()
                constructor.assert_not_called()
        self.assertTrue(Allowed(dict(GITHUB_EVENT_NAME="push", GITHUB_REF="refs/heads/main", GITHUB_ACTIONS="true")))

    def test_download_redirect_does_not_forward_authorization(self):
        api = GitHub("DaLae37/LoGreedy", "test-only-token")
        response = io.BytesIO(b"asset")
        api.opener.open = Mock(side_effect=[HTTPError("https://api.github.com", 302, "Found", {"Location": "https://release-assets.githubusercontent.com/signed"}, None), response])
        self.assertEqual(api.Download({"id": 1}), b"asset")
        redirected = api.opener.open.call_args_list[1].args[0]
        self.assertFalse(redirected.has_header("Authorization"))

    def test_untrusted_asset_redirect_is_rejected(self):
        api = GitHub("DaLae37/LoGreedy", "test-only-token")
        api.opener.open = Mock(side_effect=HTTPError("https://api.github.com", 302, "Found", {"Location": "https://untrusted.example/asset"}, None))
        with self.assertRaisesRegex(ReleaseError, "Unsafe"):
            api.Download({"id": 1})
        self.assertEqual(api.opener.open.call_count, 1)

    def test_invalid_or_duplicate_project_version_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for value in ("version=01.0.0", "version=v0.1.0", "version=0.1.0-rc1", "version=0.1.0\nversion=0.2.0"):
                (root / "gradle.properties").write_text(value)
                with self.assertRaises(ValueError):
                    ReadVersion(root)

    def test_actual_files_checksum_tamper_and_runtime_dependency_boundary(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, data in Files().items():
                (root / name).write_bytes(data)
            Validate(root, "0.1.0")
            (root / FILES[2]).write_bytes(b"modified")
            with self.assertRaisesRegex(ValueError, "SHA256SUMS"):
                Validate(root, "0.1.0")
            Validate(root, "0.1.0", prepare=True)
            with zipfile.ZipFile(root / FILES[0], "a") as archive:
                archive.writestr("thirdparty/Unsafe.class", struct.pack(">IHH", 0xCAFEBABE, 0, 65))
            with self.assertRaisesRegex(ValueError, "Unreviewed"):
                Validate(root, "0.1.0", prepare=True)


if __name__ == "__main__":
    unittest.main()
