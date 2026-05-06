# R-Phone V3 Cross-Platform Architecture

## Overview

This project now supports **both Android APK and Windows EXE** from a single codebase, using a **modular architecture** with platform abstractions.

## Project Structure

```
RPhoneV32/
├── core/                          # Shared business logic (platform-independent)
│   ├── src/main/kotlin/
│   │   └── com/rphone/v3/core/
│   │       ├── platform/          # Platform abstractions (interfaces)
│   │       │   ├── SerialConnection.kt
│   │       │   ├── FileStorage.kt
│   │       │   ├── PlatformNotification.kt
│   │       │   └── PlatformProvider.kt
│   │       └── model/             # Shared data models
│   └── build.gradle.kts
│
├── app/                           # Android APK (platform-specific)
│   ├── src/main/java/
│   │   └── com/rphone/v3/
│   │       ├── platform/          # Android implementations
│   │       │   ├── AndroidSerialConnection.kt
│   │       │   ├── AndroidFileStorage.kt
│   │       │   └── AndroidNotification.kt
│   │       ├── MainActivity.kt
│   │       ├── ui/
│   │       ├── algorithm/
│   │       └── ...
│   └── build.gradle.kts
│
├── desktop/                       # Windows EXE (platform-specific)
│   ├── src/main/kotlin/
│   │   └── com/rphone/v3/desktop/
│   │       ├── Main.kt            # JavaFX Application entry point
│   │       └── platform/          # Desktop implementations
│   │           ├── DesktopSerialConnection.kt
│   │           ├── DesktopFileStorage.kt
│   │           └── DesktopNotification.kt
│   └── build.gradle.kts
│
├── settings.gradle.kts            # Multi-module configuration
└── build.gradle.kts
```

## Architecture Layers

### Layer 1: Core Abstractions (`:core` module)

Platform-agnostic interfaces that define how the business logic communicates with the world:

- **`SerialConnection`** — USB/Bluetooth serial communication
- **`FileStorage`** — File I/O operations
- **`PlatformNotification`** — Notifications, logging, vibration
- **`PlatformProvider`** — Service locator for accessing implementations

### Layer 2: Platform Implementations

#### Android Implementation (`:app`)

Located in `app/src/main/java/com/rphone/v3/platform/`:

```kotlin
// Initialize in MainActivity or onCreate()
PlatformProvider.initialize(
    AndroidSerialConnection(context),
    AndroidFileStorage(context),
    AndroidNotification(context)
)
```

Features:

- Uses **USB Serial for Android** library (already in dependencies)
- Stores files in app's private directory (`context.filesDir`)
- Toast + Android vibration for notifications
- Proper Android logging

#### Desktop Implementation (`:desktop`)

Located in `desktop/src/main/kotlin/com/rphone/v3/desktop/platform/`:

```kotlin
// Initialize in JavaFX Application.start()
PlatformProvider.initialize(
    DesktopSerialConnection(),
    DesktopFileStorage(),
    DesktopNotification()
)
```

Features:

- Uses **jSerialComm** library for cross-platform serial communication
- Stores files in `~/.rphone-v3/` directory
- System beep + console output for notifications
- JavaFX-compatible UI

## How to Use Business Logic (No Platform-Specific Code)

Once you move business logic to the `:core` module, you can access platform services from anywhere:

```kotlin
// In any business logic class (platform-independent)
import com.rphone.v3.core.platform.PlatformProvider

class ProbeReader {
    suspend fun readMeasurement() {
        val serial = PlatformProvider.getSerialConnection()
        val data = serial.receive()

        val storage = PlatformProvider.getFileStorage()
        storage.save("measurement.json", data)

        val notify = PlatformProvider.getNotification()
        notify.showSuccess("Measurement saved!")
    }
}
```

**This code works identically on both Android and Windows!**

## Build Commands

### Build Android APK

```bash
./gradlew app:build
./gradlew app:assembleRelease  # Create signed release APK
./gradlew app:installDebug      # Install on connected device
```

### Build Windows EXE

