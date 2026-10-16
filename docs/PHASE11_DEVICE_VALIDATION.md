# Phase 11 device and field validation

Date: 2026-10-17
Branch: `phase11_device_validation`
Starting checkpoint: `0db95e8a958dccde3a6864e7fe695773e659434d` (`phase10_security_hardening`)

## Environment and automated evidence

| Check | Outcome | Evidence |
|---|---|---|
| Physical device discovery | Blocked | `adb devices -l` returned only `List of devices attached`; no serial was listed. |
| Emulator discovery | Blocked | No emulator was listed by ADB. |
| Android version/model | Not available | No target device was connected. |
| Debug unit tests | Pass | `./gradlew testDebugUnitTest --rerun-tasks`: 217 tests, 0 failures, 0 errors, 0 skipped, 29 test classes. |
| Debug APK | Built | `app/build/outputs/apk/debug/app-debug.apk`, 19,739,074 bytes. |
| Instrumentation source compilation | Pass | `./gradlew :app:compileDebugAndroidTestKotlin`: success; 2 Phase 11 integration tests compile. |
| Instrumentation execution | Blocked | `connectedDebugAndroidTest` requires an attached compatible device/emulator; none was available. |
| Existing device action test | Not run | `Phase3DeviceActionTest` opens system/home apps and changes volume; it is unsuitable as a no-side-effect smoke check. |

Automated instrumentation added in `Phase11DeviceIntegrationTest`: launches the actual activity and checks package/backup/cleartext policy, then writes a disposable reminder row to an isolated database under `noBackupFilesDir`, closes/reopens the database, verifies persistence, and deletes its temporary database files. It does not change real reminder schedules or bypass authentication. Run with `./gradlew connectedDebugAndroidTest` on a test target.

## Manual field checklist

Record device model, Android version, app version, date, tester, result, and sanitized evidence for each run. Use disposable accounts and test data. Never grant permissions or authenticate without the device owner's action. Do not enable exact alarms for these checks.

| Area | Procedure and expected result | Actual result |
|---|---|---|
| Install/start/recovery | Install debug APK; launch; navigate; background/restore; force-stop/relaunch; inspect crash/ANR and migration logs. Upgrade over a disposable prior install and verify its records remain. | Not tested: no device/emulator. |
| Microphone permissions | Test grant, deny, deny again, and Settings recovery; start/stop/cancel recognition; compare voice against text input. Verify cancellation/stale callbacks do not run commands. | Not tested: live microphone and permission UI require device. |
| Languages | Exercise configured English and Tamil paths and Tanglish English fallback. Record speech-service/provider and Tamil availability. | Not tested: availability depends on device and installed speech services. |
| Speech output | Check start/completion/cancel/repeat/navigation and whether old output continues after a newer request. Record selected TTS locale and fallback. | Not tested: live TTS requires device. |
| Device credential | Use harmless confirmation; test correct credential, cancel, failed attempt, return to BG, expiry, replay, and changed action. Verify failure is clear and no action occurs. | Not tested: credential UI requires secured device. |
| Reminders/notifications | Create/edit/snooze/complete/cancel; test notification permission allow/deny; observe delivery, restart recovery, duplicate actions, stale actions, weekly recurrence, and timezone changes. Reboot only on suitable test hardware. | Not tested: system delivery and reboot require device. Delivery is inexact. |
| Messaging | Only with explicit user approval, a test account, selected test recipient, and harmless reviewed text: verify recipient/text review, device credential, cancellation, immediate pre-dispatch checks, single dispatch, and outcome. Never retry an uncertain send automatically. | Blocked: no device/account authorization supplied for live send. Do not send as part of unattended validation. |
| Accessibility/workflows | Inspect permission explanations/service status. Test cancel, foreground-app change, stale UI, malformed plans, unexpected app-switch approval, untrusted notification/UI content, and scheduled workflow review-only behavior. | Not tested: requires device settings and app transitions. Destructive or sensitive settings flows are excluded. |
| Storage/recovery/privacy | Verify records across process restart; verify a prior-schema database on a disposable copy; interrupt a workflow and confirm uncertain outcomes are not replayed; inspect logs for secrets/private content. | Persistence and migration have JVM/Robolectric coverage; device restart, real prior-user database, interrupted-process, and logcat checks are not tested. |

## Reproduction commands

From repository root on Windows:

```powershell
adb devices -l
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat :app:compileDebugAndroidTestKotlin
# Only when a compatible authorized device/emulator is attached:
.\gradlew.bat connectedDebugAndroidTest
```

Do not use `adb shell pm clear`, wipe app data, or install over a personal database for this validation. Capture logcat only after removing private notification, message, credential, and account content.

## Final run record

- Unit tests: 217 passed, 0 failed, 0 errors, 0 skipped (29 classes).
- Instrumentation: source compiled; 2 new test cases not executed because no target was connected.
- Build/APK size: `assembleDebug` succeeded; 19,739,074 bytes.
- Device/emulator: none detected.
- Confirmed defects/fixes: none identified from the available static/JVM evidence.
