# DesignArena Android Wrapper

A minimal, zero-chrome Kotlin + AndroidX WebView wrapper for [https://www.designarena.ai/](https://www.designarena.ai/) with native file uploads, export downloads, Chrome Custom Tabs OAuth routing, Material You adaptive icons, offline recovery, and automated GitHub Actions signed APK releases.

> **Disclaimer:** Unofficial personal/open-source wrapper. Not affiliated with DesignArena. Do not publish under DesignArena's name or trademarks on Google Play.

## Architecture

- **Single-Activity Shell (`MainActivity.kt`)**: Edge-to-edge system bars & IME insets, hardware/predictive back-gesture history navigation, no accidental pull-to-refresh reloads, and state preservation across rotation/dark-mode switches.
- **Hardened WebView (`WebViewConfigurator.kt` + `UrlRouter.kt`)**: HTTPS-only (`usesCleartextTraffic="false"`), persistent cookies & DOM storage, strict `designarena.ai` host allowlist, and automatic Chrome Custom Tabs handoff for external links.
- **Google sign-in (`WebViewConfigurator.kt` + `UrlRouter.kt` + `MainActivity.kt`)**: "Continue with Google" runs through Firebase `signInWithPopup`: DesignArena opens the Firebase Auth handler (`<project>.firebaseapp.com/__/auth/handler`) in a `window.open()` popup, and the handler posts the result back to `window.opener` with `postMessage`. The popup is therefore a real, visible, fully configured WebView (JavaScript, DOM storage, cookies, Chrome user-agent with the `; wv` marker stripped — Google refuses OAuth from embedded WebViews that carry it, `403: disallowed_useragent`) that stays alive for the whole flow and closes itself once the handler reports back. The earlier build got this wrong: it forwarded the popup to the main WebView / a Custom Tab, which destroys `window.opener`, so the handler had nowhere to deliver the login and the user only saw its URL redirecting. Top-level OAuth navigations still load in the app's WebView on purpose: a Custom Tab would set the session cookie in Chrome, not in this WebView, and DesignArena has no deep-link / token-exchange return path. Trade-off, stated plainly: Google discourages OAuth inside WebViews and can detect embedded browsers by other means, so if Google ever hard-blocks this, the fallback is signing in via Chrome — the session will not transfer into the app.
- **Native File & Export Bridge (`DownloadBridge.kt`)**: Cancellation-safe multi-file `WebChromeClient.onShowFileChooser` uploads, `DownloadManager` for HTTPS downloads, and `MediaStore.Downloads` + Share Sheet support for `blob:`/`data:` code exports—with zero broad storage permissions.
- **Offline Recovery (`NetworkMonitor.kt`)**: Native offline overlay that auto-reconnects when internet returns without wiping WebView session state.
- **Optional In-App Updater (`UpdateChecker.kt` + `VersionComparator.kt`)**: Checks GitHub Releases API when `designarena.githubRepo` is configured, verifies `SHA256SUMS.txt`, and hands the APK to Android's system package installer.

## Local Build & Verification

Requires **JDK 17**, **Android SDK (API 35)**, and **Gradle 8.10.2** (or Android Studio).

```bash
# 1. Run JVM unit tests (URL allowlist, OAuth routing, semantic version checks)
gradle testDebugUnitTest

# 2. Run Android Lint & assemble debug APK
gradle lintDebug assembleDebug
```

Optional: generate the local Gradle wrapper once if you prefer `./gradlew`:
```bash
gradle wrapper --gradle-version 8.10.2
```

## Automated Signed Releases (GitHub Actions)

1. Generate a release signing keystore locally (never commit this file):
   ```bash
   keytool -genkeypair -v -keystore release.keystore -alias designarena      -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Base64-encode the keystore:
   ```bash
   base64 -w 0 release.keystore
   ```
3. Add these four **GitHub Repository Secrets** (`Settings → Secrets and variables → Actions`):
   - `ANDROID_KEYSTORE_BASE64`: Base64 output from Step 2
   - `KEYSTORE_PASSWORD`: Your keystore password
   - `KEY_ALIAS`: `designarena`
   - `KEY_PASSWORD`: Your key password
4. Push a version tag to build, sign, checksum, and publish the APK automatically:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

## Enabling or Disabling the In-App Updater

- **GitHub Actions releases** automatically inject `GITHUB_REPOSITORY` into `BuildConfig.GITHUB_REPO`.
- **Local builds** leave `designarena.githubRepo` empty in `gradle.properties` (updater stays idle). Set `designarena.githubRepo=your-user/your-repo` to enable it locally.
- To strip the updater permission completely, remove `REQUEST_INSTALL_PACKAGES` from `app/src/main/AndroidManifest.xml`.
