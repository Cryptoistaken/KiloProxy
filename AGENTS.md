# KiloProxy — Agent Rules

## Build (mandatory)
- Use ONLY the GitHub builder (`.github/workflows/build.yml`). Never build locally on this machine.
- After pushing code to `master`, check the workflow run status ONCE every 30 seconds until it finishes.
- On failure: read the failing step, fix the code, commit, and push again.
- On success: proceed with download/install per below.

## Download & Install
- Always download and install the **`app-arm64-v8a-release.apk`** from the `app-release` artifact of the successful run.
- Fresh-download to a clean directory before installing (stale APKs caused version/signature mismatch before).
- Since the persistent release keystore (GitHub secrets `RELEASE_KEYSTORE_*`) was introduced, every build is signed with the SAME key and `versionCode` increases monotonically (CI `GITHUB_RUN_NUMBER` + 100). Updates are install-overs and PRESERVE all app data — never uninstall just to update.

### Install flow
1. Check if ADB device `localhost:5557` is alive (`adb devices` → shows `device`).
2. If alive:
   - Install over the old app WITHOUT uninstalling, so profiles/usage data are preserved:
     `adb -s localhost:5557 install -r <apk>`
   - Only uninstall first if a signature mismatch or downgrade is reported:
     - `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / `INSTALL_FAILED_SIGNATURE` → signature differs (one-time migration from pre-keystore builds, or a different ABI build).
     - `INSTALL_FAILED_VERSION_DOWNGRADE` → installed versionCode is higher (e.g. a different ABI artifact); uninstall, or pull the matching ABI.
   - If not installed, install directly.
   - Verify with `adb -s localhost:5557 shell dumpsys package com.kiloproxy.app`.
3. If NOT alive: download the APK anyway, then STOP and wait for the user. Do NOT start any emulator/AVD on your own.
   - The user may skip the install, or start the emulator and tell you to install.
   - When the user later says to install after starting the device: install with `adb -s localhost:5557 install -r <apk>`; only uninstall first on signature/downgrade errors.

## Device notes
- App package: `com.kiloproxy.app`. Device ABI: supports `arm64-v8a`.
- Cross-ABI versionCode mismatch causes `INSTALL_FAILED_VERSION_DOWNGRADE` — install the ABI that matches the device; only uninstall before switching ABIs.
- Signature mismatch → the installed app was signed with an older key (pre-keystore ephemeral CI key, or a different ABI build); uninstall once, then all future updates install over cleanly.
