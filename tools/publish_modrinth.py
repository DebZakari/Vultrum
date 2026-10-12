"""Mirror a published GitHub jar to Modrinth without rebuilding it."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.error
import urllib.request
import uuid
import zipfile


REPO = "DebZakari/Vultrum"
API = "https://api.modrinth.com/v2"
USER_AGENT = "DebZakari/Vultrum-release-mirror (https://github.com/DebZakari/Vultrum)"
TAG = re.compile(r"v(\d+\.\d+\.\d+(?:-(alpha|beta)\.\d+)?)")
DEPENDENCIES = {
    "sodium": "required",
    "fabric-api": "required",
    "vitrail-shaders": "optional",
    "nvidium": "incompatible",
    "reeses-sodium-options": "incompatible",
}


class PublishError(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    # A publishing credential must never follow a redirect to another host.
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def modrinth(path, token, data=None, content_type=None):
    headers = {"User-Agent": USER_AGENT, "Authorization": token}
    if content_type:
        headers["Content-Type"] = content_type
    request = urllib.request.Request(API + path, data=data, headers=headers)
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=90) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        # Response bodies can contain credentials or private project details.
        raise PublishError(f"Modrinth request failed (HTTP {error.code}). Check token scopes and access.") from None
    except (urllib.error.URLError, TimeoutError):
        raise PublishError("Modrinth connection failed. Check the project before rerunning; an upload may have completed.") from None


def gh(*args):
    result = subprocess.run(["gh", *args], capture_output=True, text=True, encoding="utf-8")
    if result.returncode:
        raise PublishError("GitHub CLI request failed. Check release access and GH_TOKEN.")
    return result.stdout


def release_asset(release, tag):
    match = TAG.fullmatch(tag)
    if not match or release.get("tag_name") != tag:
        raise PublishError("Expected a versioned Vultrum release tag.")
    if release.get("draft") or not release.get("published_at"):
        raise PublishError("The GitHub release must already be published.")
    if not (release.get("body") or "").strip():
        raise PublishError("The GitHub release needs release notes.")
    prefix = f"vultrum-{match[1]}+mc"
    assets = [asset for asset in release.get("assets", [])
              if asset["name"].startswith(prefix) and asset["name"].endswith(".jar")
              and not asset["name"].endswith("-sources.jar")]
    if len(assets) != 1:
        raise PublishError("Expected exactly one primary Vultrum jar on the GitHub release.")
    asset = assets[0]
    if not re.fullmatch(r"sha256:[a-f0-9]{64}", asset.get("digest") or ""):
        raise PublishError("GitHub must provide a SHA-256 digest for the release jar.")
    if not re.fullmatch(r"[A-Za-z0-9.+-]+\.jar", asset["name"]):
        raise PublishError("Unexpected release asset filename.")
    return asset


def metadata(release, tag, asset, jar, project_id, dependencies):
    content = jar.read_bytes()
    if len(content) != asset["size"] or "sha256:" + hashlib.sha256(content).hexdigest() != asset["digest"]:
        raise PublishError("Downloaded jar does not match GitHub's size and SHA-256 digest.")
    with zipfile.ZipFile(jar) as archive:
        mod = json.loads(archive.read("fabric.mod.json"))
    game = mod.get("depends", {}).get("minecraft", "")
    if not isinstance(game, str) or not re.fullmatch(r"\d+(?:\.\d+)+(?:-[A-Za-z0-9.-]+)?", game):
        raise PublishError("The jar must declare one exact Minecraft version.")
    version = tag[1:] + "+mc" + game
    if mod.get("id") != "vultrum" or mod.get("version") != version or mod.get("environment") != "client":
        raise PublishError("Jar identity, version or client environment does not match the release.")
    if asset["name"] != f"vultrum-{version}.jar":
        raise PublishError("Jar filename does not match its metadata.")
    return {
        "project_id": project_id,
        "name": f"Vultrum {tag[1:]}",
        "version_number": version,
        "version_type": TAG.fullmatch(tag)[2] or "release",
        "changelog": release["body"],
        "game_versions": [game],
        "loaders": ["fabric"],
        "environment": "client_only",
        "dependencies": dependencies,
        "featured": False,
        "status": "listed",
        "file_parts": ["jar"],
        "primary_file": "jar",
    }, hashlib.sha512(content).hexdigest()


def verify_version(version, payload, sha512):
    for field in ("project_id", "version_number", "version_type", "game_versions", "loaders", "environment", "status"):
        if version.get(field) != payload[field]:
            raise PublishError(f"Existing Modrinth version has different {field}; refusing to overwrite it.")
    files = [file for file in version.get("files", []) if file.get("primary")]
    if len(files) != 1 or files[0].get("hashes", {}).get("sha512") != sha512:
        raise PublishError("Existing Modrinth version has a different primary jar; refusing to overwrite it.")
    def dependency_keys(dependencies):
        return {(item.get("project_id"), item.get("version_id"), item.get("file_name"), item["dependency_type"])
                for item in dependencies}
    if dependency_keys(version.get("dependencies", [])) != dependency_keys(payload["dependencies"]):
        raise PublishError("Existing Modrinth version has different dependencies; refusing to overwrite it.")


def matching_version(versions, payload, sha512):
    matches = [version for version in versions if version["version_number"] == payload["version_number"]]
    if len(matches) > 1:
        raise PublishError("Multiple Modrinth versions use this number; resolve the duplicate manually.")
    if matches:
        verify_version(matches[0], payload, sha512)
        return matches[0]
    return None


def project_versions(project_id, token):
    versions = []
    seen = set()
    while True:
        page = modrinth(f"/project/{project_id}/version?limit=100&offset={len(versions)}&include_changelog=false", token)
        for version in page:
            if version["id"] in seen:
                raise PublishError("Modrinth version pagination repeated an entry; retry before publishing.")
            seen.add(version["id"])
        versions.extend(page)
        if len(page) < 100:
            return versions


def multipart(payload, jar):
    boundary = "vultrum-" + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="data"\r\n'
            'Content-Type: application/json\r\n\r\n').encode() + json.dumps(payload).encode("utf-8")
    body += (f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="jar"; filename="{jar.name}"\r\n'
             'Content-Type: application/java-archive\r\n\r\n').encode()
    body += jar.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    return body, f"multipart/form-data; boundary={boundary}"


def report(message):
    print(message)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
            summary.write(message + "\n")


def mirror(tag, project_id, token, dry_run):
    if not TAG.fullmatch(tag):
        raise PublishError("Invalid release tag.")
    if not re.fullmatch(r"[A-Za-z0-9]{8}", project_id):
        raise PublishError("Set MODRINTH_PROJECT_ID to the project's stable eight-character ID.")
    if not token:
        raise PublishError("Set the MODRINTH_TOKEN repository secret.")
    release = json.loads(gh("api", f"repos/{REPO}/releases/tags/{tag}"))
    asset = release_asset(release, tag)
    project = modrinth(f"/project/{project_id}", token)
    if project.get("id") != project_id or project.get("project_type") != "mod" or project.get("title") != "Vultrum":
        raise PublishError("MODRINTH_PROJECT_ID does not identify the Vultrum mod project.")
    if project.get("status") != "approved":
        report("Deferred: the Modrinth project is not approved for public releases. After approval, run this workflow manually for the desired tag.")
        return
    dependencies = []
    for slug, kind in DEPENDENCIES.items():
        dependency = modrinth(f"/project/{slug}", token)
        dependencies.append({"project_id": dependency["id"], "dependency_type": kind})
    with tempfile.TemporaryDirectory(prefix="vultrum-release-") as directory:
        gh("release", "download", tag, "--repo", REPO, "--pattern", asset["name"], "--dir", directory)
        jar = Path(directory) / asset["name"]
        payload, sha512 = metadata(release, tag, asset, jar, project_id, dependencies)
        versions = project_versions(project_id, token)
        existing = matching_version(versions, payload, sha512)
        if existing:
            report("Already mirrored: the Modrinth version has the identical primary jar and release metadata.")
            return
        if dry_run:
            report(f"Dry run passed for {payload['version_number']}: GitHub checksum, jar metadata, dependencies and duplicate checks verified. No upload.")
            return
        data, content_type = multipart(payload, jar)
        uploaded = modrinth("/version", token, data, content_type)
        # Verify the stored version rather than trusting the upload response alone.
        stored = modrinth(f"/version/{uploaded['id']}", token)
        verify_version(stored, payload, sha512)
        report(f"Mirrored {payload['version_number']} to https://modrinth.com/mod/{project_id}/version/{stored['id']} with an identical jar.")


if __name__ == "__main__":
    try:
        mirror(os.environ.get("RELEASE_TAG", ""), os.environ.get("MODRINTH_PROJECT_ID", ""),
               os.environ.get("MODRINTH_TOKEN", ""), os.environ.get("DRY_RUN", "true").lower() == "true")
    except (PublishError, OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        raise SystemExit(f"Release mirror failed: {error}") from None
