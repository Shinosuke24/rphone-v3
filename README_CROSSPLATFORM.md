# R-Phone V3 Cross-Platform (APK + Windows EXE)

**Sekarang project ini bisa dijalankan di kedua platform: Android APK dan Windows EXE!** ✨

## 🎯 Tujuan

Membuat satu codebase yang bisa digunakan untuk:
- ✅ **Android APK** (eksisting)
- ✅ **Windows EXE** (baru)

Tanpa perlu mengubah business logic berkali-kali.

---

## 📁 Struktur Project Baru

```
RPhoneV32/
├── core/                          # Logika bisnis bersama (platform-independent)
│   └── src/main/kotlin/
│       └── com/rphone/v3/core/
│           ├── platform/          # Abstraksi interfaces
│           │   ├── SerialConnection.kt
│           │   ├── FileStorage.kt
│           │   ├── PlatformNotification.kt
│           │   └── PlatformProvider.kt
│           └── model/
│
├── app/                           # Android APK
│   └── src/main/java/
│       └── com/rphone/v3/
│           ├── platform/          # Implementasi Android
│           │   ├── AndroidSerialConnection.kt
│           │   ├── AndroidFileStorage.kt
│           │   └── AndroidNotification.kt
│           ├── MainActivity.kt
│           └── ...
│
├── desktop/                       # Windows Desktop (EXE)
│   └── src/main/kotlin/
│       └── com/rphone/v3/desktop/
│           ├── Main.kt            # Entry point
│           └── platform/          # Implementasi Desktop
│               ├── DesktopSerialConnection.kt
│               ├── DesktopFileStorage.kt
│               └── DesktopNotification.kt
│
└── Dokumentasi:
    ├── CROSSPLATFORM_ARCHITECTURE.md  # Penjelasan arsitektur detail
    └── BUILD_GUIDE.md                 # Panduan build lengkap
```

---

## 🚀 Quick Start

### Build Android APK
```bash
./gradlew app:build
# Output: app/build/outputs/apk/
```

### Run Windows Desktop
```bash
./gradlew desktop:run
# Membuka jendela JavaFX
```

---

## 🔍 Bagaimana Cara Kerjanya?

### Arsitektur 3 Layer

```
┌─────────────────────────────────┐
│  UI Layer                         │
│  Android UI   │   Desktop UI      │
├──────────────────────────────────┤
│  Platform Abstraction Layer       │
│  - SerialConnection interface     │
│  - FileStorage interface          │
│  - PlatformNotification interface │
├──────────────────────────────────┤
│  Business Logic (Core)            │
│  - Models                         │
│  - Algorithms                     │
│  - Data Processing                │
└──────────────────────────────────┘
```

### Contoh: Sama di Kedua Platform

```kotlin
// File: core/src/main/kotlin/com/rphone/v3/core/ProbeService.kt
// ✅ Kode ini IDENTIK di kedua platform!

import com.rphone.v3.core.platform.PlatformProvider

class ProbeService {
    suspend fun saveMeasurement(data: String) {
        // Ini akan bekerja di Android & Windows secara otomatis
        val storage = PlatformProvider.getFileStorage()
        storage.save("measurement.json", data)
        
        val notify = PlatformProvider.getNotification()
        notify.showSuccess("Disimpan!")
    }
}
```

**Platform Android** → Gunakan `context.filesDir` (Android-specific)
**Platform Windows** → Gunakan `~/.rphone-v3/` (Windows-specific)

Tapi kodenya SAMA di `:core` module!

---

## 📦 Modul-Modul

### `:core` — Logika Bisnis Bersama
- ✅ Hanya Kotlin, tidak ada Android SDK
- ✅ Interfaces untuk platform operations
- ✅ Data models
- ✅ Business logic
- ✅ Bisa di-test tanpa Android/Desktop!

**Dependencies:**
```gradle
- Kotlin Coroutines
- Gson (JSON parsing)
- SLF4J (logging API)
```

