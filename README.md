# Omni Files — Android universal file viewer

A private, **offline-first Android application** for opening and previewing a wide range of files. Built entirely with Android platform APIs, Java 17, and Android Gradle Plugin 8.7.3, with no third-party runtime dependencies.

**Minimum Android:** 8.0 / API 26. **Target Android:** API 35.

## Capabilities

| Format | Built-in behavior | Limitations |
|---|---|---|
| PDF | Page-by-page renderer | No text search, annotations, encrypted PDFs or PDF forms |
| JPG, PNG, GIF, WebP, HEIC, BMP, AVIF and other device-supported images | Decoded image preview | Depends on Android/codec; downsampled for memory safety |
| MP3, M4A, WAV, FLAC and device-supported audio | MediaPlayer with seeking | Codec dependent |
| MP4, MKV, WebM, MOV and other device-supported video | VideoView, native controls | Codec dependent |
| HTML, SVG | Sandboxed WebView offline rendering | No JavaScript, remote resources, full fidelity |
| TXT, JSON, CSV, XML, Markdown, source code, subtitle formats | Text preview; JSON indented | First 2 MB; no syntax highlighting |
| DOCX, XLSX, PPTX, ODT, ODS, ODP | Readable **text and data extraction** | NOT a faithful Microsoft Office document renderer |
| EPUB | Chapter text extraction | No CSS, images or exact reading order |
| ZIP, JAR, CBZ, APK | Browse entries and preview an extracted entry | Not RAR / 7z, 250-entry listing limit |
| TTF, OTF | Font sample | May fail for some variable or incompatible fonts |
| SQLite DB | Read-only table/row preview | 12 tables, 10 rows each |
| Unknown binary formats | File metadata + hex dump, Open-in-another-app | Not semantic decoding |

**Important:** No application can perfectly render every file type ever created. Proprietary, encrypted, compressed and specialized formats (such as PSD, CAD, RAW, RAR, 7z, executables, machine learning models) require format-specific decoders or other Android apps. This app never claims those bytes are a faithful render.

## Build APK

### Option A — Android Studio (recommended)

1. Open the **`omni-files`** folder in Android Studio.
2. Install Android SDK Platform 35 if prompted.
3. Choose `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
4. The installable debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

### Option B — GitHub Actions

1. Create a repository and upload all files in this folder, including `.github/workflows/build-apk.yml`.
2. Open the **Actions** tab and select **Build Android APK**, then **Run workflow**.
3. Download the `OmniFiles-APK` artifact from the successful run and extract `app-debug.apk`.

The workflow uses Java 17, Android SDK 35 and Gradle 8.10.2 to build an APK. You can also run `gradle assembleDebug` in a system with Android SDK 35 and Gradle 8.10.2 installed.

## Privacy / security

- No INTERNET permission; files remain on device.
- No broad storage permissions; picker uses Android Storage Access Framework.
- XML entity loading is disabled.
- HTML/SVG renders with JavaScript, network and file access disabled.
- Local temporary copies limited to 180 MB; archive entry extraction to 50 MB.
- SQLite browsing is read-only.
- File sharing or opening in another app is user-initiated.

## Known limitations

- File name extensions are used to choose built-in previewers, so misnamed files may show an error and a hex dump.
- Some document providers and ephemeral sharing URIs cannot be reopened from Recents without granting access again.
- No password input or decoding for locked documents.
- Exporting a contained file from an archive to another app is not available in this version.
- No automated Android device/emulator test has been performed.
