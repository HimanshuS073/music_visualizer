import json
import shutil
import subprocess
import sys
import urllib.request


def search_songs(query):
    cmd = [
        sys.executable, "-m", "yt_dlp",
        "--flat-playlist", "--dump-single-json",
        "--no-warnings", f"ytsearch10:{query}",
    ]

    try:
        result = subprocess.run(cmd, capture_output=True, text=True, check=True)
        return json.loads(result.stdout).get("entries", [])
    except (subprocess.CalledProcessError, json.JSONDecodeError):
        print("Search failed.")
        return []


def show_image(song):
    video_id = song.get("id")
    if not video_id:
        return

    try:
        result = subprocess.run(
            [
                sys.executable, "-m", "yt_dlp",
                "--skip-download", "--no-warnings",
                "--print", "%(thumbnail)s",
                f"https://www.youtube.com/watch?v={video_id}",
            ],
            capture_output=True, text=True, check=True,
        )

        image_url = result.stdout.strip().splitlines()[0]
        request = urllib.request.Request(
            image_url,
            headers={"User-Agent": "Mozilla/5.0"},
        )

        with urllib.request.urlopen(request, timeout=15) as response:
            image_data = response.read()

        process = subprocess.Popen(
            ["chafa", "--format", "symbols", "--colors", "full", "-"],
            stdin=subprocess.PIPE,
        )
        process.communicate(image_data)

    except (subprocess.SubprocessError, OSError, IndexError):
        print("Could not display thumbnail.")


def stop_process(process):
    if process and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def play_song(song):
    video_id = song.get("id")
    if not video_id:
        return

    downloader = None
    player = None

    try:
        downloader = subprocess.Popen(
            [
                sys.executable, "-m", "yt_dlp",
                "--quiet", "--no-warnings", "--no-progress",
                "--no-playlist", "-f", "bestaudio/best",
                "-o", "-",
                f"https://www.youtube.com/watch?v={video_id}",
            ],
            stdout=subprocess.PIPE,
        )

        player = subprocess.Popen(
            [
                "ffplay", "-nodisp", "-autoexit",
                "-loglevel", "error", "-i", "pipe:0",
            ],
            stdin=downloader.stdout,
        )

        downloader.stdout.close()
        print("\nPlaying audio. Press Ctrl+C to stop.")

        player.wait()
        downloader.wait()

    except KeyboardInterrupt:
        print("\nPlayback stopped.")
    except OSError as error:
        print(f"Playback failed: {error}")
    finally:
        stop_process(player)
        stop_process(downloader)


def main():
    if not all(shutil.which(name) for name in ("ffmpeg", "ffplay", "chafa")):
        print("Install dependencies: sudo pacman -S ffmpeg chafa")
        return

    try:
        subprocess.run(
            [sys.executable, "-m", "yt_dlp", "--version"],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
    except (subprocess.CalledProcessError, OSError):
        print("Install yt-dlp: python -m pip install -U yt-dlp")
        return

    while True:
        try:
            query = input("\nSearch song (q to quit): ").strip()
        except (EOFError, KeyboardInterrupt):
            break

        if query.lower() in {"q", "quit", "exit"}:
            break
        if not query:
            continue

        results = search_songs(query)
        if not results:
            print("No results found.")
            continue

        for i, song in enumerate(results, 1):
            print(f"{i}. {song.get('title', 'Unknown title')}")

        try:
            choice = input("Select a song: ").strip()
            if not choice:
                continue

            index = int(choice) - 1
            if not 0 <= index < len(results):
                raise ValueError
        except ValueError:
            print("Invalid selection.")
            continue
        except (EOFError, KeyboardInterrupt):
            break

        song = results[index]
        show_image(song)
        play_song(song)

    print("Goodbye!")


if __name__ == "__main__":
    main()
