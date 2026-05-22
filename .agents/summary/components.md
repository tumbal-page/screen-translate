# Components

## MainActivity
- Checks ML Kit translation model availability on launch
- Downloads model if missing
- Requests `POST_NOTIFICATIONS` permission (Android 13+)
- Requests overlay permission (`SYSTEM_ALERT_WINDOW`)
- Requests screen capture via `MediaProjectionManager.createScreenCaptureIntent()`
- On permission granted: starts `CaptureService` with result code + data intent

**Critical:** Screen capture permission must be requested while Activity is foreground. Do NOT start any overlay before requesting this permission.

## CaptureService
Core service. Foreground type: `mediaProjection`.

Lifecycle:
1. `onCreate` → `startForeground` (no type, immediate) → setup recognizer + translator
2. `onStartCommand` → upgrade `startForeground` to `mediaProjection` type → `setupOverlay()` → `startProjection()`
3. `onDestroy` → stop projection → remove overlays → close translator

Key methods:
- `startProjection()` — creates `VirtualDisplay` via `MediaProjection`, sets `ImageReader` listener
- `processFrame()` — converts `Image` to `Bitmap`, runs ML Kit OCR
- `handleTextBlocks()` — translates each line (with cache), collects `BoxData`, posts to `boxesView`
- `setupOverlay()` — adds `OverlayBoxesView` (MATCH_PARENT) and `OverlayView` (WRAP_CONTENT) to `WindowManager`

## OverlayView
Custom `View` for the draggable control bubble.

States: collapsed (bubble only) ↔ expanded (bubble + panel with Play/Pause/Stop buttons)

Callbacks exposed:
- `onPlayPause: ((Boolean) -> Unit)` — called when play/pause toggled
- `onStop: (() -> Unit)` — called when stop tapped
- `onDrag: ((dx: Float, dy: Float) -> Unit)` — called on drag gesture
- `onExpandChanged: ((Boolean) -> Unit)` — called when panel expands/collapses

## OverlayBoxesView
Full-screen transparent `View`. Draws semi-transparent black boxes with white translated text over each OCR bounding box. Only draws when `isPlaying = true`.

## BoxData
`@Parcelize data class` with `left, top, right, bottom, text`. Coordinates are in screen pixels matching `ImageReader` dimensions.
