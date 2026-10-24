# AGENTS.md: instructions for AI coding sessions

Read this first, then `README.md` (features, building, project layout, what has and hasn't been tested on hardware).

## What this project is

Sidereal is an Android companion app for the DJI Osmo Pro (Zenmuse X5): gimbal control, face tracking, manual camera control and
shooting sequences, over WiFi, on the DJI Mobile SDK v4. Modules: `app/` (phone app, Kotlin), `wear/` (Wear OS app) and
`wearprotocol/` (plain-Kotlin protocol and display logic shared by the phone and the watch). The app code layout is in the README.

## Workflow rules

1. **Test-driven development.** For every feature or bug fix:
   1. Write or extend a failing test first.
   2. Run it and confirm it fails for the expected reason.
   3. Implement until it passes, then refactor.
   4. After writing a guard or a fix, break it on purpose and confirm a test fails (mutation check).
   Unit tests go in the module's `src/test` and run with `tools/dbuild.sh testDebugUnitTest`. Keep logic in plain Kotlin
   (`wearprotocol/`, or pure classes in `app/`) so it can be tested without the DJI SDK or a device.
2. **Every change goes on a branch and a PR** for the user to merge. Never commit to `main`. Branch names follow the existing
   pattern (`feature/...`, `fix/...`, `docs/...`).
3. **Keep docs current.** Update `README.md` when user-facing behaviour, setup or the layout changes, and `CHANGELOG.md` for
   anything a user would notice. Update this file when conventions change.
4. **Say what was and wasn't tested on hardware.** The README separates tested from untested; keep that honest. A unit test
   passing does not mean the feature works on the Osmo, and the SDK does not run in an emulator.
5. **Save repeatable tasks as scripts** in `tools/` with a usage header, rather than repeating a multi-step command.

## Commands

- Build and test **in Docker**, not on the host: `tools/dbuild.sh assembleDebug`, `tools/dbuild.sh testDebugUnitTest`. The first
  run builds the `sidereal-build` image; Gradle's cache lives in the `sidereal-gradle` volume.
- The build needs `DJI_APP_KEY` and `android.useAndroidX=true`. They come from `gradle.properties` (gitignored); `dbuild.sh`
  passes placeholders when it is missing, so the build compiles but DJI registration fails at runtime.
- Release builds need `keystore.properties` (gitignored); without it they are unsigned.

## Rules

- **No secrets in the repo or logs.** The DJI app key, `keystore.properties`, `*.jks` and `*.keystore` stay out of git. Never print
  them.
- Keep dependencies minimal and the DJI SDK behind the `dji/` gateway so the rest of the app stays testable. There are `full`
  and `noDji` source sets; a change must build both.
- Don't write to or reconfigure the user's camera or gimbal from tests or scripts without being asked.

## Delegation (delegate-mcp)

The `delegate` MCP tools are available in this project. Use them; doing bulk work yourself costs Claude quota for nothing.

- **New files and whole-file rewrites go through `delegate_write`**, not `Write`. Pass `backend: "local"` (qwen3.6:35b, free) for short files; `deepseek-flash` for long or boilerplate-heavy ones. Small edits to existing files stay with `Edit`.
- **Bulk reading, summaries, mechanical refactors and test boilerplate go through `delegate_ask`** (`deepseek-flash`). Use `deepseek-pro` only for a hard problem or a second opinion.
- Name the backend every time; never leave a consequential call on `auto`. Write self-contained prompts (the worker has no history) and leave `max_tokens` unset.
- **Check what comes back** (read the file, run the tests) before calling it done. Design decisions, review and anything ambiguous stay with you.
- A `.delegate-private` marker makes the project local-only; a refused cloud call is correct, so switch to `local`.
- Full policy: `/mnt/user/Media/workspace/delegate-mcp/ORCHESTRATION.md`.
