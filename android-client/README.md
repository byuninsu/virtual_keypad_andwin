# Android Client

Minimal Android keypad client for the relay server.

Current scope:

- connect to the Railway WebSocket relay as the `android` peer
- join a configurable `roomId`
- send `key_down`, `key_up`, and `release_all`
- provide a simple touch UI for relay verification against the Windows client

## Project Layout

- `app/`: Android application module
- `build.gradle.kts`: root Gradle build file
- `settings.gradle.kts`: module registration

## Open In Android Studio

1. Open `C:\virtual_keypad\android-client`
2. Let Android Studio sync the Gradle project
3. Run the `app` configuration on a device or emulator

## Default Test Flow

1. Start the Windows client with the same room id
2. Launch the Android app
3. Enter:
   - URL: `wss://virtualkeypadandwin-production.up.railway.app`
   - Room: any shared value such as `test123`
4. Tap `Connect`
5. Press and release keypad buttons to verify:
   - `key_down`
   - `key_up`
   - `release_all`

## Notes

- The app currently uses a foreground activity UI, not an overlay service yet
- Overlay mode can be added after the end-to-end relay path is verified
