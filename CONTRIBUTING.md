# Contributing to LingoTrail

Thanks for taking the time to contribute! This guide covers bug reports, pull requests, code style,
and the release conventions used by the project.

By participating, you agree to keep discussions respectful and to license your contributions under
the project's [Apache-2.0 license](LICENSE).

## Reporting bugs

- Search [existing issues](https://github.com/SF-Zhou/LingoTrail/issues) first.
- Open a new issue with the **Bug report** form. It asks for the app version, Android version and
  device, the area of the app, and reproduction steps.
- Include `adb logcat` output or screenshots when they help. **Never paste your DeepSeek API key**,
  a signing keystore, or any other secret — mask or remove it before posting.

## Suggesting features

Use the **Feature request** form. Describe the problem you are trying to solve, the behaviour you
would like, and any alternatives you considered. Keep in mind the project's scope: a focused,
on-device, bring-your-own-key translation app.

## Development setup

Prerequisites are JDK 17 and the Android SDK (Platform 35). Android Studio Ladybug or newer is
recommended, but a command-line build works too:

```bash
git clone https://github.com/SF-Zhou/LingoTrail.git
cd LingoTrail
./gradlew assembleDebug
```

See [Build from source](README.md#build-from-source) in the README for details, including the
`local.properties` / `ANDROID_HOME` setup.

## Pull request flow

1. Fork the repository and create a topic branch from `main`, e.g. `fix/overlay-colour-sampling`.
2. Keep the change focused; avoid unrelated refactors or reformatting.
3. Add or update unit tests in `app/src/test` (and Compose UI tests in `app/src/androidTest` when
   the change is UI-related).
4. Run the checks locally before opening the PR:

   ```bash
   ./gradlew testDebugUnitTest lintDebug assembleDebug
   ```

5. Open a pull request against `main` and fill in the pull request template. CI must pass before a
   maintainer reviews the change.
6. Link the issue the PR addresses (`Closes #123`) where applicable.

If your change affects the UI, include a screenshot or short screen recording in the PR description.
If it changes user-visible behaviour, update `CHANGELOG.md` under `## [Unreleased]`.

## Code style

- **Kotlin official code style** — the project sets `kotlin.code.style=official`; use 4-space
  indentation, and let Android Studio format the file before committing. `.editorconfig` mirrors
  these defaults for other editors.
- **Compose** — keep composables small and stateless where practical, hoist state to the screen or
  view model, and use `Modifier` parameters in a consistent order.
- **Resources** — user-facing strings belong in `app/src/main/res/values/strings.xml`, with
  Simplified Chinese translations added to `values-zh-rCN/strings.xml`. Do not hardcode display text.
- **XML and YAML** — 2-space indentation, no tabs.
- **Scope** — do not mix formatting-only changes with functional changes in the same PR.
- **Comments** — document non-obvious decisions, not the obvious; use KDoc for public APIs.

## Tests

- JVM unit tests live in `app/src/test/`.
- Compose UI / instrumented tests live in `app/src/androidTest/` and need a connected device or
  emulator.
- Run everything relevant before submitting:

  ```bash
  ./gradlew testDebugUnitTest
  ./gradlew lintDebug
  ./gradlew connectedDebugAndroidTest   # optional, needs a device
  ```

## Commit messages

Use short imperative subjects, optionally with a Conventional Commits-style prefix:

```
feat(viewer): add double-tap zoom toggle
fix(overlay): sample background colour from the correct region
docs: document the release secrets
```

Reference issues in the body (`Closes #42`) and keep each commit focused on one logical change.

## Tags and releases

Maintainers follow [Semantic Versioning](https://semver.org/) and Keep a Changelog:

- Update `CHANGELOG.md` for the version and commit it.
- Tag the release as `vMAJOR.MINOR.PATCH` (for example `v1.0.0`).
- Release candidates and betas use a suffix such as `v1.1.0-rc1` or `v1.1.0-beta2` and are published
  as GitHub pre-releases.

Pushing a `v*` tag triggers the release workflow, which builds the signed, minified APK, verifies
it, and publishes the GitHub Release. See [Releasing](README.md#releasing) for the signing setup.

## Secrets

- Never commit an API key, `local.properties`, a keystore (`.jks`/`.keystore`),
  `keystore.properties`, or anything under `signing/` or `dist/`.
- Use `scripts/generate-keystore.sh` for release signing material and keep it backed up offline.
- The built-in `-PdefaultApiKey=` Gradle property is only for private demo builds; never commit the
  key or publish a build that contains one.

## Developer certificate

No CLA or DCO is required. Contributions are accepted under the Apache-2.0 license; if you prefer,
you can add a `Signed-off-by` line with `git commit -s`.
