"""Checks for release identity, retry safety and project status guards."""

import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import publish_modrinth as publisher


class PublisherTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.tag = "v0.1.0-alpha.3"
        self.jar = Path(self.directory.name) / "vultrum-0.1.0-alpha.3+mc26.3.jar"
        self.mod = {"id": "vultrum", "version": "0.1.0-alpha.3+mc26.3",
                    "environment": "client", "depends": {"minecraft": "26.3"}}
        self.write_jar()
        self.release = {"tag_name": self.tag, "draft": False, "published_at": "2026-10-13T00:00:00Z",
                        "body": "Terrain reload improvements.\n", "assets": [self.asset]}
        self.dependencies = [{"project_id": "AABBCCDD", "dependency_type": "required"}]

    def write_jar(self):
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("fabric.mod.json", json.dumps(self.mod))
        content = self.jar.read_bytes()
        self.asset = {"name": self.jar.name, "size": len(content),
                      "digest": "sha256:" + hashlib.sha256(content).hexdigest()}

    def payload(self):
        return publisher.metadata(self.release, self.tag, self.asset, self.jar, "LV34xpJ5", self.dependencies)

    def stored(self):
        payload, digest = self.payload()
        return {**payload, "id": "12345678", "files": [{"primary": True, "hashes": {"sha512": digest}}]}

    def test_alpha_channel_comes_from_version_not_github_prerelease(self):
        self.release["prerelease"] = False
        payload, _ = self.payload()
        self.assertEqual(payload["version_type"], "alpha")
        self.assertEqual(payload["environment"], "client_only")
        self.assertEqual(payload["game_versions"], ["26.3"])
        self.assertEqual(payload["changelog"], self.release["body"])
        self.assertEqual(payload["dependencies"], self.dependencies)

    def test_draft_and_missing_notes_are_rejected(self):
        for field, value in (("draft", True), ("published_at", None), ("body", "")):
            with self.subTest(field=field):
                release = {**self.release, field: value}
                with self.assertRaises(publisher.PublishError):
                    publisher.release_asset(release, self.tag)

    def test_ambiguous_jars_and_missing_digest_are_rejected(self):
        release = {**self.release, "assets": [self.asset, self.asset]}
        with self.assertRaises(publisher.PublishError):
            publisher.release_asset(release, self.tag)
        release["assets"] = [{**self.asset, "digest": None}]
        with self.assertRaises(publisher.PublishError):
            publisher.release_asset(release, self.tag)

    def test_changed_download_is_rejected(self):
        self.jar.write_bytes(self.jar.read_bytes() + b"changed")
        with self.assertRaises(publisher.PublishError):
            self.payload()

    def test_wrong_mod_or_version_or_environment_is_rejected(self):
        for field, value in (("id", "another-mod"), ("version", "0.1.0-alpha.2+mc26.3"),
                             ("environment", "*"), ("depends", {"minecraft": ">=26.3"})):
            with self.subTest(field=field):
                old = copy.deepcopy(self.mod)
                self.mod[field] = value
                self.write_jar()
                with self.assertRaises(publisher.PublishError):
                    self.payload()
                self.mod = old

    def test_identical_existing_version_is_a_noop(self):
        payload, digest = self.payload()
        stored = self.stored()
        self.assertIs(publisher.matching_version([stored], payload, digest), stored)

    def test_conflicting_hash_metadata_or_duplicate_is_rejected(self):
        payload, digest = self.payload()
        stored = self.stored()
        changed = copy.deepcopy(stored)
        changed["files"][0]["hashes"]["sha512"] = "different"
        for versions in ([changed], [{**stored, "loaders": ["forge"]}],
                         [{**stored, "dependencies": []}], [stored, stored]):
            with self.subTest(versions=versions), self.assertRaises(publisher.PublishError):
                publisher.matching_version(versions, payload, digest)

    def test_multipart_has_one_json_part_and_exact_jar_bytes(self):
        payload, _ = self.payload()
        body, content_type = publisher.multipart(payload, self.jar)
        self.assertIn(b'name="data"', body)
        self.assertIn(b'name="jar"', body)
        self.assertIn(self.jar.read_bytes(), body)
        self.assertTrue(body.endswith(("--" + content_type.split("boundary=")[1] + "--\r\n").encode()))

    def mock_services(self, status="approved", existing=False, corrupt_upload=False):
        def gh(*args):
            if args[0] == "api":
                return json.dumps(self.release)
            Path(args[-1], self.jar.name).write_bytes(self.jar.read_bytes())
            return ""

        def modrinth(path, token, data=None, content_type=None):
            stored = self.stored()
            stored["dependencies"] = [{"project_id": "AABBCCDD", "dependency_type": kind}
                                      for kind in publisher.DEPENDENCIES.values()]
            if path == "/project/LV34xpJ5":
                return {"id": "LV34xpJ5", "title": "Vultrum", "project_type": "mod", "status": status}
            if path.startswith("/project/LV34xpJ5/version"):
                others = [{"id": f"old-{i}", "version_number": f"older-{i}"} for i in range(100)]
                return ([stored] if existing else []) if "offset=100" in path else others
            if path.startswith("/project/"):
                return {"id": "AABBCCDD"}
            if path == "/version":
                return {"id": "12345678"}
            if path == "/version/12345678":
                return {**stored, "loaders": ["forge"]} if corrupt_upload else stored
            self.fail(f"Unexpected API request {path}")

        return gh, modrinth

    def test_other_project_states_perform_no_download_or_upload(self):
        for status in ("draft", "rejected", "withheld", "archived", "unlisted", "scheduled", "unknown", None):
            with self.subTest(status=status):
                gh, api = self.mock_services(status=status)
                with patch.object(publisher, "gh", side_effect=gh) as github, \
                        patch.object(publisher, "modrinth", side_effect=api) as modrinth:
                    publisher.mirror(self.tag, "LV34xpJ5", "test-token", False)
                self.assertEqual(github.call_count, 1)
                self.assertEqual(modrinth.call_count, 1)

    def test_under_review_uploads_and_verifies_without_modifying_project(self):
        gh, api = self.mock_services(status="processing")
        with patch.object(publisher, "gh", side_effect=gh), \
                patch.object(publisher, "modrinth", side_effect=api) as modrinth:
            publisher.mirror(self.tag, "LV34xpJ5", "test-token", False)
        writes = [call.args[0] for call in modrinth.call_args_list if len(call.args) > 2]
        self.assertEqual(writes, ["/version"])
        self.assertEqual(modrinth.call_args_list[-1].args[0], "/version/12345678")

    def test_dry_run_performs_no_upload(self):
        gh, api = self.mock_services()
        with patch.object(publisher, "gh", side_effect=gh), patch.object(publisher, "modrinth", side_effect=api) as modrinth:
            publisher.mirror(self.tag, "LV34xpJ5", "test-token", True)
        self.assertFalse(any(call.args[0] == "/version" for call in modrinth.call_args_list))

    def test_retry_after_success_performs_no_second_upload(self):
        gh, api = self.mock_services(existing=True)
        with patch.object(publisher, "gh", side_effect=gh), patch.object(publisher, "modrinth", side_effect=api) as modrinth:
            publisher.mirror(self.tag, "LV34xpJ5", "test-token", False)
        self.assertFalse(any(call.args[0] == "/version" for call in modrinth.call_args_list))

    def test_upload_verifies_stored_hash_and_metadata(self):
        gh, api = self.mock_services()
        with patch.object(publisher, "gh", side_effect=gh), patch.object(publisher, "modrinth", side_effect=api) as modrinth:
            publisher.mirror(self.tag, "LV34xpJ5", "test-token", False)
        self.assertEqual(sum(call.args[0] == "/version" for call in modrinth.call_args_list), 1)
        self.assertEqual(modrinth.call_args_list[-1].args[0], "/version/12345678")

    def test_bad_stored_upload_fails_instead_of_reporting_success(self):
        gh, api = self.mock_services(corrupt_upload=True)
        with patch.object(publisher, "gh", side_effect=gh), patch.object(publisher, "modrinth", side_effect=api), \
                self.assertRaises(publisher.PublishError):
            publisher.mirror(self.tag, "LV34xpJ5", "test-token", False)

    def test_invalid_tag_id_or_missing_token_fail_before_network(self):
        for tag, project, token in (("v1;command", "LV34xpJ5", "test"),
                                    (self.tag, "../wrong", "test"), (self.tag, "LV34xpJ5", "")):
            with patch.object(publisher, "gh") as github, self.assertRaises(publisher.PublishError):
                publisher.mirror(tag, project, token, False)
            github.assert_not_called()

    def test_authenticated_requests_do_not_redirect(self):
        self.assertIsNone(publisher.NoRedirect().redirect_request(None, None, 302, "", {}, "https://elsewhere.invalid"))

    def test_repeated_page_fails_before_upload(self):
        page = [{"id": str(i)} for i in range(100)]
        with patch.object(publisher, "modrinth", return_value=page), self.assertRaises(publisher.PublishError):
            publisher.project_versions("LV34xpJ5", "test")


if __name__ == "__main__":
    unittest.main()