### `:app` — Android
- ✅ Semua UI Android
- ✅ Implementasi `AndroidSerialConnection`, `AndroidFileStorage`, dll
- ✅ Inherit dari `:core`
- ✅ 80% code bisa direuse dari `:core`!

**Dependencies:**
```gradle
- androidx.* (semua library Android existing)
- com.github.mik3y:usb-serial-for-android
- com.rphone.v3.core (inherit)
```

### `:desktop` — Windows/Desktop
- ✅ JavaFX UI (modern, cross-platform)
- ✅ Implementasi `DesktopSerialConnection`, `DesktopFileStorage`, dll
- ✅ Inherit dari `:core`
- ✅ 80% business logic shared dengan Android!

**Dependencies:**
```gradle
- org.openjfx:javafx-* (UI)
- com.fazecast:jSerialComm (serial communication)
- com.rphone.v3.core (inherit)
```

---

## 💡 Bagaimana Cara Menggunakannya?

### Tahap 1: Pindahkan Code ke `:core`

**Sebelum** (Android-Only):
```
app/src/main/java/com/rphone/v3/model/ProbeData.kt
```

**Sesudah** (Cross-Platform):
```
core/src/main/kotlin/com/rphone/v3/core/model/ProbeData.kt
```

### Tahap 2: Impor di Kedua Platform

```kotlin
// In app/src/main/java/com/rphone/v3/MainActivity.kt
import com.rphone.v3.core.model.ProbeData  // ✅ Dari :core

// In desktop/src/main/kotlin/com/rphone/v3/desktop/Main.kt
import com.rphone.v3.core.model.ProbeData  // ✅ Sama!
```

### Tahap 3: Gunakan PlatformProvider untuk Operations

```kotlin
// In :core module (bisa digunakan di mana saja)
import com.rphone.v3.core.platform.PlatformProvider

object DataManager {
    suspend fun loadMeasurements(): List<String> {
        val storage = PlatformProvider.getFileStorage()
        // ✅ Di Android: baca dari context.filesDir
        // ✅ Di Windows: baca dari ~/.rphone-v3/
        return storage.listFiles()
    }
}
```

---

## 🛠️ Build & Run

### Android

```bash
# Build APK
./gradlew app:assembleDebug

# Install ke device
./gradlew app:installDebug

# Jalankan tests
./gradlew app:test
```

### Windows Desktop

```bash
# Jalankan langsung
./gradlew desktop:run

# Build JAR
./gradlew desktop:build

# Buat fat JAR (dengan semua dependencies)
./gradlew desktop:shadowJar
java -jar desktop/build/libs/rphone-v3-desktop.jar

# (Optional) Buat EXE dengan launch4j
# Lihat BUILD_GUIDE.md untuk setup
```

---

## 📝 File-File Yang Sudah Dibuat

| File | Fungsi |
|------|--------|
| `core/build.gradle.kts` | Konfigurasi module :core |
| `desktop/build.gradle.kts` | Konfigurasi module :desktop |
| `core/src/main/kotlin/com/rphone/v3/core/platform/` | Interfaces abstraksi |
| `app/src/main/java/com/rphone/v3/platform/` | Implementasi Android |
| `desktop/src/main/kotlin/com/rphone/v3/desktop/platform/` | Implementasi Windows |
| `CROSSPLATFORM_ARCHITECTURE.md` | Penjelasan arsitektur lengkap |
| `BUILD_GUIDE.md` | Panduan build lengkap |

---

## ✨ Contoh Real: ProbeData

### 1. Buat di `:core` (Sekali Saja)
```kotlin
// core/src/main/kotlin/com/rphone/v3/core/model/ProbeData.kt
data class ProbeData(
    val mode: String = "",
    val volt: Float = 0f,
    val display: String = ""
)
```

