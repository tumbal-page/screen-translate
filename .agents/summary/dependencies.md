# Dependencies

| Dependency | Version | Purpose |
|---|---|---|
| `kotlin-stdlib` | 1.9.10 | Kotlin standard library |
| `androidx.core:core-ktx` | 1.12.0 | Kotlin extensions for Android |
| `androidx.appcompat:appcompat` | 1.7.0 | AppCompat support |
| `com.google.android.material` | 1.9.0 | Material UI components |
| `androidx.constraintlayout` | 2.1.4 | Layout |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.6.1 | Lifecycle-aware coroutines |
| `com.google.mlkit:translate` | 17.0.3 | On-device EN→ID translation |
| `com.google.mlkit:text-recognition` | 16.0.0 | On-device OCR (Latin script) |
| `androidx.localbroadcastmanager` | 1.1.0 | (Included but unused after refactor) |

## ML Kit Models
- **Text Recognition**: bundled in APK via `mlkit:text-recognition`
- **Translation EN→ID**: downloaded on first use (~30MB), stored in app's `no_backup` directory

## Permissions Required
| Permission | Reason |
|---|---|
| `FOREGROUND_SERVICE` | Run foreground service |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Screen capture foreground type |
| `SYSTEM_ALERT_WINDOW` | Draw overlay over other apps |
| `POST_NOTIFICATIONS` | Show foreground service notification (Android 13+) |
| `INTERNET` | Download ML Kit translation model |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Keep service alive |
