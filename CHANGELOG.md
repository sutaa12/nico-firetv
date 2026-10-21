# Changelog

Changes below describe source changes, not proof of service or device compatibility.
See [validation status](docs/VALIDATION.md) for the evidence and remaining checks.

## 0.2.0 — 2026-10-21 (source release)

### Fixed

- Bound HTML entity scanning to the supported entity length. Repeated ampersands without a nearby semicolon no longer trigger repeated scans of the remaining watch-page text.
- Replace large watch-page regular-expression extraction with position-based parsing and parse embedded JSON once when loading series and quality information. This preserves the existing local fix for excessive native-memory use.
- Locate an available JDK on macOS in the installation helper before reporting missing Java.

### Added

- Regression tests for large watch-page attributes, malformed metadata, entity decoding boundaries, and large inputs containing repeated ampersands.
- An optional comment side panel, upload sorting by comment count, service-provided ranking genres with a fallback, and ranking back-button focus handling.
- An opt-in setting for sensitive listings, off by default. Playback remains subject to the service's account settings and entitlements.
- An English README, a device verification template, and a release checklist.

The side panel, sorting, ranking, memory fix, and installation changes were developed locally after the initial source publication.
Their inclusion here does not imply they were publicly released on those earlier development dates.

## 0.1.3 — 2026-09-26 (initial source publication)

- Publish the Fire TV client source under the MIT License, with Japanese build, usage, privacy, contribution, and validation guides.
- Document 59 passing unit tests and the limitations of automated and emulator verification at publication time.
- No GitHub Release or installable release APK was published with that initial source commit.
