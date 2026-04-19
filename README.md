# Moon Companion (Android)

Peripheral-side companion for [Moon-Firmware](https://github.com/KaraZajac/Moon-Firmware).
Advertises the Moon Companion GATT service, accepts `MoonRequest` writes from
a Flipper Zero running the Moon Companion service, and replies with
`MoonPhoneMessage{MoonResponse|MoonEvent}` notifications.

This is the real target peripheral for the Moon Companion protocol — the
Python `moon_phone.py` emulator in the firmware repo exists for quick
iteration, but macOS Core Bluetooth's SMP handling doesn't line up with the
Flipper's Just-Works request, so bring-up moved to Android.

## Status

Phase 1a scaffold. Advertises the service, accepts writes, handles:

- `PairRequest` — issues a random 16-byte auth token; Phase 1b will swap in
  ECDH + a 6-digit on-screen code.
- `GetPosition` / `SubscribePosition` / `UnsubscribePosition` — emits a
  walking fake-GPS fix (Flatiron, NYC) until wired up to
  `FusedLocationProvider`.
- `GetTime` — system time + IANA zone.
- `SendNotification` — logs; Phase 1b will fire a real system notification.

## Build

```
# One-time bootstrap: install Gradle (the wrapper JAR is not checked in).
brew install gradle

gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
```

Install on a connected phone with:

```
./gradlew installDebug
```

## Pairing flow

1. Open the app, tap **Start advertising**, grant BT + notification
   permissions. The status row will move to **Advertising**.
2. On the Flipper, open *Settings → Moon Companion → Pair*. The Flipper
   scans, connects, and sends a `PairRequest`.
3. The Android side logs `Paired! Issued auth_token=...` and returns the
   token in a `PairResponse`. The Flipper stores it and uses it on every
   subsequent request.

## Protocol

The single source of truth for the wire format lives in
[Moon-Firmware/applications/services/moon_companion/proto/moon_companion.proto](https://github.com/KaraZajac/Moon-Firmware/blob/dev/applications/services/moon_companion/proto/moon_companion.proto).
`app/src/main/proto/moon_companion.proto` is a copy; keep it in sync by hand
for now. We'll move to a git submodule or generated-artifact drop once the
schema stops churning.

## UUIDs

| Role                         | UUID                                   |
|------------------------------|----------------------------------------|
| Service                      | `df98ad38-b0b8-44c0-bd88-905db2d6b365` |
| RPC_TX (Flipper → phone)     | `7a30f8d0-2a4e-43b7-a383-1f786173991b` |
| RPC_RX (phone → Flipper)     | `902dac74-55ba-46d4-8ff4-6d40893ae052` |
