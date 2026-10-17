# Computer-free firmware releases

Implemented app path: public GitHub repository → latest stable release → bounded
HTTPS download → SHA-256, ESP32-C6/project/version/image checks → existing BLE OTA
→ on-device ECDSA verification → reboot/version confirmation.

The app checks a saved repository on launch and provides an explicit refresh
button. Download and installation are separate actions. No GitHub token or private
firmware key is stored in the app. Public repositories only are supported.
The checksum manifest detects corrupt downloads; it is not a cryptographic trust
anchor. The installed ESP firmware is the signature trust anchor.

## App self-update (manager 0.4.0+)

Open Settings → App update. The app uses the configured public repository and
fetches `android-update.json` plus `app-debug.apk` from its latest stable release.
It requires a newer Android versionCode, bounded size, matching SHA-256, package
name/version, and the same installed APK signer. It opens the Android package
installer using a narrow FileProvider URI. Enable installation from this app when
Android requests it, return, and press Install again. Installation requires user
confirmation; it is not a silent update. Keep the stable signing keystore backup.

The release workflow generates app metadata from Gradle output rather than
assuming that Android and firmware versions are identical. A firmware v0.4.2
release can contain Android manager 0.4.0. App installs are unavailable while
firmware OTA is active. App-update downloads are foreground operations; retry
after process death. Cancelling the installer does not report a successful update.

## One-time setup before leaving the computer

1. Project source is pushed to `https://github.com/sh-yu/MatterforWall` (the app's
   default source). Release v0.4.2 is published with firmware 0.4.2 and app 0.4.0.
   Public binary/APK downloads were verified against their manifest hashes.
2. Add Actions secret `FIRMWARE_SIGNING_KEY_B64` containing the base64 of the
   **existing** `.secrets/wmb_ota_signing_key.pem`. A new key will not work with
   the installed device. Never paste keys into issues, chats, or source files.
3. Add `ANDROID_DEBUG_KEYSTORE_B64` from the existing installation's Android
   debug keystore, so cloud APKs retain their signing identity. These are
   development APKs, not production store-signed releases. Increment Android
   versionCode for each app update. Keep an offline key backup.

   Both signing secrets above were provisioned on 2026-10-18 using the existing
   local identities, encrypted to GitHub's repository public key. Do not replace
   them with newly generated keys.
4. Run `Publish signed OTA` in Actions, or push a `vMAJOR.MINOR.PATCH` tag matching
   firmware `PROJECT_VER`. Missing keys, failed Android checks, invalid image
   identity, or OTA slot overflow stop publication. All release assets are
   uploaded to a draft before it becomes visible to the app.
5. Install and test the updated manager APK on the phone before leaving. Enter
   `owner/repository` in its firmware screen, check/download a release, connect
   to ESP over BLE and start OTA. Keep ESP powered and app foreground throughout.

## While away

Request code changes from an environment connected to this repository with write
access and a build runner. Increment firmware PROJECT_VER, push reviewed changes,
and run the release workflow in GitHub's mobile browser. This local desktop
conversation cannot upload or edit code while its computer is switched off.
GitHub Actions builds already-pushed code; it cannot receive uncommitted changes
from a powered-off computer. GitHub credentials remain on the authorized coding
environment/GitHub, not in the manager app.

The workflow intentionally builds the currently working BLE management firmware.
Matter builds remain pending, and must preserve a proven OTA transport before
deployment. Do not install the current experimental Matter image expecting the
custom BLE OTA GATT service to work after reboot.

## Verification limits

`HARDWARE_TEST_PENDING`: newly changed app APK installation, public-release fetch,
download interruption, signed release installation, signature rejection, rollback,
and post-update reconnect on the physical phone/ESP. The live v0.4.2 release is
published and manager 0.4.0 is installed over ADB. Firmware 0.4.2 has been staged
on the phone, but its installation on ESP has not been confirmed.

Elevator call transmission remains unavailable. AGENTS.md requires an approved
building interface and documented, reviewed exact protocol. No such evidence or
tested command implementation exists in this repository. Ownership alone does
not supply the electrical/protocol information required to implement a call.
