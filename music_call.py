import shutil
import subprocess

import yt_dlp
from ytmusicapi import YTMusic


def play_song(song):
    """Resolve and play a song's audio stream without saving it locally."""
    video_id = song.get("videoId")
    if not video_id:
        print("This result does not have a playable video.")
        return

    if not shutil.which("ffplay"):
        print("ffplay was not found. Install FFmpeg and make sure ffplay is on PATH.")
        return

    video_url = f"https://www.youtube.com/watch?v={video_id}"
    options = {
        "format": "bestaudio/best",
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
    }

    try:
        with yt_dlp.YoutubeDL(options) as downloader:
            info = downloader.extract_info(video_url, download=False)

        stream_url = info.get("url")
        if not stream_url:
            print("yt-dlp could not find an audio stream for this song.")
            return

        headers = info.get("http_headers", {})
        header_arg = "".join(f"{key}: {value}\r\n" for key, value in headers.items())
        command = ["ffplay", "-nodisp", "-autoexit", "-loglevel", "warning"]
        if header_arg:
            command.extend(["-headers", header_arg])
        command.append(stream_url)

        print("\nStreaming audio without saving it. Press Ctrl+C to stop.")
        subprocess.run(command, check=False)
    except KeyboardInterrupt:
        print("\nPlayback stopped.")
    except Exception as error:
        print(f"Could not play this song: {error}")


def main():
    yt = YTMusic()
    while True:
        query = input("\nSearch for a song (or q to quit): ").strip()
        if query.lower() in {"q", "quit", "exit"}:
            print("Goodbye!")
            break
        if not query:
            print("Please enter a song name.")
            continue

        try:
            results = yt.search(query, filter="songs", limit=5)
            if not results:
                print("No songs found.")
                continue

            print("\nSearch results:\n")
            for index, song in enumerate(results, start=1):
                title = song.get("title", "Unknown title")
                artists = ", ".join(
                    artist.get("name", "Unknown artist")
                    for artist in song.get("artists", [])
                )
                print(f"{index}. {title}")
                print(f"   Artist: {artists or 'Unknown artist'}")
                print(f"   Video ID: {song.get('videoId', 'N/A')}\n")

            choice = input("Enter a song number to play, or press Enter to search again: ").strip()
            if not choice:
                continue
            try:
                selected_index = int(choice) - 1
                if not 0 <= selected_index < len(results):
                    raise ValueError
            except ValueError:
                print("Please enter a number from the search results.")
                continue

            play_song(results[selected_index])
        except Exception as error:
            print(f"Search or playback failed: {error}")


if __name__ == "__main__":
    main()
