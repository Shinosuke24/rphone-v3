# Quick Start: Building for Both Android APK & Windows EXE

## Prerequisites

- **JDK 17+** (already configured in project)
- **Android SDK** (for APK builds)
- **Gradle** (included with `gradlew`)

## Building for Android

### Option 1: Debug APK (for testing)

```bash
./gradlew app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Option 2: Release APK (for distribution)

```bash
./gradlew app:assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

### Install on Android Device

```bash
./gradlew app:installDebug
# Device must be connected via USB
```

### Run Tests

```bash
./gradlew app:test
```

---

## Building for Windows EXE

### Option 1: Run Directly (Development)

```bash
./gradlew desktop:run
# Opens JavaFX window
```

### Option 2: Create Standalone JAR

```bash
./gradlew desktop:build
# Output: desktop/build/libs/desktop-*.jar
```

### Run the JAR

```bash
java -jar desktop/build/libs/desktop-*.jar
```

### Option 3: Create Fat JAR (All Dependencies)

First, add to `desktop/build.gradle.kts`:

```gradle
plugins {
    // ... existing plugins
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

tasks {
    shadowJar {
        minimize()
        archiveFileName.set("rphone-v3-desktop.jar")
    }
}
```

Then build:

```bash
./gradlew desktop:shadowJar
java -jar desktop/build/libs/rphone-v3-desktop.jar
```

### Option 4: Create Windows EXE (Advanced)

Add `launch4j` plugin to `desktop/build.gradle.kts`:

```gradle
plugins {
    // ... existing
    id("edu.sc.seis.gradle-launch4j") version "3.0.1"
}

launch4j {
    mainClassName = "com.rphone.v3.desktop.MainKt"
    icon = "src/main/resources/icon.ico"
    outputDir = "build/launch4j"
    jar = "libs/${tasks.shadowJar.get().archiveFileName.get()}"
    dontWrapJar = false
}
```

Then:

```bash
./gradlew desktop:shadowJar
./gradlew desktop:launch4j
# Output: desktop/build/launch4j/rphone-v3-desktop.exe
```

---

## CI/CD: Automated Builds

### GitHub Actions Example

Create `.github/workflows/build.yml`:

```yaml
name: Build APK & EXE

on: [push]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: "17"

      - name: Build Android APK
        run: ./gradlew app:assembleRelease

      - name: Build Windows JAR
        run: ./gradlew desktop:shadowJar

      - name: Upload Artifacts
        uses: actions/upload-artifact@v3
        with:
          name: builds
          path: |
            app/build/outputs/apk/release/
            desktop/build/libs/
```

---

## Project Structure for Build

```
RPhoneV32/
├── build.gradle.kts              # Root configuration
├── settings.gradle.kts           # Module definitions
├── gradle.properties             # Version variables
├── gradle/wrapper/               # Gradle wrapper
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
```

---

## Troubleshooting

### "Module :core not found"

```bash
# Make sure settings.gradle.kts includes :core
# Check: include(":core")
./gradlew --refresh-dependencies clean build
```

### "Cannot resolve com.rphone.v3.core"

```bash
# Rebuild the core module
./gradlew core:build
./gradlew app:build
```

### "JavaFX classes not found"

```bash
# Ensure desktop/build.gradle.kts has JavaFX dependencies
./gradlew desktop:clean desktop:build
```

### "Serial port not found"

- **Android**: Check USB permissions in AndroidManifest.xml
- **Desktop**: Ensure device is connected and drivers installed

---

## Distribution

### For Android (Google Play Store)

1. Create signed release APK: `./gradlew app:assembleRelease`
2. Upload to Play Store Console
3. See `app/build.gradle.kts` for signing configuration

### For Windows (Direct Download)

1. Create standalone EXE: `./gradlew desktop:launch4j`
2. Distribute `desktop/build/launch4j/rphone-v3-desktop.exe`
3. Or create installer with NSIS/MSI

### For Windows (Portable JAR)

1. Create fat JAR: `./gradlew desktop:shadowJar`
2. Package with JRE runtime: `jpackage`
3. Users run: `java -jar rphone-v3-desktop.jar`

---

## Version Management

Edit `gradle.properties`:

```gradle
appVersion=3.0.0
buildNumber=21
```

Then reference in modules:

```gradle
// app/build.gradle.kts
versionName = "${appVersion}"
versionCode = "${buildNumber}".toInt()
```

---

## Performance Tips

### Faster Builds

```bash
# Use Gradle daemon
./gradlew --daemon

# Parallel builds
./gradlew -x desktop:test app:build  # Skip tests, parallel

# Incremental builds
./gradlew -t app:run  # Continuous mode
```

### Smaller APK

```bash
# Enable R8/ProGuard
buildTypes {
    release {
        isMinifyEnabled = true
    }
}
```

### Faster Desktop App

- Use UPX executable compression (optional)
- Remove unused JavaFX modules
- Profile with VisualVM

---

## Cross-Platform Testing

```bash
# Test Android
./gradlew app:test
./gradlew app:connectedAndroidTest

# Test Desktop
./gradlew desktop:test

# Test all
./gradlew test
```

---

**Ready to deploy on both platforms!** 🚀
