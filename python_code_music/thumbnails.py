"""Return thumbnail URLs or image bytes. Images are never saved or displayed here."""

import urllib.request


def thumbnail_url(song):
    """Return a cover URL so another app can draw the image itself."""
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


def fetch_thumbnail(song):
    """Return image bytes in memory. This does not print or save a file."""
    request = urllib.request.Request(
        thumbnail_url(song), headers={"User-Agent": "Mozilla/5.0"},
    )
    with urllib.request.urlopen(request, timeout=5) as response:
        return response.read(10 * 1024 * 1024)


def load_image(song):
    """Return image bytes for the calling app to display."""
    return fetch_thumbnail(song)


def show_image(song):
    """Keep the previous name usable; return bytes without terminal output."""
    return fetch_thumbnail(song)
