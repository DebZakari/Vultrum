import contextlib
import io
import unittest
from unittest.mock import patch

import sync_modrinth_project as sync
from publish_modrinth import PublishError


class ProjectPageTest(unittest.TestCase):
    def setUp(self):
        gallery = patch.object(sync, "desired_gallery", return_value=[])
        gallery.start()
        self.addCleanup(gallery.stop)

    def project(self):
        return {"id": "LV34xpJ5", "title": "Vultrum", "project_type": "mod",
                "description": "old", "body": "old", "status": "processing", "requested_status": "approved",
                "slug": "vultrum", "versions": ["existing"], "gallery": [{"url": "existing-image"}], "icon_url": "icon"}

    def test_preview_does_not_write_and_excludes_moderation_fields(self):
        with patch.object(sync, "request", return_value=self.project()) as request, \
                patch.object(sync, "desired_fields", return_value={"description": "new", "body": "new"}), \
                contextlib.redirect_stdout(io.StringIO()) as output:
            sync.synchronize("LV34xpJ5", "credential")
        self.assertEqual(request.call_count, 1)
        self.assertIn('PROJECT_PATCH_JSON={"description":"new","body":"new"}', output.getvalue())
        self.assertNotIn("credential", output.getvalue())

    def test_audit_reads_version_metadata_without_writes(self):
        with patch.object(sync, "request", side_effect=[self.project(), [{"id": "version", "dependencies": []}]]) as request, \
                contextlib.redirect_stdout(io.StringIO()) as output:
            sync.synchronize("LV34xpJ5", "credential", audit=True)
        self.assertEqual(request.call_count, 2)
        self.assertTrue(all(not call.kwargs for call in request.call_args_list))
        self.assertIn('VERSIONS_JSON=[{"id":"version"', output.getvalue())

    def test_apply_only_changed_fields_then_verifies_stored_values(self):
        before = self.project()
        after = before | {"body": "new"}
        with patch.object(sync, "request", side_effect=[before, None, after]) as request, \
                patch.object(sync, "desired_fields", return_value={"description": "old", "body": "new"}), \
                contextlib.redirect_stdout(io.StringIO()):
            sync.synchronize("LV34xpJ5", "credential", apply=True)
        self.assertEqual(request.call_args_list[1].kwargs, {"method": "PATCH", "data": {"body": "new"}})

    def test_does_not_write_when_page_is_current(self):
        with patch.object(sync, "request", return_value=self.project()) as request, \
                patch.object(sync, "desired_fields", return_value={"body": "old"}), \
                contextlib.redirect_stdout(io.StringIO()):
            sync.synchronize("LV34xpJ5", "credential", apply=True)
        self.assertEqual(request.call_count, 2)
        self.assertTrue(all(not call.kwargs for call in request.call_args_list))

    def test_rejects_another_project_before_writing(self):
        with patch.object(sync, "request", return_value=self.project() | {"title": "Another mod"}) as request:
            with self.assertRaises(PublishError):
                sync.synchronize("LV34xpJ5", "credential", apply=True)
        self.assertEqual(request.call_count, 1)

    def test_fails_if_moderation_changes_during_apply(self):
        before = self.project()
        with patch.object(sync, "request", side_effect=[before, None, before | {"body": "new", "status": "draft"}]), \
                patch.object(sync, "desired_fields", return_value={"body": "new"}), \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(PublishError, "status changed"):
                sync.synchronize("LV34xpJ5", "credential", apply=True)

    def test_actual_readme_becomes_current_modrinth_markdown(self):
        body = sync.desired_fields()["body"]
        self.assertIn("0.13.0-beta.vultrum.8", body)
        self.assertIn("Display Shadow Limit", body)
        self.assertIn("Sleep-Trails/releases/tag/v0.1.0-alpha.8", body)
        self.assertNotIn("<picture>", body)
        self.assertNotIn("](LICENSE)", body)
        self.assertNotIn("## Building", body)
        self.assertIn(sync.RAW + "docs/images/fps.png", body)
        self.assertIn(sync.WEB + "/blob/main/LICENSE", body)

    def test_gallery_updates_only_the_exact_existing_image_caption(self):
        current = [{"url": "preview", "raw_url": "original", "title": "old", "featured": True, "ordering": 0}]
        self.assertEqual(sync.gallery_changes(current, [{"raw_url": "original", "title": "new"}]),
                         [{"url": "original", "title": "new"}])
        self.assertEqual(sync.gallery_changes(current, [{"raw_url": "original", "title": "old"}]), [])

    def test_missing_gallery_image_stops_before_writes(self):
        with patch.object(sync, "request", return_value=self.project()) as request, \
                patch.object(sync, "desired_fields", return_value={"body": "new"}), \
                patch.object(sync, "desired_gallery", return_value=[{"raw_url": "missing", "title": "new"}]), \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(PublishError):
                sync.synchronize("LV34xpJ5", "credential", apply=True)
        self.assertEqual(request.call_count, 1)


if __name__ == "__main__":
    unittest.main()
