# BG release readiness

Audit date: 2026-10-17

Starting checkpoint: `phase11_device_validation` at `144101ecfa38abfa13a534e2f4ea8a020be7c169`
Package: `com.boopathi.bg`

## Candidate status

This is a release-readiness record, not a production approval. BG's release variant builds without embedded Gemini or Groq keys, but no production signing identity or secure cloud-AI backend is configured. Physical-device validation has not been completed. The release APK and AAB are unsigned inspection artifacts; the APK cannot be installed until it is signed. Do not distribute them as production builds.

## Build environment and commands

The project uses JDK 21, Gradle wrapper 9.5.1, Android Gradle Plugin 9.3.0, compile SDK 37, target SDK 36, and min SDK 26. Configure Android Studio's SDK Manager with the required compile SDK and platform/build tools. `local.properties` is machine-local and ignored by Git; set `sdk.dir` to the SDK installation. Do not commit this file.

Target SDK 36 is required for new Google Play apps and updates submitted from August 31, 2026. Android 16 target behavior includes enforced edge-to-edge and predictive-back behavior; BG already uses system-bar insets and does not opt out of edge-to-edge, but those runtime paths were not device-tested in this environment. See [Google Play target API requirements](https://developer.android.com/google/play/requirements/target-sdk) and [Android 16 target behavior changes](https://developer.android.com/about/versions/16/behavior-changes-16).

From the repository root on Windows:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
.\gradlew.bat bundleRelease
.\gradlew.bat lint
.\gradlew.bat lintRelease
.\gradlew.bat :app:compileDebugAndroidTestKotlin
adb devices -l
# Only when a compatible authorized device/emulator is attached:
.\gradlew.bat connectedDebugAndroidTest
```

Install the debug APK for local testing with an authorized device connected:

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

The debug APK is not a production artifact. Provider keys placed in debug `BuildConfig` are extractable from the APK. Use only revocable, restricted development keys in a private ignored `local.properties` or environment variables; never commit them or reuse them in release.

## Artifact and signing record

| Variant | Expected output | Signing state |
|---|---|---|
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` | Android debug signing; test-only. |
| Release APK | `app/build/outputs/apk/release/app-release-unsigned.apk` | Unsigned in this build; apksigner verification failed as expected for the unsigned output. |
| Release AAB | `app/build/outputs/bundle/release/app-release.aab` | Unsigned in this build; it has no signature entry and `jarsigner -verify` reports unsigned. |

For artifact verification, use Android SDK `aapt dump badging` and `apksigner verify --verbose` for APKs, and `jarsigner -verify` for the AAB. Before distribution, the owner must provide an established production/upload key using a protected signing system or untracked local Gradle properties/CI secrets. Never create a substitute key for an existing app identity, commit key material, or expose passwords in logs. Verify the resulting APK/AAB certificate against the established Play/upload certificate before publishing. Publication is a separate, deliberate action.

## AI configuration and network behavior

Debug builds read `GEMINI_API_KEY` and `GROQ_API_KEY` from ignored `local.properties` or same-named environment variables. These values are compiled into the debug client and can be extracted. Release build configuration deliberately sets both fields to empty strings regardless of local developer configuration. The current app calls the Google Gemini and Groq HTTPS APIs directly; there is no backend, server-side key service, authenticated proxy, or quota/abuse-control layer in this repository. As a result, release cloud AI is unavailable by default. Do not add a permanent provider key to the client or rely on obfuscation to protect one.

For a future cloud-enabled release, first deploy a backend that authenticates BG users, holds provider secrets server-side, validates and limits requests, enforces per-user quotas/rate limits, detects abuse, uses HTTPS, avoids logging prompts/secrets/private payloads, and applies documented retention/deletion rules. Then configure a non-secret service identifier/URL through a reviewed client setting and test provider failure/fallback. No endpoint or credentials are supplied by this repository.

With cloud AI unconfigured, deterministic local command handling and supported local memory/reminder functions remain available; free-form AI answers and Gemini image analysis report an unavailable-service result. Do not describe cloud AI as operational in this release build.

## Local data, privacy, and deletion

Room stores the owner profile and preferred language; complete user/assistant conversation text and timestamps; memory/note titles, contents, types and importance; task/reminder titles, descriptions, due/recurrence/timezone/status metadata; and workflow summaries, statuses, progress, timestamps, and sanitized failure reasons. Conversations are loaded with up to 50 recent messages at startup, and recent turns/matching memories may be sent to the configured remote provider for a request. Gemini requests include the current prompt, recent conversation, owner name/persona, and matched memory contents. Groq requests include the prompt, recent conversation, and owner name/persona; the current Groq implementation does not add matched memory contents. Gemini multimodal requests send the user-selected image bytes (after validation/downsampling), question, optional task context, and active image-session turns. These transfers occur only when the corresponding provider is configured and the request path is used. Review the providers' terms and retention practices before using real personal data.

The app also reads visible foreground UI through Android Accessibility only after the user explicitly enables its system service. This access can expose sensitive screen content to BG's workflow logic; do not enable it for private screens unless needed. No raw UI tree, notification body, or image is intentionally persisted in Room. The notification listener requires a separate user-granted system setting and keeps a bounded, redacted notification buffer in memory; this buffer is not the Room conversation database.

The database is stored in Android's app-private storage, but it is **not encrypted at rest**. Do not store secrets or highly sensitive personal data with BG. SQLCipher/encrypted Room was not introduced because safe key lifecycle, recovery, and migration from existing plaintext databases need a separately tested design. The schema remains version 3 and existing migrations are unchanged.

Backup is disabled with `allowBackup=false`; explicit legacy and Android 12+ extraction rules exclude root, files, database, shared preferences, and external app data from cloud backup and device transfer. This prevents platform backup/transfer from carrying BG's private records under these rules; it does not encrypt the database or protect a compromised/unlocked device.

Deletion controls verified in the app include deleting or clearing completed reminders in Reminder Center and the `forget <keyword>` memory command. Conversation storage has a clear-history method in the data layer, but no clear-conversation control was found in the shipped main UI. No general task deletion control or visible UI for deleting the owner profile or clearing all workflow history/memories at once was verified. Uninstalling the app removes its app-private data through Android, but also removes all BG data. Treat these missing in-app deletion controls as a privacy limitation for production distribution.

## Permissions and Android component review

The manifest requests `RECORD_AUDIO` for voice input, `INTERNET` for configured cloud requests, `POST_NOTIFICATIONS` for reminder notifications on supported Android versions, and `RECEIVE_BOOT_COMPLETED` to restore reminders after boot/package replacement. A notification-listener service requires the user to grant notification access in Android Settings; it can observe notifications while enabled. The AccessibilityService requires user enablement in Settings and can inspect visible UI and perform user-directed interactions. Device-credential UI is required before protected message dispatch; authentication fails closed if no secure device credential is available. These permissions are not granted or bypassed automatically.

Manifest review: cleartext traffic is disabled. The launcher Activity is exported for the launcher intent. The AccessibilityService is exported only with Android's `BIND_ACCESSIBILITY_SERVICE` protection; the notification listener is protected by `BIND_NOTIFICATION_LISTENER_SERVICE`; the boot receiver is protected by `RECEIVE_BOOT_COMPLETED`; and the internal alarm receiver is not exported. Reminder `PendingIntent`s use immutable flags. Android may delay inexact reminder alarms, and background/accessibility behavior can vary by OS version and manufacturer.

## Security and regression review

- Release provider-key fields are forced empty in the release build type; debug keys, if configured, are client-visible.
- No signing configuration or production private signing material was found in tracked project configuration. Confirm untracked local/CI signing secrets separately without printing them.
- `allowBackup=false`, explicit extraction exclusions, and cleartext denial are configured.
- Release minification/obfuscation is disabled; no secret relies on obfuscation for protection.
- Message dispatch still requires reviewed confirmation and device credential; do not weaken these controls.
- Scheduled workflow requests remain review-only; uncertain external actions must not be retried automatically; reminders use inexact alarms.
- Room migrations 1→2 and 2→3 are retained. Existing migration regression coverage remains in the unit suite.
- Database encryption, production signing, secure cloud backend, and clear-all conversation/profile/history controls remain unresolved.

## Final release checklist and evidence

| Item | Status | Evidence / remaining action |
|---|---|---|
| Unit regression suite | Pass | `testDebugUnitTest --rerun-tasks`: 217 tests, 0 failures/errors/skips, 29 classes. |
| Debug APK | Built and verified | `app/build/outputs/apk/debug/app-debug.apk`, 19,616,807 bytes, package `com.boopathi.bg`, version 1.0 (1); apksigner verified with one signer (debug/test only). |
| Release APK | Built and verified | `app/build/outputs/apk/release/app-release-unsigned.apk`, 13,249,078 bytes, package `com.boopathi.bg`, version 1.0 (1), target SDK 36; apksigner reports unsigned. |
| Release AAB | Built and verified | `app/build/outputs/bundle/release/app-release.aab`, 12,895,522 bytes; package/version/target SDK confirmed from bundle manifest; no signature entry and jarsigner reports unsigned. |
| Lint/static analysis | Pass with warnings | `lint` and `lintRelease` succeeded; 0 errors and 30 warnings in each report. Warnings include dependency version notices and deprecated API/UI usage; no major dependency upgrades were made. |
| Instrumentation tests | Compiled, not executed | `:app:compileDebugAndroidTestKotlin` passed; no device/emulator was available for `connectedDebugAndroidTest`. |
| Release provider secrets | Pass | Generated release `GEMINI_API_KEY` and `GROQ_API_KEY` are empty. Secret-pattern scan found 0 matches in APK and AAB. Values were not printed. |
| Source secret scan | Pass | Tracked source/config scan found 0 common provider/private-key signatures. Current ignored local properties and process environment have both provider keys empty; values were not printed. |
| Manifest/network/backup | Reviewed | Merged release manifest confirms package identity, backup false, explicit extraction rules, cleartext false, and required permissions. App components use platform bind/boot protections where exported; AndroidX ProfileInstaller receiver is protected by `android.permission.DUMP`. |
| Production signing | Blocked | Release APK and AAB are unsigned. Owner must provide/use established production upload key securely and verify certificate identity. |
| Physical-device validation | Blocked | `adb devices -l` listed no targets. Connect an Android 16/API 36+ test phone or emulator; validate edge-to-edge, predictive back, permissions, notification, credential, voice/TTS, reboot, and safe messaging tests using the Phase 11 checklist. No device test is implied by JVM tests. |
| Cloud AI for release | Blocked or intentionally unavailable | No backend configured; release provider fields are empty. Deploy/configure the authenticated, rate-limited backend described above, or ship with cloud AI unavailable. |
| Privacy acceptance | Pending owner review | Review provider data flows, plaintext local database limitation, accessibility access, notification access, and missing deletion controls. |

## Final build record

- Starting branch/commit: `phase11_device_validation` / `144101ecfa38abfa13a534e2f4ea8a020be7c169`.
- Phase 12 commit: this commit contains the release-readiness work and record.
- Unit tests: 217 passed; 0 failed/errors/skipped (29 classes).
- Debug build: `assembleDebug` succeeded.
- Release APK/AAB: `assembleRelease` and `bundleRelease` succeeded; package `com.boopathi.bg`, version 1.0 (1), target SDK 36; paths and byte sizes recorded above.
- APK/AAB signing: debug APK has one debug signer; release APK and AAB are unsigned; no production signing credentials configured.
- Lint: `lint` and `lintRelease` succeeded with 0 errors and 30 warnings each.
- Secret scans: 0 common secret pattern matches in tracked source, release APK, and AAB; generated release provider fields empty.
- Devices: `adb devices -l` listed no devices/emulators; instrumentation and field checks not run.
- Push: not performed.

## Follow-up code review fixes (2026-10-17)

After the Phase 12 candidate was pushed, a source review identified messaging verification, foreground recovery, notification redaction, and owner-persona issues. The following source changes and regression tests have since been applied:

- Messaging now requires the exact WhatsApp package and exact normalized contact/draft matches. Phone recipients compare normalized digits. A send is only reported as verified when the input clears and the count of exact matching message bubbles increases; this confirms appearance in the chat UI, not recipient delivery or read status.
- A changed foreground app now stops recovery and requires the user to return and explicitly resume.
- Notification redaction inspects all text in notification extras, including expanded and nested message text, and recognizes short numeric codes when nearby wording identifies them as authentication codes. Unrelated numbers alone are not redacted.
- The Gemini multimodal provider now requires an owner-profile provider and builds its persona from the current profile rather than a fixed name.
- `testDebugUnitTest` passed after these changes: 222 tests, 0 failures, 0 errors. No physical device/emulator was available, and release APK/AAB files have not been rebuilt or signed from this follow-up source state.

The code changes do not resolve the release blockers above: production signing credentials, real-device validation, an authenticated cloud backend, database encryption, and user-facing history deletion controls remain outstanding.

## Additional privacy and accessibility hardening (2026-10-17)

- Notification sensitive terms and messaging safety phrases now use whole-word/whole-phrase matching, preventing ordinary words such as “shopping” and “shipping” from matching `pin`.
- Message safety rejects blank and oversized text, credential values, sensitive phrases, and numeric money-transfer instructions. Regression coverage includes harmless wording, credentials, and transfer examples.
- Accessibility clicks and text entry now reject targets from a different foreground package. Target lookup requires one exact match across text, content description, or view ID and aborts when the match is missing or ambiguous.
- Validation: `testDebugUnitTest` passed (232 tests, 0 failures/errors); `assembleDebug` passed; `:app:compileDebugAndroidTestKotlin` passed.
- `adb devices -l` returned no connected devices, so the Phase 3 and Phase 11 instrumentation tests could not be run on a device. The debug APK was rebuilt; the unsigned release APK/AAB were not rebuilt or signed.

## Automation & Messaging Release Hardening (2026-10-17)

- `AppAgent.OpenApp`: Returns `AppActionResultStatus.UNAVAILABLE` when `ToolRegistry` is null, and properly checks `ToolResult.status` to prevent false positive successes on failed app launches.
- `AppAgent.Tap` & `TypeText`: Rejects ambiguous controls by returning `REQUIRES_CLARIFICATION` whenever multiple actionable (clickable/editable and enabled) elements match the target query; fails safely if targets are disabled or not editable.
- `AppVerifier.verifyWindowActive`: Strictly validates target app strings (rejecting blank targets immediately) and timeouts (rejecting non-positive values); enforces exact matching against a trusted package directory (`TRUSTED_APP_PACKAGES`) to prevent substring phishing/spoofing.
- `WhatsAppMessagingAdapter.findSendButton`: Enforces foreground package check (`com.whatsapp`), restricts button discovery to verified labels and resource IDs, excludes audio/voice recording controls, and fails closed (`null`) when ambiguous matching controls exist.
- `AppAgent.SendMessage`: Verifies foreground package identity immediately before dispatching the send click to prevent cross-app click-jacking or accidental sends if the user switches away.
- Regression coverage: Added `Phase12ReleaseReadinessTest` with 14 comprehensive test cases covering OpenApp availability, launch failures, target ambiguity, blank targets, package spoofing, foreground shifts, ambiguous send buttons, message delivery baselines, and privacy filters.
- Validation: `testDebugUnitTest` passed (246 tests, 0 failures/errors across 30 test classes); `assembleDebug` passed. No physical device/emulator was connected for instrumentation tests.
