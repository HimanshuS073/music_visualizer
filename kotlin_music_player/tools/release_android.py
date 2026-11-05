#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
# SPDX-License-Identifier: GPL-3.0-or-later

"""Build a checked APK; add --publish to publish it on GitHub."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

PROJECT = Path(__file__).resolve().parents[1]
REPO = "HimanshuS073/music_visualizer"
# Public certificate fingerprint, not a private signing key.
CERTIFICATE = "66632e468fffb4d0a2d0c6a802abafe4fc6cb514f95ef896a1737a7c79f9f02f"


def run(*args, capture=False):
    return subprocess.run(args, cwd=PROJECT, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publish", action="store_true", help="also publish the verified APK as a GitHub release")
    args = parser.parse_args()
    if not (PROJECT / ".signing/debug.keystore").is_file():
        raise SystemExit("The original local signing key is required for update-compatible releases.")
    if args.publish and run("git", "status", "--porcelain", "--", ".", capture=True).strip():
        raise SystemExit("Commit the source changes before publishing a release.")
    config = (PROJECT / "app/build.gradle.kts").read_text()
    code = int(re.search(r'versionCode\s*=\s*(\d+)', config)[1])
    name = re.search(r'versionName\s*=\s*"([0-9]+(?:\.[0-9]+){1,2})"', config)[1]
    tag = "android-v" + name
    sdk_path = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    local = PROJECT / "local.properties"
    if not sdk_path and local.exists():
        sdk_path = next((line.partition("=")[2].strip() for line in local.read_text().splitlines()
                         if line.startswith("sdk.dir=")), None)
    if not sdk_path:
        raise SystemExit("Set ANDROID_HOME or sdk.dir in local.properties first.")
    sdk = Path(sdk_path) / "build-tools/36.0.0"
    wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
    run(wrapper, "--no-daemon", ":app:assembleDebug", ":app:lintDebug", ":app:testDebugUnitTest")
    apk = PROJECT / "app/build/outputs/apk/debug/app-debug.apk"
    signature = run(str(sdk / ("apksigner.bat" if os.name == "nt" else "apksigner")),
                    "verify", "--print-certs", str(apk), capture=True)
    if f"certificate SHA-256 digest: {CERTIFICATE}" not in signature:
        raise SystemExit("Signing certificate does not match existing Music Room installs.")
    run(str(sdk / ("zipalign.exe" if os.name == "nt" else "zipalign")), "-c", "-P", "16", "4", str(apk))
    output = PROJECT / "releases" / f"music-room-v{code}.apk"
    output.parent.mkdir(exist_ok=True)
    shutil.copy2(apk, output)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    print(f"APK: {output}\nSHA-256: {digest}\nRelease tag: {tag}")
    if not args.publish:
        print("Ready locally. Commit the source, then run this command with --publish to release it.")
        return
    commit = run("git", "rev-parse", "HEAD", capture=True).strip()
    # Upload into a draft, then check GitHub's digest before anyone can install it.
    with tempfile.TemporaryDirectory(prefix="music-room-release-") as directory:
        notes = Path(directory) / "notes.md"
        notes.write_text(f"Music Room {name} for Android 8 and newer.\n\n"
                         f"Download **{output.name}** and install it over the previous Kotlin app. "
                         "Music Room checks for future updates at startup and from Settings. "
                         "Android requires confirmation to install.\n\n"
                         "This is a personal debug-signed build. The signing key stays private. "
                         "Automated checks cover update validation and simulated lifecycle behavior; "
                         "real-device audio, installer and screen-lock testing is still needed.\n\n"
                         f"Source: this release's Source code archives, `kotlin_music_player/`. "
                         "Build and dependency instructions are included there.\n\n"
                         f"SHA-256: `{digest}`\n")
        releases = json.loads(run("gh", "api", f"repos/{REPO}/releases?per_page=100", capture=True))
        release = next((item for item in releases if item["tag_name"] == tag), None)
        if release is not None and not release["draft"]:
            raise SystemExit("That release is already published. Increase the version before publishing again.")
        if release is None:
            run("gh", "release", "create", tag, str(output), "--repo", REPO, "--target", commit,
                "--draft", "--title", f"Music Room {name} — Android", "--notes-file", str(notes))
            releases = json.loads(run("gh", "api", f"repos/{REPO}/releases?per_page=100", capture=True))
            release = next(item for item in releases if item["tag_name"] == tag)
        else:
            run("gh", "release", "upload", tag, str(output), "--repo", REPO, "--clobber")
        endpoint = f"repos/{REPO}/releases/{release['id']}"
        release = json.loads(run("gh", "api", endpoint, capture=True))
        asset = next((a for a in release["assets"] if a["name"] == output.name), {})
        if asset.get("digest") != "sha256:" + digest or asset.get("size") != output.stat().st_size:
            raise SystemExit("Uploaded asset verification failed. Release was left as a draft.")
        payload = Path(directory) / "publish.json"
        payload.write_text(json.dumps({"draft": False, "make_latest": "false", "target_commitish": commit,
                                       "name": f"Music Room {name} — Android", "body": notes.read_text()}))
        run("gh", "api", endpoint, "--method", "PATCH", "--input", str(payload), capture=True)
    print(f"Published https://github.com/{REPO}/releases/tag/{tag}")


if __name__ == "__main__":
    main()
