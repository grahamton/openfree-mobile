# Google Play Store Publishing Guide

This guide describes how to sign your release builds, generate the Android App Bundle (AAB), and manage the Google Play Console closed testing tracks for OpenFree.

---

## 🔑 1. Setup Release Signing Credentials

To submit to the Google Play Store, you must sign your app bundle using an **upload key**. 

### Step A: Generate the Keystore File
Use the Java `keytool` command (which comes with the JDK/Android Studio) to generate a secure `.jks` keystore file. 

Run the following command in your terminal. Replace `your_password` and `your_alias` with your preferred values:

```bash
keytool -genkey -v -keystore openfree-upload-key.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias openfree-upload-alias
```

*Note: Save this keystore file and write down the passwords in a secure password manager. If you lose your upload key, you will not be able to update your app.*

### Step B: Configure keystore.properties
1. Copy the `keystore.properties.example` file in the root directory and rename it to `keystore.properties`.
2. Move your generated `openfree-upload-key.jks` file to the root of the project (or to a location of your choice).
3. Update `keystore.properties` with the path and passwords you used:

```properties
storeFile=openfree-upload-key.jks
storePassword=your_store_password_here
keyAlias=openfree-upload-alias
keyPassword=your_key_password_here
```

*Note: Both `keystore.properties` and `*.jks` are already added to `.gitignore` to prevent secret leaks.*

---

## 📦 2. Compile the Release App Bundle (AAB)

Once `keystore.properties` is configured, you can compile the release bundle which Google Play requires.

From the root of the project, run:

```bash
./gradlew bundleRelease
```

This compiles the native C++ Whisper engine for all target architectures (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) and packages them along with the Kotlin codebase.

The generated App Bundle will be located at:
`app/build/outputs/bundle/release/app-release.aab`

---

## 📋 3. Closed Testing Tracks & Onboarding (20-Tester Rule)

If your Google Play Developer Console account was created after **November 13, 2023**, you must complete Google's closed testing requirements before applying to publish to production.

### Requirements:
* At least **20 testers** must be opted into your closed test.
* They must remain opted-in continuously for at least **14 days**.
* They should actively open and test the application on their devices.

### Recommended Steps:

#### Step 1: Create a Testers Google Group
Google allows you to manage testers easily by target groups.
1. Go to [Google Groups](https://groups.google.com/) and click **Create Group**.
2. Name your group (e.g., `openfree-testers@googlegroups.com`).
3. Add the email addresses of your 20+ testers.

#### Step 2: Configure the Closed Testing Track in Play Console
1. Log into your [Google Play Console](https://play.google.com/console).
2. Select your app and go to **Testing** -> **Closed testing**.
3. Click **Create track** or select the default Alpha track.
4. Under **Testers**, select **Google Groups** and enter your Google Group address.
5. Save changes.

#### Step 3: Create a Release & Upload AAB
1. In the Closed testing track page, click **Create new release**.
2. Upload the `app-release.aab` file you compiled in Section 2.
3. Review and roll out the release to your testers.

#### Step 4: Share the Opt-In Link
1. Once the release passes Google's automated review (which can take a few days for the first release), copy the **Join on Android** or **Join on Web** opt-in URL from the Testers section in your Play Console.
2. Send this URL to your Google Group testers.
3. Instruct testers to click the link, opt-in, and download the App from the Google Play Link.
4. Ensure your testers run the app periodically over the 14-day duration.

---

## 🎨 4. Store Listing Pre-flight Check

Ensure you have prepared the metadata in the `store_listing/metadata.md` file and hosted the `store_listing/PRIVACY_POLICY.md` on your website (e.g. GitHub Pages) as a privacy policy URL is mandatory for apps requesting microphone record audio permissions.
