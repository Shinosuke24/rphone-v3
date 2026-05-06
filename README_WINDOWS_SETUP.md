# R-Phone V3 Desktop — Windows Setup Guide

## Quick Start

1. **Download paket Windows** dari GitHub release
2. **Extract ZIP** ke folder pilihan Anda
3. **Jalankan `rphone-v3-desktop.exe`** dari folder `launch4j`

## Requirements

- Windows 10 atau lebih baru
- Java 21 Runtime (jika included runtime tidak berfungsi)

## Troubleshooting

### Error: "This application requires a Java Runtime Environment 21.0.0"

Ini berarti bundled runtime tidak ditemukan atau tidak berfungsi. Solusi:

#### Option 1: Install Java (Recommended)

1. Download [Temurin JDK 21](https://adoptium.net/temurin/releases/?version=21)
2. Install dengan opsi "Set JAVA_HOME" atau "Add Java to PATH"
3. Restart komputer
4. Jalankan `.exe` lagi

#### Option 2: Manual Set Environment Variable

1. Download [Temurin JDK 21](https://adoptium.net/temurin/releases/?version=21) dan install
2. Buka Command Prompt sebagai Administrator
3. Jalankan:
   ```cmd
   setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-21.0.3+9"
   ```
   (Ganti path sesuai lokasi install Anda)
4. Restart komputer dan jalankan `.exe` lagi

#### Option 3: Run dari Command Prompt

Buka Command Prompt di folder `launch4j` dan jalankan:

```cmd
.\rphone-v3-desktop.exe
```

### "Cannot find port COM3" atau Serial Port Issues

1. Pastikan ESP32/Probe Meter sudah terkoneksi ke USB
2. Buka Device Manager dan cari "USB Serial Port"
3. Perhatikan nomor COM-nya (misal COM3, COM4)
4. Di app, pilih port yang sesuai dari dropdown

## File Structure

```
launch4j/
├── rphone-v3-desktop.exe       # Aplikasi executable
├── lib/                         # Library files
│   ├── javafx-*.jar
│   ├── kotlin-*.jar
│   └── ...
└── runtime/                     # Java Runtime (jika included)
    ├── bin/
    ├── lib/
    └── conf/
```

## Contact & Support

Jika masih gagal jalan, screenshot error dan kirim ke developer.

---

**Versi**: 3.0.0 (Windows)  
**Tanggal**: May 6, 2026
