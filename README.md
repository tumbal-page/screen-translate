# Manga Screen Translator

This project contains an Android application that performs real‑time screen translation, designed for reading manga or other English text content and displaying Indonesian translations directly on top of the original text. It uses Google's on‑device ML Kit APIs for both text recognition and translation, meaning translations happen without sending your data to the cloud after the model has been downloaded.

## Features

* **Real‑time screen capture**: Captures the entire display using `MediaProjection` and processes frames approximately every 100 ms.
* **On‑device OCR and translation**: Extracts Latin text (English) from the captured image via ML Kit's Text Recognition API and translates it into Indonesian using ML Kit's Translation API【952574096015423†L367-L425】【500306694237231†L277-L390】.
* **Accurate bounding boxes**: Each line of recognized text is associated with its bounding box; translations are drawn only over those regions so the rest of the screen stays interactive.
* **Overlay click‑through**: The overlay is transparent and does not steal touch events; you can keep scrolling your manga reader while translations appear on top.
* **Cache**: Previously translated lines are cached in memory to reduce latency when the same text reappears.
* **Foreground service**: Runs as a foreground service to avoid being killed by the OS and shows a small “ON” indicator in the corner when active.
* **Build via GitHub Actions**: Includes a GitHub Actions workflow that compiles a debug APK whenever you push to the repository.

## Requirements

* Android Studio Giraffe (or later) and SDK Platform 34.
* Physical Android device running Android 6.0 (API 23) or higher. (ML Kit translation requires `minSdkVersion 23`【500306694237231†L263-L274】.)
* Internet connectivity for the first model download (translation model is ~30 MB【500306694237231†L351-L353】). After the model is downloaded, translations happen on device.

## How it works

1. **Permissions**: On the first run, the app asks for two permissions:
   * *Draw over other apps*: Required to display overlay; the user must enable this in system settings.
   * *Screen capture permission*: Requested via `MediaProjectionManager.createScreenCaptureIntent()`. The system prompts the user and returns a result code and data intent that allow the service to capture the screen.
2. **Foreground service**: `MainActivity` launches `ScreenTranslatorService` with the screen‑capture data. The service sets up `MediaProjection`, creates an `ImageReader`, and listens for available frames. It also creates an `OverlayView` and attaches it to the `WindowManager` using `TYPE_APPLICATION_OVERLAY`, with flags so it doesn’t intercept touch events.
3. **Text recognition**: For each frame, the service converts the captured `Image` to a `Bitmap` and passes it to ML Kit’s `TextRecognizer` using `recognizer.process(image)`【952574096015423†L613-L715】. The returned `Text` object contains `TextBlock` and `Line` objects with the recognized text and bounding boxes【952574096015423†L725-L740】.
4. **Translation**: Each line’s text is passed to a `Translator` configured with source language English and target language Indonesian. The translation model is downloaded automatically on first use【500306694237231†L310-L356】. Translations are cached to avoid duplicate calls.
5. **Overlay drawing**: An `OverlayView` draws a semi‑transparent box and the translated text over each line’s bounding box. The view also draws a small green “ON” indicator to show the service is running.
6. **Update frequency**: The `ImageReader` callback fires as fast as the virtual display produces frames. The overlay is updated accordingly. You can adjust this by throttling updates if needed for battery life.

## Running the app

1. Import the project into Android Studio and let it download the dependencies. Alternatively, build from the command line with:

   ```bash
   ./gradlew assembleDebug
   ```

2. Install the generated `app-debug.apk` on a physical device.
3. Launch the app and tap **Start Translator**. Grant overlay permission when prompted (system settings page). Then grant screen‑capture permission.
4. Open your manga reader or any other app with English text. Translations should appear directly on top of the text. Use the system notification panel to stop the service if needed.

## Customization

* **Refresh interval**: The service currently processes every frame delivered by `ImageReader`. To throttle updates (e.g., to ~100 ms), you can add a timestamp check in `processFrame()` and skip frames that arrive too soon.
* **Accuracy**: ML Kit’s Text Recognition and Translation APIs generally achieve high accuracy on printed English text【952574096015423†L725-L740】. For vertical Japanese text or complex layouts, consider switching to the appropriate script options (e.g., `JapaneseTextRecognizerOptions`) and adjusting the source language for translation.
* **Model management**: The translation models occupy roughly 30 MB each【500306694237231†L351-L353】. You can explicitly download or delete models using `RemoteModelManager` as described in the ML Kit docs【500306694237231†L424-L444】.
* **Translation granularity**: The translation is done line by line; longer sentences spanning multiple lines might be split and translated separately, which could affect fluency.

## GitHub Actions

The `.github/workflows/android-build.yml` workflow compiles the app on every push and uploads the debug APK as an artifact. You can modify it to build release artifacts or run unit tests. The workflow uses the `gradle-build-action` for caching and runs with Java 17.

## Caveats

* This app is a proof of concept and not production‑ready. It may consume significant CPU/GPU resources when running continuously. Consider throttling frame processing or detecting changes to reduce workload.
* Overlay windows require user trust. Always inform users clearly what the app does and why it needs permissions.
* The translation is done line by line; longer sentences spanning multiple lines might be split and translated separately, which could affect fluency.

## License

This project is provided under the MIT License. Feel free to modify and adapt it for your own use.