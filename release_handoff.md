# Release Preparation & Google Play Publishing Handoff
**Project: OpenFree Android Keyboard**

This document serves as the guide for release preparation and Google Play Store publishing, to be executed after the design revision phase is finalized.

---

## 1. Release Signing & Credentials Configuration
To compile production-ready packages for the Google Play Store, release signing must be configured.

1.  **Generate Upload Keystore**:
    If not already done, generate a secure `.jks` file:
    ```bash
    keytool -genkey -v -keystore openfree-upload-key.jks \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -alias openfree-upload-alias
    ```
2.  **Configure Environment Variables / Properties**:
    Copy `keystore.properties.example` to `keystore.properties` in the project root:
    ```properties
    storeFile=openfree-upload-key.jks
    storePassword=your_store_password
    keyAlias=openfree-upload-alias
    keyPassword=your_key_password
    ```
    *Note: Both `keystore.properties` and `*.jks` are configured in `.gitignore` to avoid checking keys into version control.*

---

## 2. Compiling the Production App Bundle (AAB)
Google Play requires publishing in the Android App Bundle format (`.aab`) rather than standard APKs:

1.  **Clean and Assemble Bundle**:
    From the root directory, execute:
    ```bash
    ./gradlew bundleRelease
    ```
2.  **Verification**:
    This compiles the Kotlin codebase along with the native C++ Whisper engines for all target architectures (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) and produces the output file:
    `app/build/outputs/bundle/release/app-release.aab`

---

## 3. Closed Testing Tracks & Onboarding (20-Tester Rule)
For new Google Play Developer accounts, Google requires completing a closed testing phase before requesting production release.

*   **Requirement**: At least **20 testers** must remain opted-in continuously for at least **14 days** and actively test the application.
*   **Onboarding Steps**:
    1.  **Google Group**: Create a Google Group containing all tester emails (e.g., `openfree-testers@googlegroups.com`).
    2.  **Console Config**: In Play Console, navigate to **Testing -> Closed testing**, select your track, and add the Google Group under the **Testers** tab.
    3.  **Create Release**: Upload `app-release.aab` to the Closed testing track.
    4.  **Join URL**: Share the Play Store opt-in links ("Join on Android" / "Join on Web") with the Google Group.

---

## 4. Play Store Listing Pre-flight Check
Ensure all assets and legal requirements are ready before submission:

*   **Privacy Policy URL**: Mandatory because the app requests recording permissions (`RECORD_AUDIO`). Host the privacy policy (contained in `store_listing/PRIVACY_POLICY.md`) on a public website (e.g. GitHub Pages) and link it in the Play Console.
*   **Store Listing Metadata**: Retrieve description text, title, feature graphics, and screenshots from `store_listing/metadata.md` and upload them to the Store Presence section of the console.
*   **Pre-Release Checklist**:
    - [ ] Keystore properties match the upload key.
    - [ ] Target SDK is set to 37 (Android 17) and min SDK is 29 (Android 10).
    - [ ] Native libraries (`whisper.cpp`) are stripped of debug symbols.
    - [ ] Local tests and instrumented tests run cleanly.
