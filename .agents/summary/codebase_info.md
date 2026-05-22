# Codebase Info

**Project:** Manga Screen Translator  
**Package:** `com.example.screentranslator`  
**Language:** Kotlin  
**Platform:** Android (minSdk 23, targetSdk 34, compileSdk 34)  
**Version:** 1.11 (versionCode 11)  
**Build System:** Gradle (Groovy DSL)  
**Java Compatibility:** Java 17  

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 1.9.10 |
| OCR | ML Kit Text Recognition 16.0.0 (Latin script) |
| Translation | ML Kit Translate 17.0.3 (EN→ID, on-device) |
| Screen Capture | Android MediaProjection API |
| Overlay | WindowManager TYPE_APPLICATION_OVERLAY |
| CI/CD | GitHub Actions (debug APK on push) |

## Source Files

| File | Role |
|---|---|
| `MainActivity.kt` | Entry point, permission flow, model check |
| `CaptureService.kt` | Core service: screen capture + OCR + translate + overlay |
| `OverlayView.kt` | Control bubble UI (play/pause/stop/drag) |
| `OverlayBoxesView.kt` | Full-screen transparent view for translation boxes |
| `BoxData.kt` | Parcelable data class for bounding box + text |
| `CaptureApplication.kt` | Application class (minimal) |
