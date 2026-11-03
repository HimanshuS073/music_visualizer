# SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
# SPDX-License-Identifier: GPL-3.0-or-later

"""Fetch cover images into memory."""

import urllib.error
import urllib.request


def thumbnail_url(song):
    """Choose a thumbnail URL from the result."""
    url = song.get("thumbnail")
    if isinstance(url, str) and url.startswith(("https://", "http://")):
        return url
    images = song.get("thumbnails", [])
    if isinstance(images, list):
        for image in reversed(images):
            if isinstance(image, dict):
                url = image.get("url")
                if isinstance(url, str) and url.startswith(("https://", "http://")):
                    return url
    return f"https://i.ytimg.com/vi/{song['id']}/hqdefault.jpg"


def fetch_thumbnail(song, high_resolution=False):
    """Fetch image bytes without saving a file."""
    urls = [thumbnail_url(song)]
    if high_resolution:
        urls = [f"https://i.ytimg.com/vi/{song['id']}/maxresdefault.jpg",
                f"https://i.ytimg.com/vi/{song['id']}/sddefault.jpg"] + urls
    for index, url in enumerate(urls):
        request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                data = response.read(10 * 1024 * 1024 + 1)
            if len(data) > 10 * 1024 * 1024:
                raise ValueError("Thumbnail is too large.")
            return data
        except urllib.error.HTTPError as error:
            if error.code != 404 or index == len(urls) - 1:
                raise


def load_image(song):
    return fetch_thumbnail(song)


def show_image(song):
    """Older callers still use this name."""
    return fetch_thumbnail(song)
