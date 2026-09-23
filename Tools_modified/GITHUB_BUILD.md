# Tools — GitHub Actions build

This project contains a GitHub Actions workflow that builds the debug APK automatically.

## Build

1. Create a GitHub repository.
2. Upload all files from this project.
3. Open **Actions**.
4. Select **Build Android APK**.
5. Click **Run workflow**.
6. When the job finishes, open the run and download the **app-debug-apk** artifact.

The workflow uses:
- JDK 17
- Android SDK 35
- Gradle from the project's `gradle-wrapper.properties`
- `:app:assembleDebug`

The generated APK is taken from:
`app/build/outputs/apk/debug/`
