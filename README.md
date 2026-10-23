# Grupo STR Driver — Android prototype

Native Android Kotlin prototype for municipal route GPS tracking. Android 8+.

Implemented: foreground-service GPS tracking, local SQLite points, accuracy filtering, persistent notification, manual start/stop, and GitHub Actions debug APK workflow.

Not yet implemented: Base44 authenticated sync, assigned-route selection, photo/video evidence, map display, production signing and field testing.

Build: GitHub Actions > Build Android APK. Gradle 8.9, Android Gradle Plugin 8.7.3, JDK 17.

Backend contract: authenticated assigned routes, batch GPS ingest, evidence upload and sync acknowledgement. Validate driver/route/branch authorization and stable point IDs. Never embed service-role keys in the APK.
