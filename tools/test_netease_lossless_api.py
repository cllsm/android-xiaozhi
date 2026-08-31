#!/usr/bin/env python3
"""Diagnose the NetEase lossless gateway used by the Android app.

The script mirrors the app request format and writes request/response details
to the console and build/music-api-test.log by default. The AppKey is always
masked in logs.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


DEFAULT_API_URL = "https://api.j8y.cn/api/gateway.php"
PLAYBACK_URL_KEYS = ("url", "music_url", "play_url", "data_url", "link")


class GatewayError(RuntimeError):
    def __init__(self, status: int, body: str, reason: str = "") -> None:
        self.status = status
        self.body = body
        super().__init__(f"HTTP {status} {reason}".strip())


def load_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("#", "!")) or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def build_url(base_url: str, action: str, params: dict[str, str]) -> str:
    parsed = urllib.parse.urlsplit(base_url)
    if parsed.scheme != "https" or not parsed.hostname:
        raise ValueError("The gateway URL must be a valid HTTPS URL")

    query = [
        item for item in urllib.parse.parse_qsl(parsed.query, keep_blank_values=True)
        if item[0] != "api_path"
    ]
    query.extend([("api_path", "wy_music"), ("action", action)])
    query.extend(params.items())
    return urllib.parse.urlunsplit(parsed._replace(query=urllib.parse.urlencode(query)))


def mask_key(value: str) -> str:
    if len(value) <= 6:
        return "*" * len(value)
    return f"{value[:3]}...{value[-3:]}"


def request_json(
    url: str,
    app_key: str,
    timeout: float,
    retries: int,
    retry_delay: float,
) -> dict[str, Any]:
    headers = {
        "Accept": "application/json",
        "X-App-Key": app_key,
    }
    log.info("request headers: %s", {
        "Accept": headers["Accept"],
        "X-App-Key": mask_key(app_key),
    })

    last_error: GatewayError | None = None
    for attempt in range(1, retries + 2):
        log.info("request attempt %s: GET %s", attempt, url)
        started = time.perf_counter()
        request = urllib.request.Request(url, headers=headers, method="GET")
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                body = response.read().decode("utf-8", errors="replace")
                elapsed_ms = int((time.perf_counter() - started) * 1000)
                log.info(
                    "response: HTTP %s in %s ms; content-type=%s; final-url=%s",
                    response.status,
                    elapsed_ms,
                    response.headers.get("Content-Type", ""),
                    response.url,
                )
                log.info("response body: %s", body)
                parsed = json.loads(body)
                if not isinstance(parsed, dict):
                    raise ValueError("The gateway response root must be a JSON object")
                return parsed
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", errors="replace")
            elapsed_ms = int((time.perf_counter() - started) * 1000)
            log.info(
                "response: HTTP %s in %s ms; content-type=%s; body=%s",
                error.code,
                elapsed_ms,
                error.headers.get("Content-Type", ""),
                body,
            )
            last_error = GatewayError(error.code, body, error.reason)
            if error.code < 500 or attempt > retries:
                raise last_error from error
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, ValueError) as error:
            elapsed_ms = int((time.perf_counter() - started) * 1000)
            log.info("request failed after %s ms: %s: %s", elapsed_ms, type(error).__name__, error)
            raise

        if attempt <= retries:
            log.info("retrying in %s seconds", retry_delay)
            time.sleep(retry_delay)

    raise AssertionError("unreachable retry state")


def find_array(root: dict[str, Any], name: str) -> list[dict[str, Any]]:
    value = root.get(name)
    if isinstance(value, list):
        return [item for item in value if isinstance(item, dict)]
    for child in root.values():
        if isinstance(child, dict):
            found = find_array(child, name)
            if found:
                return found
    return []


def find_string(root: dict[str, Any], names: tuple[str, ...]) -> str | None:
    for name in names:
        value = root.get(name)
        if isinstance(value, str) and value:
            return value
    for child in root.values():
        if isinstance(child, dict):
            found = find_string(child, names)
            if found is not None:
                return found
    return None


def artist_text(item: dict[str, Any]) -> str:
    artists = item.get("artists")
    if isinstance(artists, list):
        names = []
        for artist in artists:
            if isinstance(artist, dict):
                name = artist.get("name")
                if isinstance(name, str) and name:
                    names.append(name)
            elif isinstance(artist, str) and artist:
                names.append(artist)
        return "/".join(names)
    for key in ("artist", "artists"):
        value = item.get(key)
        if isinstance(value, str) and value:
            return value
    value = item.get("singer")
    if isinstance(value, str) and value:
        return value
    return ""


def album_text(item: dict[str, Any]) -> str:
    album = item.get("album")
    if isinstance(album, dict) and isinstance(album.get("name"), str):
        return album["name"]
    album = item.get("al")
    if isinstance(album, dict) and isinstance(album.get("name"), str):
        return album["name"]
    value = item.get("album")
    return value if isinstance(value, str) else ""


def parse_songs(root: dict[str, Any]) -> list[dict[str, Any]]:
    return find_array(root, "songs")


def probe_playback_url(url: str, timeout: float) -> None:
    probes = (
        ("HEAD", {}),
        ("GET", {"Range": "bytes=0-0"}),
    )
    for method, extra_headers in probes:
        log.info("probe %s playback URL: %s", method, url)
        request = urllib.request.Request(url, headers=extra_headers, method=method)
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                content_type = response.headers.get("Content-Type", "")
                content_length = response.headers.get("Content-Length", "")
                log.info(
                    "probe response: HTTP %s in %s ms; content-type=%s; content-length=%s",
                    response.status,
                    int((time.perf_counter() - started) * 1000),
                    content_type,
                    content_length,
                )
                return
        except urllib.error.HTTPError as error:
            log.info(
                "probe response: HTTP %s in %s ms; content-type=%s; content-length=%s",
                error.code,
                int((time.perf_counter() - started) * 1000),
                error.headers.get("Content-Type", ""),
                error.headers.get("Content-Length", ""),
            )
        except (urllib.error.URLError, TimeoutError) as error:
            log.info("probe failed: %s: %s", type(error).__name__, error)


def configure_logging(output: Path | None) -> None:
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8", errors="replace")
    handlers: list[logging.Handler] = [logging.StreamHandler()]
    if output is not None:
        output.parent.mkdir(parents=True, exist_ok=True)
        handlers.append(logging.FileHandler(output, mode="w", encoding="utf-8"))
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        handlers=handlers,
    )


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed < 1:
        raise argparse.ArgumentTypeError("must be >= 1")
    return parsed


def parse_args() -> argparse.Namespace:
    project_root = Path(__file__).resolve().parents[1]
    properties = load_properties(project_root / "local.properties")
    default_api_url = (
        os.environ.get("XIAOZHI_NETEASE_LOSSLESS_API_URL")
        or properties.get("XIAOZHI_NETEASE_LOSSLESS_API_URL")
        or DEFAULT_API_URL
    )
    default_app_key = (
        os.environ.get("XIAOZHI_NETEASE_LOSSLESS_APP_KEY")
        or properties.get("XIAOZHI_NETEASE_LOSSLESS_APP_KEY")
        or ""
    )

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("query", nargs="?", default="晴天", help="search keyword (default: 晴天)")
    parser.add_argument("--api-url", default=default_api_url, help="gateway HTTPS URL")
    parser.add_argument("--app-key", default=default_app_key, help="gateway AppKey")
    parser.add_argument("--song-id", help="skip search and test this song ID directly")
    parser.add_argument("--index", type=positive_int, default=1, help="1-based search result index")
    parser.add_argument("--level", default="lossless", help="playback quality level")
    parser.add_argument("--limit", type=positive_int, default=20, help="search result limit")
    parser.add_argument("--timeout", type=float, default=15.0, help="HTTP timeout seconds")
    parser.add_argument("--retries", type=int, default=1, help="retry count for HTTP 5xx responses")
    parser.add_argument("--retry-delay", type=float, default=0.15, help="delay between retries")
    parser.add_argument("--no-probe", action="store_true", help="do not probe the returned audio URL")
    parser.add_argument("--no-log-file", action="store_true", help="only print logs to the console")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.app_key:
        raise SystemExit("AppKey is missing. Set XIAOZHI_NETEASE_LOSSLESS_APP_KEY or use --app-key.")

    output = None if args.no_log_file else Path(__file__).resolve().parents[1] / "build" / "music-api-test.log"
    configure_logging(output)
    if output is not None:
        log.info("log file: %s", output)

    try:
        if args.song_id:
            song_id = args.song_id
            log.info("using explicit song_id=%s", song_id)
        else:
            search_url = build_url(
                args.api_url,
                "search",
                {"keyword": args.query, "limit": str(args.limit)},
            )
            log.info("=== SEARCH REQUEST ===")
            search_json = request_json(search_url, args.app_key, args.timeout, args.retries, args.retry_delay)
            songs = parse_songs(search_json)
            log.info("parsed songs: %s", len(songs))
            for position, song in enumerate(songs, start=1):
                song_id_value = song.get("id", song.get("song_id", song.get("songId")))
                log.info(
                    "candidate %s: song_id=%s; title=%s; artist=%s; album=%s",
                    position,
                    song_id_value,
                    song.get("name", song.get("title", song.get("song_name", ""))),
                    artist_text(song),
                    album_text(song),
                )
            if not songs:
                log.error("no songs were returned; stop playback test")
                return 1
            if args.index > len(songs):
                log.error("selected index %s is out of range; only %s songs returned", args.index, len(songs))
                return 1
            selected = songs[args.index - 1]
            song_id = str(selected.get("id", selected.get("song_id", selected.get("songId", ""))))
            if not song_id:
                log.error("selected result has no usable song_id: %s", selected)
                return 1

        song_url = build_url(
            args.api_url,
            "song",
            {"id": song_id, "level": args.level},
        )
        log.info("=== PLAYBACK REQUEST ===")
        playback_json = request_json(song_url, args.app_key, args.timeout, args.retries, args.retry_delay)
        playback_url = find_string(playback_json, PLAYBACK_URL_KEYS)
        if playback_url:
            log.info("parsed playback URL: %s", playback_url)
            if not args.no_probe:
                probe_playback_url(playback_url, args.timeout)
        else:
            log.error("response does not contain a playback URL under keys=%s", PLAYBACK_URL_KEYS)
        return 0 if playback_url else 1
    except GatewayError as error:
        log.error("gateway request failed: %s", error)
        return 1
    except Exception as error:  # Keep the tester usable from a plain terminal.
        log.exception("unexpected failure: %s", error)
        return 1


if __name__ == "__main__":
    log = logging.getLogger("music-api")
    main()
else:
    log = logging.getLogger("music-api")