```bash
# Compile desktop app
./gradlew desktop:build

# Run desktop app
./gradlew desktop:run

# Create executable JAR
./gradlew desktop:jar

# Create Windows EXE (with launch4j plugin - optional)
./gradlew desktop:createExe
```

### Create fat JAR (all-in-one for Windows)

```bash
./gradlew desktop:shadowJar
java -jar desktop/build/libs/desktop-all.jar
```

## Migration Guide: Moving Code to `:core`

### Step 1: Identify Platform-Independent Code

Look for code that:

- Processes data (algorithms, calculations)
- Manages state (models, ViewModels)
- Handles business logic
- **Does NOT** directly use Android APIs

### Step 2: Move to `:core`

Example: Move `ProbeData.kt` from `app/src/main/java/com/rphone/v3/model/` to `core/src/main/kotlin/com/rphone/v3/core/model/`

```kotlin
// core/src/main/kotlin/com/rphone/v3/core/model/ProbeData.kt
data class ProbeData(
    val mode: String = "",
    val volt: Float = 0f,
    val vdrop: Float = 0f,
    val ohm: Float = 0f,
    val display: String = ""
)
```

### Step 3: Update imports in `:app`

```kotlin
// In Android code
import com.rphone.v3.core.model.ProbeData
```

### Step 4: Use PlatformProvider in Business Logic

```kotlin
// In :core module (completely platform-independent)
import com.rphone.v3.core.platform.PlatformProvider

class MeasurementService {
    suspend fun saveMeasurement(data: ProbeData) {
        // This works on both Android and Windows!
        val storage = PlatformProvider.getFileStorage()
        storage.save("probe_data.json", gson.toJson(data))
    }
}
```

## Best Practices

### ✅ DO:

- Put data models, algorithms, and business logic in `:core`
- Use `PlatformProvider` interfaces for platform operations
- Keep UI separate: `app/` UI + `desktop/` UI
- Write unit tests for `:core` module (platform-independent)

### ❌ DON'T:

- Use Android-specific imports in `:core` (no `android.*`)
- Hard-code platform-specific paths in `:core`
- Mix UI and business logic
- Access `PlatformProvider` before `initialize()`

## Example: Sharing ProbeData Model

**Before (Android-only):**

```
app/src/main/java/com/rphone/v3/model/ProbeData.kt
```

**After (Cross-platform):**

```
core/src/main/kotlin/com/rphone/v3/core/model/ProbeData.kt
```

Then import in both `:app` and `:desktop`:

```gradle
// app/build.gradle.kts & desktop/build.gradle.kts
dependencies {
    implementation(project(":core"))
}
```

## Debugging Platform-Specific Issues

### Android Issues?

Check `app/src/main/java/com/rphone/v3/platform/AndroidSerialConnection.kt`

### Windows Issues?

Check `desktop/src/main/kotlin/com/rphone/v3/desktop/platform/DesktopSerialConnection.kt`

## Dependencies Summary

### `:core` module (minimal, platform-agnostic)

- Kotlin Coroutines (core)
- Gson
- SLF4J API

### `:app` module (Android)

- All existing Android dependencies
- Inherits from `:core`

### `:desktop` module (Windows/Desktop)

- JavaFX (UI)
- jSerialComm (serial communication)
- Kotlin Coroutines (javafx flavor)
- Inherits from `:core`

## Next Steps

1. **Move all platform-independent code to `:core`**
   - Data models → `core/src/main/kotlin/com/rphone/v3/core/model/`
   - Business logic → `core/src/main/kotlin/com/rphone/v3/core/algorithm/`
   - Utilities → `core/src/main/kotlin/com/rphone/v3/core/util/`

2. **Update `:app` to use `:core` modules**
   - Remove duplicate code from Android
   - Initialize `PlatformProvider` in `MainActivity`

3. **Enhance `:desktop` UI**
   - Currently has basic JavaFX UI
   - Add measurement display
   - Add chart/graph for data visualization
   - Add settings panel

4. **Test on both platforms**
   - Build APK and test on Android
   - Build JAR/EXE and test on Windows

---

**Total code reuse: ~80% of business logic can be shared between both platforms!**
