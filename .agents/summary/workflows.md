# Workflows

## App Start Flow

```mermaid
sequenceDiagram
    User->>MainActivity: Tap "Start Translator"
    MainActivity->>MainActivity: checkModelAvailability()
    alt Model not ready
        MainActivity->>User: Show "Download" button
        User->>MainActivity: Tap Download
        MainActivity->>MLKit: downloadModelIfNeeded()
    end
    MainActivity->>MainActivity: ensureNotificationPermission()
    MainActivity->>MainActivity: check canDrawOverlays()
    alt No overlay permission
        MainActivity->>System: ACTION_MANAGE_OVERLAY_PERMISSION
    end
    MainActivity->>System: createScreenCaptureIntent()
    User->>System: Grant screen capture
    System->>MainActivity: onActivityResult(RESULT_OK, data)
    MainActivity->>CaptureService: startForegroundService(resultCode, data)
```

## Screen Capture & Translation Flow

```mermaid
sequenceDiagram
    CaptureService->>CaptureService: onCreate → startForeground (no type)
    CaptureService->>CaptureService: onStartCommand → startForeground (mediaProjection type)
    CaptureService->>CaptureService: setupOverlay() → add OverlayBoxesView + OverlayView
    CaptureService->>MediaProjection: createVirtualDisplay()
    loop Every frame (when isPlaying=true)
        ImageReader->>CaptureService: onImageAvailable()
        CaptureService->>MLKit: TextRecognizer.process(bitmap)
        MLKit-->>CaptureService: Text blocks + bounding boxes
        loop Each line
            alt Cached
                CaptureService->>CaptureService: use cache
            else Not cached
                CaptureService->>MLKit: Translator.translate(text)
                MLKit-->>CaptureService: translated string
            end
        end
        CaptureService->>OverlayBoxesView: handler.post { updateBoxes(list) }
        OverlayBoxesView->>OverlayBoxesView: postInvalidate() → onDraw()
    end
```

## Play/Pause/Stop

- **Play** → `isPlaying = true`, `OverlayBoxesView.setPlaying(true)` → frames start processing
- **Pause** → `isPlaying = false`, `clearBoxes()` → frames skipped, overlay cleared
- **Stop** → `CaptureService.stopSelf()` → `onDestroy` → projection stopped, overlays removed
