# Grupo STR Driver — Native GPS Companion v0.2

Native Android GPS engine used by the existing Grupo STR municipal driver web application on Base44.

## How a driver uses it

1. Install the APK once on their Android phone (Android 8+).
2. Open the existing Grupo STR Base44 web application, select the assigned municipal route, and press **Iniciar ruta**.
3. Base44 issues a route-scoped GPS session and opens `strdriver://track?...`.
4. Android requests precise location permission on first use, starts a persistent foreground-service GPS collector, and returns the driver to Base44.
5. Android batches GPS points to Base44, buffering locally when offline. Base44's route map automatically refreshes.
6. Pressing **Finalizar ruta** in Base44 stops native tracking; Android also polls for the closed-route state.

If Android does not open automatically, the Base44 driver screen offers **Abrir GPS nativo**. A **GPS web de respaldo** option remains for phones without the APK.

No manual Route ID, QR code, second login, or administrator token is used by the APK.

## Integration contract

The Base44 project defines `base44/functions/gpsRouteBridge/entry.ts`, which implements:

- Authenticated `{ action: "issue", route_id }`: checks that the caller is an admin or that the route is assigned to their Driver record; issues a random route-specific session credential (SHA-256 stored server-side, 36-hour expiry).
- Token-authenticated `{ action: "sync", route_id, token, points: [...] }`: up to 50 GPS points per batch, validated and de-duplicated by `client_point_id`, acknowledgements returned to SQLite for safe retries.
- Token-authenticated `{ action: "status", route_id, token }`: detects route closure and stops recording. Buffered points have a one-hour grace period after completion.

The handoff includes an HTTPS endpoint returned by Base44 itself. The APK validates the endpoint host and function path, uses HTTPS, and contains no API secret. The database stores each point with a UUID and an unsynced flag; WorkManager retries every 15 minutes when online, while the foreground service attempts synchronization every 25 seconds.

## Build and installation

GitHub Actions → **Build Android APK** → artifact `GrupoSTRDriver-debug-apk`.
Gradle 8.9; Android Gradle Plugin 8.7.3; Kotlin 2.0.21; JDK 17; target SDK 35.

**Testing notice:** This artifact is debug-signed on a GitHub runner, not a production release. Different runner-generated debug signing certificates can prevent upgrades over an older APK; uninstalling a previous debug version may be necessary (this clears local unsynchronized GPS data). Configure a stable private release keystore before production rollout.

**Outstanding field verification:** end-to-end HTTP/session handoff against the published Base44 function, locked-screen GPS consistency on the target phone, intermittent-network recovery, OEM battery optimization settings, and production signing. The APK does not replace Base44's map, evidence uploads, or operational driver UI.
