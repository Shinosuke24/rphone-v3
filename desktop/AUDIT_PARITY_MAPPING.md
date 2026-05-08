# APK → Desktop Parity Audit

Scope: audit and mapping for these APK folders to Desktop equivalents:

- `ui/psu`
- `ui/probe`
- `ui/waveid`
- `ai`
- `util`

Format: Android file → Desktop equivalent → status → dependency → blocker → recommendation

---

## 1) ui/psu

- `app/src/main/java/com/rphone/v3/ui/psu/PsuFragment.kt` → `desktop/src/main/kotlin/com/rphone/v3/desktop/Main.kt` (PSU page)
  - status: sudah (PSU UI implemented within `Main.kt` as PSU page)
  - dependency: `DesktopSerialConnection`, `psuWaveState` (DesktopWaveformState), `FirmwareUpdateHelper` (for OTA), `SupabaseUploader` for upload
  - blocker: none functional; UI differences (Fragment → single-window) are expected
  - recommendation: keep PSU view logic in `Main.kt` but extract `PsuPage` component class under `desktop/ui/psu/PsuPage.kt` for parity and maintainability

- `app/src/main/java/com/rphone/v3/ui/psu/PsuViewModel.kt` → `desktop/src/main/kotlin/com/rphone/v3/desktop/Main.kt` (inline view-model behavior) / extract to `desktop/viewmodel/PsuViewModel.kt`
  - status: partial (logic present in `Main.kt`, not in dedicated ViewModel class)
  - dependency: `CoroutineScope`, `SerialConnection`
  - blocker: none
  - recommendation: create `PsuViewModel` (shared logic) that reuses business logic from APK ViewModel

## 2) ui/probe

- `app/src/main/java/com/rphone/v3/ui/probe/ProbeFragment.kt` → `desktop/src/main/kotlin/com/rphone/v3/desktop/Main.kt` (PROBE page)
  - status: sudah (PROBE UI implemented in `Main.kt`)
  - dependency: `ProbeValidator`, Probe-related utils in `desktop/util` (ProbeValidator.kt, CalibrationDialog.kt)
  - blocker: none
  - recommendation: extract `ProbePage` and `ProbeViewModel` in `desktop/ui/probe/` to match APK code structure and enable reuse

- `app/src/main/java/com/rphone/v3/ui/probe/ProbeViewModel.kt` → not yet extracted; logic mixed into `Main.kt`
  - status: belum (needs extraction)
  - recommendation: factor probe sampling, stabilization, and history buffering into `desktop/viewmodel/ProbeViewModel.kt`

- `app/src/main/java/com/rphone/v3/ui/probe/ProbeCompareFragment.kt` → Desktop: `desktop/engine/DtwMatcher.kt` + UI overlay missing
  - status: partial (DTW algorithm present as `DtwMatcher`, UI overlay compare screen not implemented)
  - dependency: `DtwMatcher`, `WaveIDDatabase`, `WaveIDManager`
  - blocker: UI overlay/Canvas code and synchronized rendering logic
  - recommendation: implement `ProbeCompareOverlay` in `desktop/ui/waveid/` reusing `DtwMatcher` and existing waveform buffers

## 3) ui/waveid

- `app/src/main/java/com/rphone/v3/ui/waveid/WaveIDMenuFragment.kt` → `desktop/src/main/kotlin/com/rphone/v3/desktop/Main.kt` (WaveID section tiles)
  - status: sudah (menu tiles present)
  - dependency: `WaveIDManager`, `WaveIDDatabase`, `SupabaseUploader`
  - blocker: none
  - recommendation: extract pages (`WaveRecordPage`, `WaveDatabasePage`, `WaveComparePage`) into `desktop/ui/waveid/` for parity

- `app/src/main/java/com/rphone/v3/ui/waveid/RekamFragment.kt` → `desktop/src/main/kotlin/com/rphone/v3/desktop/engine/BootRecorder.kt` + `Main.kt` record flow
  - status: partial (recording engine exists, `Main.kt` triggers recording; UI recording dialog can be improved)
  - dependency: `BootRecorder`, `DesktopFileStorage`
  - blocker: none
  - recommendation: move record UI into `desktop/ui/waveid/RekamPage.kt` and reuse `BootRecorder`

- `app/src/main/java/com/rphone/v3/ui/waveid/HasilAnalisaFragment.kt` → `desktop/src/main/kotlin/com/rphone/v3/desktop/managers/WaveIDManager.kt` + results panels in `Main.kt`
  - status: partial (analysis engine exists; result list UI minimal)
  - dependency: `DtwMatcher`, `WaveAnalyzer`, `WaveIDDatabase`
  - blocker: richer result UI (expandable details) not yet present
  - recommendation: implement result panel component and reuse `WaveAnalyzer` logic

- `app/src/main/java/com/rphone/v3/ui/waveid/DatabaseFragment.kt` → `desktop/src/main/kotlin/com/rphone/v3/desktop/database/WaveIDDatabase.kt` + `Main.kt` file listing
  - status: partial (database + index handling exists, UI for full CRUD can be improved)
  - dependency: `Sqlite (sqlite-jdbc)`, `WaveIDDatabase`
  - blocker: UI for editing labels/metadata missing
  - recommendation: implement `WaveDatabasePage` with TableView/CRUD wired to `WaveIDDatabase`

- `app/src/main/java/com/rphone/v3/ui/waveid/BandingkanFragment.kt` → Desktop: partially implemented (compare function trigger exists; overlay UI missing)
  - status: partial
  - dependency: `DtwMatcher`, `WaveIDDatabase`
  - blocker: overlay UI to render two synchronized canvases
  - recommendation: build `WaveComparePage` using two bound canvases and shared time base

