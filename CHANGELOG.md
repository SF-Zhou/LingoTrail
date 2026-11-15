# Changelog

All notable changes to LingoTrail are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-11-16

Initial public release.

### Added

- Capture a photo with the system camera or import one from the gallery, with EXIF orientation
  correction and thumbnail generation on import.
- Text recognition through the DeepSeek OCR API (`POST /v1/tools/ocr`), using the rich layout
  response so lines and paragraphs keep their structure.
- Translation of the recognised lines into the user's target language with the `deepseek-flash`
  chat model, keeping translations aligned one-to-one with the original lines.
- In-place translation overlay: the background colour is sampled from the original photo and the
  translation is auto-fitted into the area occupied by the original text.
- Local history that keeps every scan; scans can be reopened, retranslated, deleted individually, or
  cleared all at once.
- Full-screen viewer with pinch-to-zoom, panning, double-tap zoom, and a button that toggles the
  translation overlay on and off.
- Sharing a composited image of the photo with its translation drawn on top.
- Onboarding flow that verifies the user's DeepSeek API key and lets them choose the target
  language. The target language is pre-selected from the system locale.
- Settings for the API key, target language, API endpoint override, and history management, plus
  links to the source code, license, and privacy policy.
- English and Simplified Chinese user interface, with all strings externalised for translation.
- Bring-your-own-key model: the API key is stored only in the app's private storage and excluded
  from Android auto-backup; the app has no accounts and no analytics.
- Material 3 Compose UI with a splash screen and adaptive launcher icon.
- Automated CI (unit tests, Android Lint, debug APK on every push and pull request) and a release
  workflow that builds, signs, verifies and publishes the release APK with a SHA-256 checksum.
- `scripts/generate-keystore.sh` to create the release signing keystore and print the GitHub Actions
  secrets setup, and `scripts/release.sh` for local release builds.
- Project documentation: README, contributing guide, privacy policy, issue forms, pull request
  template, and EditorConfig.

[Unreleased]: https://github.com/SF-Zhou/LingoTrail/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/SF-Zhou/LingoTrail/releases/tag/v1.0.0
