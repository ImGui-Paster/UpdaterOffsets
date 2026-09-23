# Tools_modified — AIDE packaging workaround

This project is prepared for AIDE/Gradle and includes a project-local APK output directory:

```text
aide-output/main.apk
aide-output/main-release.apk
```

## IMPORTANT: one AIDE setting is required

The error `\/main.apk-unzipaligned-unsigned: EROFS` is produced by AIDE's own legacy packaging path, not by Java or Gradle. EROFS means the filesystem is read-only. A project ZIP cannot change an AIDE application setting by itself.

In AIDE Pro open:

**Settings → Advanced Settings → Build & Run**

Set:

- **Enable Gradle** — ON
- **Redefine APK build path** — ON
- **Enable adrt debug file** — OFF

Then restart AIDE. The AIDE community documentation for AideLua specifically recommends these settings and notes that `Redefine APK build path` is required to avoid APK errors.

After that, open this project and build Debug. If AIDE asks for the APK output path, select the project-local `aide-output/main.apk` path.

## Gradle tasks

Debug APK:

```text
:app:assembleDebug
```

A copy is also produced at:

```text
aide-output/main.apk
```

Release APK:

```text
:app:assembleRelease
```

A copy is also produced at:

```text
aide-output/main-release.apk
```

The Gradle output remains under `app/build/outputs/apk/` as usual.

## Gradle versions

- Android Gradle Plugin: 8.7.1
- Gradle: 8.9
- compileSdk: 35
- targetSdk: 35
- Java: 17

Java source, resources and the existing release keystore configuration were preserved.