## 4) ai

- `app/src/main/java/com/rphone/v3/ai/UartAiAnalyzer.kt` → `desktop/src/main/kotlin/com/rphone/v3/desktop/ai/UartAiAnalyzer.kt`
  - status: sudah (ported)
  - dependency: `AiConfigStore`, provider analyzers (`GroqAnalyzer`, `LiteLLMAnalyzer`, `GeminiAnalyzer`, `ClaudeAnalyzer`)
  - blocker: provider API keys/config at runtime
  - recommendation: keep provider logic in `desktop/ai/*` and extract common interface to `core/ai` module for reusability

- `app/src/main/java/com/rphone/v3/ai/GroqAnalyzer.kt` → `desktop/.../GroqAnalyzer.kt`
  - status: sudah
  - dependency: network (HttpURLConnection / OkHttp)
  - blocker: none

- `app/src/main/java/com/rphone/v3/ai/LiteLLMAnalyzer.kt` → `desktop/.../LiteLLMAnalyzer.kt`
  - status: sudah

- `app/src/main/java/com/rphone/v3/ai/GeminiAnalyzer.kt` → `desktop/.../GeminiAnalyzer.kt`
  - status: sudah

- `app/src/main/java/com/rphone/v3/ai/ClaudeAnalyzer.kt` → `desktop/.../ClaudeAnalyzer.kt`
  - status: sudah

- `app/src/main/java/com/rphone/v3/ai/WaveAnalyzer.kt` → `desktop/.../WaveAnalyzer.kt`
  - status: sudah

- `app/src/main/java/com/rphone/v3/ai/AiPromptBuilder.kt` → `desktop/.../AiPromptBuilder.kt`
  - status: sudah

## 5) util

Below are key utils in APK and desktop equivalents.

- `app/src/main/java/com/rphone/v3/util/BackupManager.kt` → `desktop/src/main/kotlin/com/rphone/v3/desktop/util/BackupManager.kt`
  - status: sudah
  - dependency: java.util.zip, File IO
  - blocker: none

- `app/src/main/java/com/rphone/v3/util/CsvExporter.kt` → `desktop/src/main/kotlin/com/rphone/v3/desktop/util/CsvExporter.kt`
  - status: sudah

- `app/src/main/java/com/rphone/v3/util/AutoReconnect.kt` → `desktop/src/main/kotlin/com/rphone/v3/desktop/util/AutoReconnect.kt`
  - status: sudah

- `app/src/main/java/com/rphone/v3/util/DataBufferManager.kt` → `desktop/src/main/kotlin/com/rphone/v3/desktop/util/DataBufferManager.kt`
  - status: sudah

- `app/src/main/java/com/rphone/v3/util/ProbeTtsManager.kt` → `desktop/src/main/kotlin/com/rphone/v3/desktop/tts/DesktopTtsManager.kt`
  - status: sudah

- `app/src/main/java/com/rphone/v3/util/OtaUpdateHelper.kt` → `desktop/src/main/kotlin/com/rphone/v3/desktop/util/FirmwareUpdateHelper.kt`
  - status: sudah

- `app/src/main/java/com/rphone/v3/util/PermissionHelper.kt` → desktop adapter: `desktop/src/main/kotlin/com/rphone/v3/desktop/platform/DesktopSerialConnection.kt` (no Android permissions)
  - status: adapted
  - blocker: Android-specific permission flow not applicable; desktop adapter used

- `app/src/main/java/com/rphone/v3/util/SupabasePollingWorker.kt` → `desktop/src/main/kotlin/com/rphone/v3/desktop/scheduler/SupabaseCloudPollingTask.kt`
  - status: partial (scheduler exists, verify parity of payload handling)

- `app/src/main/java/com/rphone/v3/util/SupabaseUploader.kt` → `desktop/src/main/kotlin/com/rphone/v3/desktop/SupabaseUploader.kt`
  - status: sudah

- `app/src/main/java/com/rphone/v3/util/ThemeManager.kt` → desktop: basic styling via CSS/inline styles in `Main.kt` (not full ThemeManager extracted)
  - status: partial
  - blocker: Theme resource files not extracted; recommend creating `desktop/res/theme.kt` or CSS file and mapping color constants

---

Summary of blockers (high level):

- UI overlay pages (ProbeCompare, Wave Compare) not yet ported — algorithm and DB exist; need Canvas overlay and synchronized timebase.
- Several APK ViewModel classes are not extracted on desktop; logic exists inline in `Main.kt`. For true parity and maintainability, refactor into `desktop/viewmodel/*` matching APK classes.
- Theme resource centralization missing — currently inline CSS strings in `Main.kt`. Create shared theme/constants to match Android resources.
- Provider API runtime configs (API keys, endpoints) need to be stored/loaded; `AiConfigStore` exists but verify contents.

Next steps recommended (prioritized):

1. Extract UI pages from `Main.kt` into `desktop/ui/{usb,psu,probe,waveid,uart,settings}` and create corresponding `ViewModel` classes to mirror APK structure.
2. Implement ProbeCompare and WaveCompare UI overlay using `DtwMatcher` and existing waveform buffers.
3. Centralize Theme and Colors into `desktop/res/Theme.kt` and apply consistently.
4. Create automated tests for DTW/WaveAnalyzer parity against sample `.rphp` fixtures.

---

File generated by desktop audit script on behalf of user request. Use this as the baseline for next implementation commits.
