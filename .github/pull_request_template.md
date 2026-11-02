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
- [ ] New code sits in the package its concept belongs to and follows `config/architecture/rules.txt`; no new lines in `config/architecture/allowlist.txt`.
- [ ] Scenes only observe their ViewModel; network and database work runs in a ViewModel or a domain service, not in a scene, helper or dialog.
- [ ] No new stateful top-level `object`; process-wide singletons go on a `ServiceRegistry` module.
- [ ] Moved or renamed classes went through `scripts/move-package.sh` (tests, XML class names, `baseline-prof.txt`, baselines and coverage floor updated in the same commit).
- [ ] Changes to `framework/lib/glview` or `glgallery` keep the render, layout and touch paths free of allocations, boxing and lambdas.
- [ ] Schema changes include a Room migration and the exported schema.
- [ ] No credentials, keystores or `local.properties` in the diff.
