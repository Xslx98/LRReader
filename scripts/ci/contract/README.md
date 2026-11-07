# Contract tests against a real LANraragi

`.github/workflows/contract.yml` boots real servers (`v.0.9.81` and `nightly`), seeds them, and runs the client's own `LRR*Api` classes and DTOs against them, so an upstream break shows up before a user reports it. It is a weekly signal, not a merge gate.

## Run it locally

Any machine with Docker works. The API key is random, and the container only listens on loopback. Every test run needs a freshly seeded container and an empty content folder, because the tests assert what a new server answers, such as a page thumbnail not generated yet.

```bash
docker rm -f lrr-contract
rm -rf /tmp/lrr-env /tmp/lrr-content /tmp/lrr-seed
python3 scripts/ci/contract/seed.py --env-file /tmp/lrr-env boot \
  --image difegue/lanraragi:nightly --content-dir /tmp/lrr-content
source /tmp/lrr-env
python3 scripts/ci/contract/seed.py --env-file /tmp/lrr-env seed --out-dir /tmp/lrr-seed
source /tmp/lrr-env
./gradlew app:testAppReleaseDebugUnitTest -PlrrContract
docker rm -f lrr-contract
```

- The tests live in `app/src/test/java/com/lanraragi/reader/contract/`. The default unit test run excludes them.
- `-PlrrContract` runs only them, one class at a time, never from the build cache.
- Without `LRR_CONTRACT_URL`, `LRR_CONTRACT_KEY` and `LRR_CONTRACT_SEED` the run fails instead of skipping.
- A URL that is not loopback is refused, because these tests write and delete.

## What the signals mean

| Signal | Meaning | What to do |
|---|---|---|
| Red test | A DTO, status or behaviour the client relies on changed | Read the upstream change, then adapt the client with a version gate or tell upstream |
| `Contract key drift` warning | A response gained or lost a field the client may ignore | Read the change, then run `key_drift.py --update` (below) and commit `known-keys.json` |
| `openapi.yaml changed` warning (nightly) | Upstream edited its API description | Read the diff, then update the recorded hash the same way |
| `Infrastructure` error | The image did not pull or boot, after one retry | Not a contract result; rerun the job |

Tests are never retried automatically. Find the mechanism behind a failure before calling it flaky.

## Refresh `known-keys.json`

Each run uploads `observed-keys.json` in its `contract-<tag>` artifact. To accept what a run saw:

```bash
python3 scripts/ci/contract/key_drift.py --update --leg nightly --observed observed-keys.json
```
