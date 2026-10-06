## What and why

<!-- What does this change, and which problem does it solve? Link the issue: "Fixes #123". -->

## How it was tested

<!-- New or changed tests, and any manual check on a device or emulator (Android version, server version). -->

## Checklist

- [ ] Each commit is one logical change that builds and passes the tests on its own.
- [ ] Bug fixes come with a test that fails without the fix.
- [ ] `bash scripts/ci-check.sh` passes locally.
- [ ] New user-visible strings have all ten translations.
- [ ] No new entries in the detekt or lint baselines.
- [ ] Schema changes include a Room migration and the exported schema.
- [ ] No credentials, keystores or `local.properties` in the diff.
