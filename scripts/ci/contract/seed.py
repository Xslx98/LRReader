#!/usr/bin/env python3
"""Boots a throwaway LANraragi container and seeds it for the contract tests.

    seed.py boot --image difegue/lanraragi:v.0.9.81 --content-dir /tmp/lrr-content
    seed.py seed --out-dir /tmp/lrr-seed

`boot` retries once, because pulling and starting the image are infrastructure,
and labels a final failure as such. `seed` never retries: a failure there is a
finding about the server. Stdlib only. See README.md in this directory.
"""
import argparse
import json
import os
import pathlib
import re
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.parse

from fixtures import write_archive
from lrr_client import ApiError, LrrClient

CONTAINER = "lrr-contract"
BOOT_DEADLINE_S = 180
SEED_DEADLINE_S = 90
POLL_S = 1

# name, pages, padded past the arcid cut-off, tags
ARCHIVES = [
    ("alpha", 3, False, "artist:contract alpha,date_added:1700000000,contractmisc,rating:4"),
    ("bravo", 4, False, "artist:contract bravo,date_added:1700000000,series:contract"),
    ("charlie", 5, False, "artist:contract charlie,date_added:1700000000,series:contract"),
    ("big", 40, True, "artist:contract big,date_added:1700000000"),
]
# Generated but never uploaded: the upload contract test owns its lifecycle.
SPARE = ("spare", 6, False)


def _run(*cmd, check=True):
    return subprocess.run(cmd, check=check, capture_output=True, text=True)


def _export(env_file, values):
    """Hands values to later steps: $GITHUB_ENV in Actions, a sourceable file locally."""
    github_env = os.environ.get("GITHUB_ENV")
    if github_env:
        with open(github_env, "a", encoding="utf-8") as out:
            out.writelines(f"{k}={v}\n" for k, v in values.items())
    if env_file:
        with open(env_file, "a", encoding="utf-8") as out:
            out.writelines(f"export {k}='{v}'\n" for k, v in values.items())


def _wait_for_info(client, deadline_s):
    deadline = time.monotonic() + deadline_s
    last = "no answer"
    while time.monotonic() < deadline:
        try:
            status, _ = client.request("GET", "/api/info", timeout=5)
            if status == 200:
                return True
            last = f"HTTP {status}"
        except (urllib.error.URLError, OSError) as err:
            last = str(err)
        time.sleep(POLL_S)
    print(f"/api/info not ready after {deadline_s} s: {last}", file=sys.stderr)
    return False


def _start_container(image, content_dir, port):
    _run("docker", "rm", "-f", CONTAINER, check=False)
    _run("docker", "pull", image)
    # Windows has no uid to hand over, and the image's defaults work there.
    owner = ["-e", f"LRR_UID={os.getuid()}", "-e", f"LRR_GID={os.getgid()}"] if hasattr(os, "getuid") else []
    _run(
        "docker", "run", "-d", "--name", CONTAINER,
        # Loopback only: this server has the default password until the key is set.
        "-p", f"127.0.0.1:{port}:3000",
        *owner,
        "-v", f"{content_dir}:/home/koyomi/lanraragi/content",
        image,
    )


def _set_api_key(key):
    """Config lives in Redis DB 2 (lrr.conf), and Model/Config.pm reads it on every call."""
    _run("docker", "exec", CONTAINER, "valkey-cli", "-n", "2", "hset", "LRR_CONFIG", "apikey", key)


def boot(args):
    if not re.fullmatch(r"[\w./:-]+", args.image):
        sys.exit(f"Refusing image name {args.image!r}")
    pathlib.Path(args.content_dir).mkdir(parents=True, exist_ok=True)
    url = f"http://127.0.0.1:{args.port}"
    client = LrrClient(url)
    for attempt in (1, 2):
        try:
            _start_container(args.image, args.content_dir, args.port)
            if _wait_for_info(client, BOOT_DEADLINE_S):
                break
            logs = _run("docker", "logs", "--tail", "50", CONTAINER, check=False)
            print(logs.stdout + logs.stderr, file=sys.stderr)
        except subprocess.CalledProcessError as err:
            print(f"attempt {attempt}: {err.cmd} failed: {err.stderr}", file=sys.stderr)
    else:
        print(f"::error title=Infrastructure (not a contract failure)::{args.image} did not boot")
        sys.exit(1)

    key = secrets.token_urlsafe(32)
    if os.environ.get("GITHUB_ACTIONS"):
        print(f"::add-mask::{key}")
    _set_api_key(key)
    _export(args.env_file, {"LRR_CONTRACT_URL": url, "LRR_CONTRACT_KEY": key})
    print(f"{args.image} is up at {url}")


def _poll(what, predicate):
    deadline = time.monotonic() + SEED_DEADLINE_S
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(POLL_S)
    raise ApiError(f"seed: {what} not true after {SEED_DEADLINE_S} s")


