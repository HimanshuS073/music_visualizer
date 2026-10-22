import shutil
import subprocess
import sys

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
    downloader = None
    player = None
    try:
        # Let yt-dlp make and maintain the media request. Passing its signed
        # URL to ffplay directly can fail when YouTube binds it to a session/IP.
        downloader = subprocess.Popen(
            [
                sys.executable,
                "-m",
                "yt_dlp",
                "--quiet",
                "--no-warnings",
                "--no-progress",
                "--no-playlist",
                "--format",
                "bestaudio/best",
                "--output",
                "-",
                video_url,
            ],
            stdout=subprocess.PIPE,
        )
        player = subprocess.Popen(
            ["ffplay", "-nodisp", "-autoexit", "-loglevel", "warning", "-i", "pipe:0"],
            stdin=downloader.stdout,
        )
        downloader.stdout.close()

        print("\nStreaming audio without saving it. Press Ctrl+C to stop.")
        player_status = player.wait()
        downloader_status = downloader.wait()
        if player_status:
            print("ffplay could not play this stream.")
        elif downloader_status:
            print("yt-dlp could not finish the audio stream.")
    except KeyboardInterrupt:
        print("\nPlayback stopped.")
        for process in (player, downloader):
            if process and process.poll() is None:
                process.terminate()
        for process in (player, downloader):
            if process:
                process.wait()
    except Exception as error:
        print(f"Could not play this song: {error}")
        for process in (player, downloader):
            if process and process.poll() is None:
                process.terminate()
        for process in (player, downloader):
            if process:
                process.wait()


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