### 2. Gunakan di Android
```kotlin
// app/src/main/java/com/rphone/v3/MainActivity.kt
import com.rphone.v3.core.model.ProbeData

class MainActivity : AppCompatActivity() {
    fun displayProbe(data: ProbeData) {
        textView.text = data.display
    }
}
```

### 3. Gunakan di Windows (SAMA!)
```kotlin
// desktop/src/main/kotlin/com/rphone/v3/desktop/Main.kt
import com.rphone.v3.core.model.ProbeData

class RPhoneDesktopApp : Application() {
    fun displayProbe(data: ProbeData) {
        label.text = data.display
    }
}
```

**1 file = 2 platform! 🎉**

---

## 🎯 Langkah Berikutnya

### 1. **Move Platform-Independent Code ke `:core`**
- [ ] Pindahkan semua `model/*.kt` ke `core/src/main/kotlin/com/rphone/v3/core/model/`
- [ ] Pindahkan `algorithm/*.kt` ke `core/src/main/kotlin/com/rphone/v3/core/algorithm/`
- [ ] Pindahkan utility classes ke `core/src/main/kotlin/com/rphone/v3/core/util/`

### 2. **Update `:app` (Android)**
- [ ] Remove duplikasi code dari `:core`
- [ ] Tambahkan di `MainActivity.onCreate()`:
  ```kotlin
  PlatformProvider.initialize(
      AndroidSerialConnection(this),
      AndroidFileStorage(this),
      AndroidNotification(this)
  )
  ```

### 3. **Enhance `:desktop` UI**
- [ ] Tambahkan measurement display di JavaFX
- [ ] Tambahkan device selection dropdown
- [ ] Tambahkan chart untuk visualisasi data

### 4. **Test Kedua Platform**
- [ ] Build & test APK di Android
- [ ] Build & test EXE/JAR di Windows

---

## 📚 Dokumentasi Lengkap

Baca kedua file ini untuk detail lebih lanjut:

1. **[CROSSPLATFORM_ARCHITECTURE.md](./CROSSPLATFORM_ARCHITECTURE.md)**
   - Penjelasan arsitektur detail
   - Migration guide
   - Best practices
   - Examples

2. **[BUILD_GUIDE.md](./BUILD_GUIDE.md)**
   - Panduan build lengkap
   - Troubleshooting
   - CI/CD setup
   - Distribution

---

## 🎨 Fitur

| Fitur | Android | Windows |
|-------|---------|---------|
| Serial Communication | ✅ USB Serial for Android | ✅ jSerialComm |
| File Storage | ✅ app-private directory | ✅ ~/.rphone-v3/ |
| Notifications | ✅ Toast + Vibration | ✅ Console + Beep |
| UI | ✅ Native Android | ✅ JavaFX |
| Business Logic | ✅ Shared | ✅ Shared |

---

## 💬 Bantuan

**Q: Bagaimana saya bisa menambah fitur baru?**  
A: Tambahkan di `:core` module, maka otomatis tersedia di kedua platform!

**Q: Bagaimana jika satu platform butuh logic khusus?**  
A: Buat interface baru di `:core`, implementasi di `:app` dan `:desktop`.

**Q: Bisaa build jaadi exe tunggal (standalone)?**  
A: Ya! Lihat `BUILD_GUIDE.md` bagian "Create Windows EXE".

**Q: Berapa besar file EXE?**  
A: ~150-200MB (termasuk JRE). Bisa dikurangi dengan optimization.

---

## 🚀 Status

- ✅ Project structure setup
- ✅ Multi-module Gradle configuration
- ✅ Platform abstractions (interfaces)
- ✅ Android implementations
- ✅ Desktop implementations (JavaFX)
- ✅ Documentation
- ⏳ Next: Move existing code ke `:core`

---

**Siap untuk cross-platform development!** 🎉

Untuk pertanyaan, lihat dokumentasi atau jalankan:
```bash
./gradlew tasks  # Lihat semua available tasks
```