def _upload(client, path, title):
    data = client.json(
        "PUT", "/api/archives/upload",
        multipart=({"title": title}, "file", path.name, path.read_bytes()),
    )
    if data.get("success") != 1 or not data.get("id"):
        raise ApiError(f"seed: upload of {path.name} answered {data}")
    return data["id"]


def _make_archive(out_dir, name, pages, padded):
    path = out_dir / f"contract-{name}.zip"
    colours = write_archive(path, name, pages, padded)
    return path, {
        "title": f"Contract {name}",
        "file": str(path.resolve()),
        "pages": pages,
        "page_rgb": colours,
    }


def _seed_archives(client, out_dir):
    archives = {}
    for name, pages, padded, tags in ARCHIVES:
        path, entry = _make_archive(out_dir, name, pages, padded)
        entry["id"] = _upload(client, path, entry["title"])
        entry["tags"] = tags
        # Tags go through the metadata route: upload's `tags` field writes the
        # archive hash without updating the search index.
        client.json("PUT", f"/api/archives/{entry['id']}/metadata", query={"tags": tags})
        archives[name] = entry
    for entry in archives.values():
        metadata_url = f"/api/archives/{entry['id']}/metadata"
        _poll(
            f"pagecount of {entry['title']}",
            lambda url=metadata_url, want=entry["pages"]: client.json("GET", url).get("pagecount") == want,
        )
    return archives


def _seed_tank(client, members):
    tank_id = client.json("PUT", "/api/tankoubons", form={"name": "Contract tank"})["tankoubon_id"]
    client.json("PUT", f"/api/tankoubons/{tank_id}", json_body={"archives": members})
    return {"id": tank_id, "name": "Contract tank", "members": members}


def _seed_categories(client, static_members):
    static_id = client.json("PUT", "/api/categories", form={"name": "Contract static"})["category_id"]
    for member in static_members:
        client.json("PUT", f"/api/categories/{static_id}/{member}")
    dynamic_id = client.json(
        "PUT", "/api/categories", form={"name": "Contract dynamic", "search": "series:contract"}
    )["category_id"]
    return {
        "static": {"id": static_id, "name": "Contract static", "members": static_members},
        "dynamic": {"id": dynamic_id, "name": "Contract dynamic", "search": "series:contract"},
    }


def _ids_in_search(client, group_tanks):
    query = {"groupby_tanks": "true" if group_tanks else "false"}
    status, body = client.request("GET", "/api/search", query=query)
    if status != 200:
        return set()
    return {entry["arcid"] for entry in json.loads(body)["data"]}


def seed(args):
    out_dir = pathlib.Path(args.out_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    url = os.environ["LRR_CONTRACT_URL"]
    host = urllib.parse.urlsplit(url).hostname
    if host not in ("127.0.0.1", "localhost", "::1"):
        sys.exit(f"Refusing to seed a non-loopback server: {host}")
    client = LrrClient(url, os.environ["LRR_CONTRACT_KEY"])

    archives = _seed_archives(client, out_dir)
    alpha, bravo, charlie, big = (archives[n]["id"] for n in ("alpha", "bravo", "charlie", "big"))
    tank = _seed_tank(client, [alpha, bravo])
    categories = _seed_categories(client, [alpha, charlie, tank["id"]])

    stamp = {"archive": big, "page": 3, "content": "contract stamp", "position": "25,75"}
    stamp["id"] = client.json(
        "PUT", f"/api/archives/{big}/stamps/{stamp['page']}",
        query={"content": stamp["content"], "position": stamp["position"]},
    )["stamp_id"]
    client.json("PUT", f"/api/archives/{big}/toc", query={"page": 1, "title": "Chapter one"})
    client.json("PUT", f"/api/archives/{big}/toc", query={"page": 21, "title": "Chapter two"})

    every_id = {alpha, bravo, charlie, big}
    _poll("all archives in search", lambda: every_id <= _ids_in_search(client, False))
    _poll("tank folding in search", lambda: tank["id"] in _ids_in_search(client, True))

    _, spare = _make_archive(out_dir, *SPARE)
    seed_file = out_dir / "seed.json"
    seed_file.write_text(
        json.dumps(
            {"archives": archives, "spare": spare, "tank": tank, "categories": categories, "stamp": stamp},
            indent=2,
        ),
        encoding="utf-8",
    )
    _export(args.env_file, {"LRR_CONTRACT_SEED": str(seed_file)})
    print(f"Seeded {len(archives)} archives into {url}; wrote {seed_file}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", help="also append `export` lines for the test env vars here")
    commands = parser.add_subparsers(dest="command", required=True)
    boot_cmd = commands.add_parser("boot")
    boot_cmd.add_argument("--image", required=True)
    boot_cmd.add_argument("--content-dir", required=True)
    boot_cmd.add_argument("--port", type=int, default=3000)
    boot_cmd.set_defaults(func=boot)
    seed_cmd = commands.add_parser("seed")
    seed_cmd.add_argument("--out-dir", required=True)
    seed_cmd.set_defaults(func=seed)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
