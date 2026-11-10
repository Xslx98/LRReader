#!/usr/bin/env python3
"""Reports response fields that differ from known-keys.json. Never fails a run on drift.

The client ignores unknown keys, and its DTOs have defaults, so a new or removed
field cannot fail a contract test. It is still worth reading, so it becomes a
`::warning::` and a line in the step summary. HTTP errors and reshaped responses
are warnings too: the contract tests own those, and must still get to run.

    key_drift.py --leg nightly --openapi-container lrr-contract --write-observed out.json
    key_drift.py --update --leg nightly --observed out.json
"""
import argparse
import hashlib
import json
import os
import pathlib
import subprocess

from lrr_client import ApiError, LrrClient

KNOWN_KEYS = pathlib.Path(__file__).with_name("known-keys.json")
OPENAPI_PATH = "/home/koyomi/lanraragi/tools/openapi.yaml"


def _keys(objects):
    found = set()
    for item in objects:
        found.update(item)
    return sorted(found)


def warn(title, message):
    print(f"::warning title={title}::{message}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write(f"- **{title}:** {message}\n")


def observe(client, seed):
    big = seed["archives"]["big"]["id"]
    tank = seed["tank"]["id"]
    stamp = seed["stamp"]
    observed = {}

    def sample(name, path, shape, query=None):
        try:
            observed.update(shape(client.json("GET", path, query=query)))
        except (ApiError, KeyError, TypeError, AttributeError, OSError) as err:
            # A missing or reshaped field is exactly what this reports, and the
            # contract tests must still run after it.
            warn("Contract key drift", f"{name}: {err!r}")

    sample("info", "/api/info", lambda d: {"info": _keys([d])})
    sample(
        "search", "/api/search",
        lambda d: {"search": _keys([d]), "search.data[]": _keys(d["data"])},
        query={"groupby_tanks": "false"},
    )
    sample(
        "search/random", "/api/search/random",
        lambda d: {"search/random": _keys([d]), "search/random.data[]": _keys(d["data"])},
        query={"count": 1, "groupby_tanks": "false"},
    )
    sample("metadata", f"/api/archives/{big}/metadata", lambda d: {"metadata": _keys([d])})
    sample("files", f"/api/archives/{big}/files", lambda d: {"files": _keys([d])})
    sample("archive/categories", f"/api/archives/{big}/categories", lambda d: {"archive/categories": _keys([d])})
    sample("archive/tankoubons", f"/api/archives/{big}/tankoubons", lambda d: {"archive/tankoubons": _keys([d])})
    sample("archive/stamps", f"/api/archives/{stamp['archive']}/stamps", lambda d: {"archive/stamps": _keys([d])})
    sample(
        "archive/stamps/page", f"/api/archives/{stamp['archive']}/stamps/{stamp['page']}",
        lambda d: {"archive/stamps/page": _keys([d]), "archive/stamps/page.result[]": _keys(d["result"])},
    )
    sample("categories[]", "/api/categories", lambda d: {"categories[]": _keys(d)})
    sample(
        "tankoubons", "/api/tankoubons",
        lambda d: {"tankoubons": _keys([d]), "tankoubons.result[]": _keys(d["result"])},
    )
    sample(
        "tankoubons/full", f"/api/tankoubons/{tank}/full",
        lambda d: {
            "tankoubons/full": _keys([d]),
            "tankoubons/full.result": _keys([d["result"]]),
            "tankoubons/full.result.full_data[]": _keys(d["result"]["full_data"]),
        },
        query={"page": -1},
    )
    sample("database/stats[]", "/api/database/stats", lambda d: {"database/stats[]": _keys(d)})
    return observed


def compare(leg, known, observed):
    baseline = known.get("legs", {}).get(leg)
    if baseline is None:
        warn("Contract key drift", f"{leg}: no baseline recorded in known-keys.json")
        return
    for name in sorted(set(baseline) | set(observed)):
        added = sorted(set(observed.get(name, [])) - set(baseline.get(name, [])))
        removed = sorted(set(baseline.get(name, [])) - set(observed.get(name, [])))
        if added or removed:
            warn("Contract key drift", f"{leg} {name}: new {added}, removed {removed}")


def openapi_hash(container):
    spec = subprocess.run(["docker", "exec", container, "cat", OPENAPI_PATH], check=False, capture_output=True)
    if spec.returncode != 0:
        warn("openapi.yaml changed", f"could not read {OPENAPI_PATH}: {spec.stderr.decode(errors='replace')}")
        return None
    return hashlib.sha256(spec.stdout).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--leg", required=True, help="image tag this run tested")
    parser.add_argument("--openapi-container", help="check tools/openapi.yaml inside this container")
    parser.add_argument("--write-observed", help="save what this run saw, for --update")
    parser.add_argument("--update", action="store_true", help="accept --observed into known-keys.json")
    parser.add_argument("--observed", help="an observed-keys.json from a run")
    args = parser.parse_args()

    known = json.loads(KNOWN_KEYS.read_text(encoding="utf-8")) if KNOWN_KEYS.exists() else {}
    if args.update:
        saved = json.loads(pathlib.Path(args.observed).read_text(encoding="utf-8"))
        known.setdefault("legs", {})[args.leg] = saved["keys"]
        if saved.get("openapi_sha256"):
            known["openapi_sha256"] = saved["openapi_sha256"]
        KNOWN_KEYS.write_text(json.dumps(known, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        return

    seed = json.loads(pathlib.Path(os.environ["LRR_CONTRACT_SEED"]).read_text(encoding="utf-8"))
    observed = observe(LrrClient(os.environ["LRR_CONTRACT_URL"], os.environ["LRR_CONTRACT_KEY"]), seed)
    compare(args.leg, known, observed)

    spec_hash = openapi_hash(args.openapi_container) if args.openapi_container else None
    if spec_hash and spec_hash != known.get("openapi_sha256"):
        warn("openapi.yaml changed", f"{args.leg}: sha256 {spec_hash}, recorded {known.get('openapi_sha256')}")

    if args.write_observed:
        pathlib.Path(args.write_observed).write_text(
            json.dumps({"keys": observed, "openapi_sha256": spec_hash}, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )


if __name__ == "__main__":
    main()
