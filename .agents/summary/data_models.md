# Data Models

## BoxData
```kotlin
@Parcelize
data class BoxData(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val text: String  // translated text
) : Parcelable
```
Coordinates are screen pixels from `ImageReader` (full display resolution).

## OverlayView.Box
```kotlin
data class Box(val rect: Rect, val translation: String)
```
Internal model used by `OverlayBoxesView`. Converted from `BoxData` in `CaptureService.sendBoxesToOverlay()`.

## Translation Cache
```kotlin
ConcurrentHashMap<String, String>  // original → translated
```
In-memory, unbounded. Lives in `CaptureService` for the service lifetime.
