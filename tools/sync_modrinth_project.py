"""Audit or synchronize the Modrinth page from the repository's player documentation."""

import argparse
import html
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.request
from urllib.parse import urlencode

from publish_modrinth import API, NoRedirect, PublishError, USER_AGENT

ROOT = Path(__file__).resolve().parents[1]
WEB = "https://github.com/DebZakari/Vultrum"
RAW = "https://raw.githubusercontent.com/DebZakari/Vultrum/main/"
SNAPSHOT_FIELDS = (
    "id", "slug", "title", "description", "body", "status", "requested_status",
    "project_type", "categories", "additional_categories", "client_side", "server_side",
    "issues_url", "source_url", "wiki_url", "discord_url", "donation_urls", "license",
    "icon_url", "gallery", "versions",
)


def request(path, token, *, method="GET", data=None):
    headers = {"User-Agent": USER_AGENT, "Authorization": token}
    if data is not None:
        data = json.dumps(data).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(API + path, headers=headers, data=data, method=method)
    try:
        with urllib.request.build_opener(NoRedirect).open(req, timeout=90) as response:
            content = response.read()
            return json.loads(content) if content else None
    except urllib.error.HTTPError as error:
        raise PublishError(f"Modrinth project request failed (HTTP {error.code}). Check scopes and project access.") from None
    except (urllib.error.URLError, TimeoutError):
        raise PublishError("Modrinth connection failed. Audit the current page before retrying a write.") from None


def project_body(readme):
    hero = re.search(r'<img src="docs/images/hero.webp" alt="([^"]+)">', readme)
    if not hero or "> [!NOTE]" not in readme:
        raise PublishError("README hero or alpha notice is missing.")
    body = readme[readme.index("> [!NOTE]"):]
    body = body.replace("> [!NOTE]\n", "")
    body = re.sub(r"^> ?", "", body, flags=re.MULTILINE)
    body = re.sub(r"<picture>.*?src=\"docs/images/([^\"]+)-light.svg\" alt=\"([^\"]+)\">.*?</picture>",
                  lambda m: f"![{html.unescape(m[2])}]({RAW}docs/images/{m[1]}.png)", body, flags=re.DOTALL)
    body = re.sub(r"## Building\n.*?(?=## Licence)", "", body, flags=re.DOTALL)
    body = re.sub(r'<a id="[^"]+"></a>', "", body)
    body = re.sub(r"\]\(([^)]+)\)", lambda m: "](" + absolute_link(m[1]) + ")", body)
    return (f"![{html.unescape(hero[1])}]({RAW}docs/images/hero.webp)\n\n"
            "Measured with Vultrum Alpha.5 and custom Vitrail .1. The full setup is listed under Performance.\n\n"
            + body.strip() + "\n")


def absolute_link(target):
    if target.startswith(("https://", "http://", "#")):
        return target
    return WEB + "/blob/main/" + target


def desired_fields():
    config = json.loads((ROOT / "docs/modrinth-project.json").read_text(encoding="utf-8"))
    allowed = {"title", "description", "source_url", "issues_url", "client_side", "server_side"}
    if not config.keys() <= allowed:
        raise PublishError("Project metadata contains unsupported fields.")
    return config | {"body": project_body((ROOT / "README.md").read_text(encoding="utf-8"))}


def desired_gallery():
    return json.loads((ROOT / "docs/modrinth-gallery.json").read_text(encoding="utf-8"))


def gallery_changes(current, desired):
    changes = []
    for item in desired:
        if not item.keys() <= {"raw_url", "title", "description"} or not item.get("raw_url"):
            raise PublishError("Gallery metadata must identify an existing image and its caption.")
        matches = [image for image in current if item["raw_url"] == image.get("raw_url", image["url"])]
        if len(matches) != 1:
            raise PublishError("A managed gallery image is missing or duplicated; audit the gallery.")
        fields = {k: v for k, v in item.items() if k != "raw_url" and matches[0].get(k) != v}
        if fields:
            changes.append({"url": item["raw_url"], **fields})
    return changes


def validate_project(project, project_id):
    if not re.fullmatch(r"[A-Za-z0-9]{8}", project_id):
        raise PublishError("Expected a stable eight-character project ID.")
    if (project.get("id"), project.get("title"), project.get("project_type")) != (project_id, "Vultrum", "mod"):
        raise PublishError("The configured project is not the Vultrum mod.")


def synchronize(project_id, token, *, apply=False, audit=False):
    if not token:
        raise PublishError("Set the MODRINTH_TOKEN repository secret.")
    if not re.fullmatch(r"[A-Za-z0-9]{8}", project_id):
        raise PublishError("Expected a stable eight-character project ID.")
    path = "/project/" + project_id
    before = request(path, token)
    validate_project(before, project_id)
    snapshot = {k: before.get(k) for k in SNAPSHOT_FIELDS}
    print("PROJECT_BEFORE_JSON=" + json.dumps(snapshot, separators=(",", ":")))
    if audit:
        return
    desired = desired_fields()
    patch = {k: v for k, v in desired.items() if before.get(k) != v}
    captions = desired_gallery()
    gallery = gallery_changes(before.get("gallery", []), captions)
    print("Changed project fields: " + (", ".join(sorted(patch)) or "none"))
    print("Changed gallery captions: " + str(len(gallery)))
    if not apply:
        print("PROJECT_PATCH_JSON=" + json.dumps(patch, separators=(",", ":")))
        print("GALLERY_PATCH_JSON=" + json.dumps(gallery, separators=(",", ":")))
        print("Dry run: no project fields changed.")
        return
    if patch:
        request(path, token, method="PATCH", data=patch)
    for caption in gallery:
        request(path + "/gallery?" + urlencode(caption), token, method="PATCH")
    after = request(path, token)
    validate_project(after, project_id)
    for key, value in desired.items():
        if after.get(key) != value:
            raise PublishError(f"Stored project {key} differs from the requested value.")
    if gallery_changes(after.get("gallery", []), captions):
        raise PublishError("Stored gallery captions differ from the requested values.")
    identities = lambda images: {(i.get("raw_url", i["url"]), i.get("featured"), i.get("ordering")) for i in images}
    if identities(before.get("gallery", [])) != identities(after.get("gallery", [])):
        raise PublishError("Gallery images or their ordering changed; inspect the stored gallery.")
    for key in ("status", "requested_status", "slug", "versions", "icon_url", "license", "categories", "additional_categories"):
        if after.get(key) != before.get(key):
            raise PublishError(f"Project {key} changed during synchronization; inspect the stored project.")
    print("PROJECT_AFTER_JSON=" + json.dumps({k: after.get(k) for k in SNAPSHOT_FIELDS}, separators=(",", ":")))
    print("Project content and gallery captions verified; moderation, slug, versions, icon and gallery images preserved.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit", action="store_true")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if args.audit and args.apply:
        parser.error("Choose audit or apply.")
    try:
        synchronize(os.environ.get("MODRINTH_PROJECT_ID", ""), os.environ.get("MODRINTH_TOKEN", ""),
                    apply=args.apply, audit=args.audit)
    except (PublishError, OSError, ValueError, KeyError) as error:
        raise SystemExit(str(error)) from None


if __name__ == "__main__":
    main()
