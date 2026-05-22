# Architecture

## Overview

Single-service architecture. `CaptureService` is the only foreground service, holding `mediaProjection` type. It manages screen capture, OCR, translation, and both overlay views directly.

```mermaid
graph TD
    A[MainActivity] -->|startForegroundService + MediaProjection token| B[CaptureService]
    B --> C[MediaProjection / ImageReader]
    B --> D[ML Kit TextRecognizer]
    B --> E[ML Kit Translator EN→ID]
    B --> F[OverlayBoxesView\nfull-screen, NOT_TOUCHABLE]
    B --> G[OverlayView\ncontrol bubble, touchable]
    G -->|onPlayPause / onStop callbacks| B
    B -->|handler.post updateBoxes| F
```

## Key Design Decisions

- **Single service** — avoids Android 14+ restriction where `dataSync` + `mediaProjection` cannot coexist in separate services. All logic lives in `CaptureService`.
- **Dual overlay views** — `OverlayBoxesView` is `MATCH_PARENT + FLAG_NOT_TOUCHABLE` (draws translations at screen coordinates); `OverlayView` is `WRAP_CONTENT` (touchable control bubble).
- **Two-phase `startForeground`** — `onCreate` calls `startForeground` immediately (no type) to avoid 5-second timeout; `onStartCommand` upgrades to `mediaProjection` type after token is received.
- **On-device ML Kit** — no network calls after initial model download (~30MB).
- **Translation cache** — `ConcurrentHashMap<String, String>` avoids re-translating identical text lines.

## Android Version Constraints

| API | Behavior |
|---|---|
| Android 14+ (API 34) | `foregroundServiceType=dataSync` deprecated/killed; `mediaProjection` type required |
| Android 10+ (API 29) | `startForeground` must specify type |
| Android 8+ (API 26) | Must use `startForegroundService` |
| Android 6+ (API 23) | ML Kit translation minimum |
