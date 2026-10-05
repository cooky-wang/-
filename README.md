# Fast Auto Clicker

A tiny, offline Android single-point auto clicker aimed at Android 16 (API 36).

## Design

- Kotlin
- `compileSdk = 36`
- `targetSdk = 36`
- `minSdk = 26`
- No `INTERNET` permission
- No ads / analytics / account system
- No root / Shizuku / ADB
- Uses `AccessibilityService.dispatchGesture()`
- Uses `TYPE_ACCESSIBILITY_OVERLAY`, so it does **not** request `SYSTEM_ALERT_WINDOW`
- Callback-driven loop: the next tap is sent from `GestureResultCallback.onCompleted()`

## Build APK without Android Studio

1. Create a GitHub repository and upload the **contents** of `FastAutoClicker` to its root, including `.github/workflows/build-apk.yml`. Do not upload only the ZIP or nest the project inside another directory.
2. Open **Actions** → **Build APK** → **Run workflow**.
3. When it finishes, download the `AutoClicker-APK` artifact.
4. Inside it is `app-debug.apk`.

The workflow installs JDK 17, Android API 36, Build Tools 35.0.0 and Gradle 8.13. AGP is 8.13.2 and Kotlin is 2.3.0. A Gradle Wrapper is not required: `gradle/actions/setup-gradle@v4` supplies Gradle. It runs:

```bash
gradle :app:assembleDebug --stacktrace
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The workflow checks that the APK is nonempty before uploading. Missing artifacts fail the job.
Success means a green **Build APK** run with an **AutoClicker-APK** artifact containing `app-debug.apk`.
Static checks alone do not establish a successful Android build.

To push an extracted project from PowerShell (replace the URL with your repository):

```powershell
cd 'D:\codex_work\连点器\FastAutoClicker'
git init -b main
git add .
git diff --cached --check
git diff --cached --stat
git commit -m "Prepare Android API 36 cloud APK build"
git remote add origin https://github.com/YOUR_ACCOUNT/YOUR_REPOSITORY.git
git push -u origin main
```

Pushing `main` or `master` triggers the workflow. For another branch, select it under Actions → Build APK → Run workflow after the workflow exists on the default branch. Inspect failed build logs, fix the reported error and push again.

## First use

1. Install the APK.
2. Open the app.
3. Tap **Open Accessibility settings**.
4. Enable **Fast Auto Clicker**.
5. A floating control panel and red `◎` target appear.
6. Drag `◎` to the screen coordinate you want.
7. Choose `MAX`, `1 ms`, `2 ms`, `5 ms`, `10 ms`, `20 ms`, or `50 ms`.
8. Tap **START**.
9. Tap or long-press **STOP** to stop.

While clicking, the red target is removed from the screen, so it cannot intercept the injected tap. It returns after stopping.

## MAX mode

MAX uses a 1 ms gesture stroke and **no artificial delay between completed gestures**. A new gesture is dispatched only from the previous gesture's `onCompleted()` callback.

`1 ms` does **not** mean 1000 CPS. Android's gesture injection, device firmware, the target app and scheduling overhead determine the actual rate.

## Benchmark

Benchmark tests these gesture durations for about 3 seconds each:

- 1 ms
- 2 ms
- 5 ms
- 10 ms
- 20 ms

It records average CPS, peak 1-second CPS, cancellations and `dispatchGesture(false)` counts, then recommends the duration with the highest measured average CPS.

**Warning:** Benchmark really taps the selected target for the full test. Put the target somewhere safe before starting it.

## Safety stops

Clicking stops when:

- STOP is tapped
- STOP is long-pressed
- the screen turns off
- the accessibility service is interrupted/destroyed

Only one gesture is in flight at once; gestures are not pre-queued.
STOP invalidates pending callbacks and removes retry tasks. Android may finish the last already injected gesture; it cannot start another tap. If START is pressed before that callback returns, wait briefly and press START again.
Rotation stops clicking before adjusting the target and controls. Service unbind/destroy removes overlays and safety receivers; reconnecting the same service preserves the outstanding gesture guard.

## Permissions

The manifest requests no ordinary dangerous permissions and no network permission.

The accessibility service itself must be manually enabled by the user because Android requires `BIND_ACCESSIBILITY_SERVICE` for this service type.

## Privacy

Everything is local. There is no Internet permission, analytics, advertising or data upload.
