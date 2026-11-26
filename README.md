# Sakura Mobile

Native Android companion for the Sakura Dashboard on Bazzite. It uses the Dashboard’s dark purple and pink palette and runs without Google Play services or a cloud account.

## Build and install

Open this directory in Android Studio with Android SDK 35 installed, or run:

```sh
./gradlew :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android 8.0 or newer is required. The pairing scanner needs a camera. Keep the phone and PC on the same LAN for discovery and Wake-on-LAN. The PC also needs Wake-on-LAN enabled for its network adapter and power state.

## PC interface expected by the app

This is the Android client’s proposed v1 interface. The existing Sakura services do not expose this interface.

- The pairing QR contains JSON with `version` (1), `host` (hostname or IP address), `port`, `fingerprint` (SHA-256 of the DER TLS leaf certificate as lowercase hex), `token` (32–128 URL-safe characters), and `mac` (the PC adapter’s colon-delimited MAC).
- Requests use HTTPS with the pinned certificate and an `Authorization: Bearer <token>` header. Android encrypts the pairing record with an AES-GCM key held in Android Keystore.
- Advertise `_sakura-mobile._tcp` using DNS-SD with instance name `Sakura-<first 16 hex digits of fingerprint>`. The app verifies the certificate and token before switching to the advertised address.
- `GET /v1/status` returns an object with optional `name`, `stats` (`cpu`, `memory`, `gpu` in percent), `media` (`title`, `artist`, `volume` in percent), and `lighting` (`keyboard`, `ram`, `mouse`, each with `color` as a hex RGB value and `brightness` in percent). Omit unavailable readings and devices.
- `POST /v1/command` accepts `action` values `previous`, `play_pause`, `next`, `lock`, and `sleep`. Use `volume` with `percent`, and `lighting` with `device`, `color`, and `brightness`. Return HTTP 204 or a JSON object on success.
- Wake-on-LAN sends a standard magic packet directly over the LAN.

Compose feature panels, transport, discovery, and credential storage have separate entry points so a future Sakura Canvas client can reuse pairing and transport.
