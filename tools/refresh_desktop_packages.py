"""Refresh app code in existing local portable builds without replacing their runtimes."""

import argparse
import gzip
import hashlib
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tarfile
import tempfile
from zipfile import ZIP_STORED, ZipFile

ROOT = Path(__file__).resolve().parents[1]
APP_FILES = (
    "main.lua", "conf.lua", "config.lua", "spectrum.lua", "music_queue.lua",
    "music_backend.lua", "music_windows.lua", "music_call_just_yt_dlp.py",
    "music_recommender.py", "README.md",
)
DEPENDENCIES = {
    "ytmusicapi", "requests", "urllib3", "certifi", "idna", "charset_normalizer",
}


def refresh_app(root, packages, site_packages):
    app = root / "app"
    for name in APP_FILES:
        shutil.copy2(ROOT / name, app / name)
    for path in (ROOT / "python_code_music").glob("*.py"):
        shutil.copy2(path, app / "python_code_music" / path.name)
    for path in packages.iterdir():
        name = path.name
        if name.startswith("certifi") and (site_packages / "certifi/__init__.py").is_file():
            continue
        if name in DEPENDENCIES or (name.endswith(".dist-info") and name.rsplit("-", 1)[0] in DEPENDENCIES):
            shutil.copytree(path, site_packages / name, dirs_exist_ok=True,
                            ignore=shutil.ignore_patterns("__pycache__", "*.so", "*.pyd"))
    for name in DEPENDENCIES:
        if not (site_packages / name / "__init__.py").is_file():
            raise ValueError(f"Missing dependency in supplied site-packages: {name}")


def linux(source, target, staging, packages):
    root = staging / "linux"
    subprocess.run([str(source), "--extract", str(root)], check=True)
    site_packages = next((root / "runtime/lib").glob("python*/site-packages"))
    refresh_app(root, packages, site_packages)
    launcher = root / "AppRun"
    launcher.write_text(launcher.read_text().replace("import yt_dlp, yt_dlp_ejs,", "import ytmusicapi, yt_dlp, yt_dlp_ejs,")
                        .replace("Bundled Python and yt-dlp", "Bundled Python, yt-dlp and recommender"))
    payload = staging / "linux.tar.gz"
    with payload.open("wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=3) as compressed:
        with tarfile.open(fileobj=compressed, mode="w|") as archive:
            for path in sorted(root.iterdir()):
                archive.add(path, arcname=path.name)
    with source.open("rb") as f:
        first = f.read(4096).split(b"\x1f\x8b", 1)[0]
    if not first.endswith(b'exit "$status"\n'):
        raise ValueError("Unrecognized Linux launcher")
    with payload.open("rb") as data:
        digest = hashlib.file_digest(data, "sha256").hexdigest()
    header = re.sub(rb"(?<=printf ')[0-9a-f]{64}", digest.encode(), first)
    with target.open("wb") as out, payload.open("rb") as data:
        out.write(header)
        shutil.copyfileobj(data, out)
    target.chmod(0o755)


def windows(source, target, staging, packages):
    root = staging / "windows"
    with source.open("rb") as f:
        f.seek(-16, 2)
        magic, offset = struct.unpack("<8sQ", f.read(16))
        if magic != b"MUSICWIN" or not 0 < offset < 1024 * 1024:
            raise ValueError("Unrecognized Windows launcher")
        f.seek(0)
        header = f.read(offset)
    with ZipFile(source) as archive:
        archive.extractall(root)
    refresh_app(root, packages, root / "runtime/python/Lib/site-packages")
    payload = staging / "windows.zip"
    with ZipFile(payload, "w", compression=ZIP_STORED, allowZip64=False) as archive:
        for path in sorted(root.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(root).as_posix())
    with target.open("wb") as out, payload.open("rb") as data:
        out.write(header)
        shutil.copyfileobj(data, out)
        out.write(struct.pack("<8sQ", magic, len(header)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("platform", choices=("linux", "windows"))
    parser.add_argument("base", type=lambda value: Path(value).resolve())
    parser.add_argument("output", type=Path)
    parser.add_argument("site_packages", type=Path, help="Site-packages containing ytmusicapi and its dependencies")
    args = parser.parse_args()
    if args.base == args.output.resolve():
        parser.error("Choose a different output path so the base can be recovered.")
    with tempfile.TemporaryDirectory(prefix="music-refresh-") as directory:
        builder = {"linux": linux, "windows": windows}[args.platform]
        builder(args.base, args.output, Path(directory), args.site_packages)
