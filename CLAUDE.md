# CLAUDE.md

## Engineering Standards

Follow the rules at https://gitlab.com/postgres-ai/rules/-/tree/main/rules — always pull latest before starting work.

## Deployment

- Version schema: `vMAJOR.MINOR.PATCH` (e.g. `v0.1.0`). Tag on main.
- Build: `./gradlew assembleDebug` → APK at `app/build/outputs/apk/debug/app-debug.apk`
- Install on tablets via sideloading (no Play Store yet)

## Code Review

All changes go through PRs. Before merging, run a REV review (https://gitlab.com/postgres-ai/rev/) and post the report as a PR comment. REV is designed for GitLab but works on GitHub PRs too.

Never merge without explicit approval from the project owner.

## Stack

- Pure Java (no Kotlin, no XML layouts)
- Android SDK 34, minSdk 26
- UDP multicast (239.255.42.1:9876) for LAN-only peer-to-peer communication
- Zero dependencies beyond AndroidX AppCompat
