#!/usr/bin/env python3
"""
test_structure.py — Tier 1/2 structural validation for OpenFree Android project.

Tests verify that every required file exists, contains expected tokens, and
that colour values match the Fieldwork design system.  These run without a
connected device or JDK (pure Python, zero network access).
"""

import os
import re
import unittest

# ---------------------------------------------------------------------------
# Repo root
# ---------------------------------------------------------------------------

REPO = os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "..")
)

def repo(*parts):
    return os.path.join(REPO, *parts)

def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


# ===========================================================================
# F1 — Keyboard UI & Lifecycle
# ===========================================================================

class TestF1KeyboardUIAndLifecycle(unittest.TestCase):
    """At least 5 happy-path + 5 boundary checks for F1."""

    # Happy-path: required files exist
    def test_f1_manifest_exists(self):
        self.assertTrue(os.path.isfile(repo("app/src/main/AndroidManifest.xml")))

    def test_f1_method_xml_exists(self):
        self.assertTrue(os.path.isfile(repo("app/src/main/res/xml/method.xml")))

    def test_f1_keyboard_view_exists(self):
        self.assertTrue(os.path.isfile(repo("app/src/main/res/layout/keyboard_view.xml")))

    def test_f1_openfree_ime_exists(self):
        self.assertTrue(os.path.isfile(
            repo("app/src/main/java/com/openfree/client/OpenFreeIME.kt")))

    def test_f1_settings_activity_exists(self):
        self.assertTrue(os.path.isfile(
            repo("app/src/main/java/com/openfree/client/SettingsActivity.kt")))

    # Boundary: manifest content checks
    def test_f1_manifest_has_ime_intent_filter(self):
        content = read(repo("app/src/main/AndroidManifest.xml"))
        self.assertIn("android.view.InputMethod", content)

    def test_f1_manifest_has_bind_input_method_permission(self):
        content = read(repo("app/src/main/AndroidManifest.xml"))
        self.assertIn("BIND_INPUT_METHOD", content)

    def test_f1_manifest_has_record_audio_permission(self):
        content = read(repo("app/src/main/AndroidManifest.xml"))
        self.assertIn("RECORD_AUDIO", content)

    def test_f1_method_xml_has_settings_activity(self):
        content = read(repo("app/src/main/res/xml/method.xml"))
        self.assertIn("SettingsActivity", content)

    def test_f1_keyboard_view_has_mic_button(self):
        content = read(repo("app/src/main/res/layout/keyboard_view.xml"))
        self.assertIn("btn_mic", content)

    def test_keyboard_view_has_visualizer_and_dots_layout(self):
        content = read(repo("app/src/main/res/layout/keyboard_view.xml"))
        self.assertIn("ime_waveform_container", content)
        self.assertIn("layout_dots", content)

    def test_floating_layout_has_pill_timer_and_waveform(self):
        content = read(repo("app/src/main/res/layout/floating_layout.xml"))
        self.assertIn("floating_pill_container", content)
        self.assertIn("tv_timer", content)
        self.assertIn("waveform_container", content)


# ===========================================================================
# F1 — Fieldwork colour compliance
# ===========================================================================

class TestF1FieldworkColors(unittest.TestCase):
    """Verify keyboard panel and colors.xml use modern standard palette."""

    MODERN_COLORS = {
        "bg_primary":      "#FEF7FF",
        "primary":         "#6750A4",
        "on_primary":      "#FFFFFF",
        "secondary":       "#386A20",
        "text_primary":    "#1D1B20",
        "text_secondary":  "#49454F",
        "border_primary":  "#CAC4D0",
        "surface_container": "#F3EDF7",
    }

    def test_colors_xml_exists(self):
        self.assertTrue(os.path.isfile(repo("app/src/main/res/values/colors.xml")))

    def _colors_content(self):
        return read(repo("app/src/main/res/values/colors.xml")).upper()

    def test_color_bg_primary(self):
        self.assertIn("FEF7FF", self._colors_content())

    def test_color_primary(self):
        self.assertIn("6750A4", self._colors_content())

    def test_color_secondary(self):
        self.assertIn("386A20", self._colors_content())

    def test_color_text_primary(self):
        self.assertIn("1D1B20", self._colors_content())

    def test_keyboard_view_references_surface_color(self):
        content = read(repo("app/src/main/res/layout/keyboard_view.xml"))
        # Keyboard panel uses @color/surface as its background
        self.assertIn("surface", content)

    def test_keyboard_view_references_preview_bg(self):
        content = read(repo("app/src/main/res/layout/keyboard_view.xml"))
        self.assertIn("preview_bg", content)

    def test_keyboard_view_references_mic_button_bg(self):
        content = read(repo("app/src/main/res/layout/keyboard_view.xml"))
        self.assertIn("mic_button_bg", content)

    def test_mic_button_bg_uses_primary_color(self):
        content = read(repo("app/src/main/res/drawable/mic_button_bg.xml")).upper()
        self.assertIn("6750A4", content)

    def test_mic_button_bg_active_uses_secondary_color(self):
        content = read(repo("app/src/main/res/drawable/mic_button_bg_active.xml")).upper()
        self.assertIn("386A20", content)


# ===========================================================================
# F2 — Audio Capture Engine
# ===========================================================================

class TestF2AudioCaptureEngine(unittest.TestCase):

    def _content(self):
        return read(repo("app/src/main/java/com/openfree/client/AudioRecorder.kt"))

    # Happy-path
    def test_f2_audio_recorder_exists(self):
        self.assertTrue(os.path.isfile(
            repo("app/src/main/java/com/openfree/client/AudioRecorder.kt")))

    def test_f2_sample_rate_is_16khz(self):
        self.assertIn("16_000", self._content())

    def test_f2_uses_pcm16bit_encoding(self):
        self.assertIn("ENCODING_PCM_16BIT", self._content())

    def test_f2_uses_mono_channel(self):
        self.assertIn("CHANNEL_IN_MONO", self._content())

    def test_f2_start_recording_method_exists(self):
        self.assertIn("fun startRecording", self._content())

    # Boundary
    def test_f2_stop_recording_method_exists(self):
        self.assertIn("fun stopRecording", self._content())

    def test_f2_normalises_to_float(self):
        # Should divide by Short.MAX_VALUE
        self.assertIn("Short.MAX_VALUE", self._content())

    def test_f2_uses_atomic_boolean_for_thread_safety(self):
        self.assertIn("AtomicBoolean", self._content())

    def test_f2_has_mic_source(self):
        self.assertIn("AudioSource.MIC", self._content())

    def test_f2_callback_receives_float_array(self):
        self.assertIn("FloatArray", self._content())


# ===========================================================================
# F3 — Whisper JNI Wrapper
# ===========================================================================

class TestF3WhisperJNI(unittest.TestCase):

    def _kt(self):
        return read(repo("app/src/main/java/com/openfree/client/WhisperEngine.kt"))

    def _cpp(self):
        return read(repo("app/src/main/cpp/whisper-jni.cpp"))

    def _cmake(self):
        return read(repo("app/src/main/cpp/CMakeLists.txt"))

    # Happy-path
    def test_f3_whisper_engine_exists(self):
        self.assertTrue(os.path.isfile(
            repo("app/src/main/java/com/openfree/client/WhisperEngine.kt")))

    def test_f3_jni_cpp_exists(self):
        self.assertTrue(os.path.isfile(repo("app/src/main/cpp/whisper-jni.cpp")))

    def test_f3_cmake_exists(self):
        self.assertTrue(os.path.isfile(repo("app/src/main/cpp/CMakeLists.txt")))

    def test_f3_load_model_method_exists(self):
        self.assertIn("fun loadModel", self._kt())

    def test_f3_transcribe_method_exists(self):
        self.assertIn("fun transcribe", self._kt())

    # Boundary
    def test_f3_unload_model_method_exists(self):
        self.assertIn("fun unloadModel", self._kt())

    def test_f3_jni_load_model_function_exists(self):
        self.assertIn("nativeLoadModel", self._cpp())

    def test_f3_jni_transcribe_function_exists(self):
        self.assertIn("nativeTranscribe", self._cpp())

    def test_f3_jni_unload_function_exists(self):
        self.assertIn("nativeUnloadModel", self._cpp())

    def test_f3_cmake_links_whisper(self):
        self.assertIn("whisper", self._cmake())


# ===========================================================================
# F4 — Settings UI & Model Downloader
# ===========================================================================

class TestF4SettingsAndDownloader(unittest.TestCase):

    def _kt(self):
        return read(repo("app/src/main/java/com/openfree/client/SettingsActivity.kt"))

    def _layout(self):
        return read(repo("app/src/main/res/layout/activity_settings.xml"))

    # Happy-path
    def test_f4_settings_activity_exists(self):
        self.assertTrue(os.path.isfile(
            repo("app/src/main/java/com/openfree/client/SettingsActivity.kt")))

    def test_f4_layout_has_model_path_field(self):
        self.assertIn("edit_model_path", self._layout())

    def test_f4_layout_has_download_button(self):
        self.assertIn("btn_download_model", self._layout())

    def test_f4_layout_has_theme_selection_panel(self):
        self.assertIn("toggle_group_theme", self._layout())

    def test_f4_layout_has_contrast_selector(self):
        self.assertIn("toggle_group_contrast", self._layout())

    def test_f4_layout_has_blur_radius_slider(self):
        self.assertIn("slider_blur_radius", self._layout())

    def test_f4_layout_has_circular_progress_indicator(self):
        self.assertIn("progress_download_circular", self._layout())

    def test_f4_layout_has_accessibility_service_switch(self):
        self.assertIn("switch_floating_service", self._layout())

    def test_f4_layout_has_save_button(self):
        self.assertIn("btn_save", self._layout())

    def test_f4_layout_has_progress_bar(self):
        self.assertIn("progress_download", self._layout())

    # Boundary
    def test_f4_layout_has_fallback_url_field(self):
        self.assertIn("edit_fallback_url", self._layout())

    def test_f4_uses_huggingface_url(self):
        self.assertIn("huggingface.co", self._kt())

    def test_f4_shared_prefs_key_model_path(self):
        kt = self._kt()
        # Accept either the raw string literal or the constant reference
        self.assertTrue(
            "pref_key_model_path" in kt or "KEY_MODEL_PATH" in kt,
            "SettingsActivity must reference the model path preference key"
        )

    def test_f4_shared_prefs_key_fallback_url(self):
        kt = self._kt()
        self.assertTrue(
            "pref_key_remote_fallback_url" in kt or "KEY_REMOTE_FALLBACK_URL" in kt,
            "SettingsActivity must reference the fallback URL preference key"
        )

    def test_f4_settings_layout_uses_dark_background(self):
        self.assertIn("bg_primary", self._layout())


# ===========================================================================
# Tier 3 — Cross-feature integration checks
# ===========================================================================

class TestTier3CrossFeature(unittest.TestCase):

    def test_ime_uses_audio_recorder(self):
        content = read(repo("app/src/main/java/com/openfree/client/OpenFreeIME.kt"))
        self.assertIn("AudioRecorder", content)

    def test_ime_uses_whisper_engine(self):
        content = read(repo("app/src/main/java/com/openfree/client/OpenFreeIME.kt"))
        self.assertIn("WhisperEngine", content)

    def test_ime_commits_text(self):
        content = read(repo("app/src/main/java/com/openfree/client/OpenFreeIME.kt"))
        self.assertIn("commitText", content)

    def test_ime_reads_model_path_from_prefs(self):
        content = read(repo("app/src/main/java/com/openfree/client/OpenFreeIME.kt"))
        self.assertIn("KEY_MODEL_PATH", content)

    def test_settings_prefs_key_matches_ime_prefs_key(self):
        ime_content  = read(repo("app/src/main/java/com/openfree/client/OpenFreeIME.kt"))
        sett_content = read(repo("app/src/main/java/com/openfree/client/SettingsActivity.kt"))
        # Both should reference PREFS_NAME or OpenFreeIME.PREFS_NAME
        self.assertTrue(
            "PREFS_NAME" in ime_content and "PREFS_NAME" in sett_content,
            "Settings and IME must share the same prefs name constant"
        )


# ===========================================================================
# Tier 4 — End-to-end scenario plausibility (static analysis)
# ===========================================================================

class TestTier4E2EScenarios(unittest.TestCase):

    def test_scenario_build_files_present(self):
        """Scenario: project can be opened and built."""
        for f in ["build.gradle.kts", "settings.gradle.kts", "gradle.properties",
                  "local.properties", "gradlew.bat"]:
            self.assertTrue(os.path.isfile(repo(f)), f"Missing: {f}")

    def test_scenario_sdk_path_set(self):
        """Scenario: local.properties points to an SDK dir."""
        content = read(repo("local.properties"))
        self.assertIn("sdk.dir", content)

    def test_scenario_ndk_version_set(self):
        """Scenario: app build.gradle.kts specifies an NDK version."""
        content = read(repo("app/build.gradle.kts"))
        self.assertIn("ndkVersion", content)

    def test_scenario_cmake_path_configured(self):
        """Scenario: CMake path wired in app build.gradle.kts."""
        content = read(repo("app/build.gradle.kts"))
        self.assertIn("cmake", content.lower())

    def test_scenario_whisper_submodule_cmakelists_referenced(self):
        """Scenario: CMakeLists.txt references whisper.cpp submodule."""
        content = read(repo("app/src/main/cpp/CMakeLists.txt"))
        self.assertIn("whisper.cpp", content)

    def test_scenario_target_sdk_is_37(self):
        """Scenario: app build.gradle.kts targets API 37 (Android 17)."""
        content = read(repo("app/build.gradle.kts"))
        self.assertIn("compileSdk = 37", content)
        self.assertIn("targetSdk = 37", content)

    def test_scenario_app_metadata_exists(self):
        """Scenario: AppFunctions metadata XML exists and is registered."""
        self.assertTrue(os.path.isfile(repo("app/src/main/res/xml/app_metadata.xml")))
        manifest = read(repo("app/src/main/AndroidManifest.xml"))
        self.assertIn("android.app.appfunctions.app_metadata", manifest)

    def test_scenario_dictionary_app_functions_exists(self):
        """Scenario: AppFunctions implementation class exists."""
        self.assertTrue(os.path.isfile(repo("app/src/main/java/com/openfree/client/DictionaryAppFunctions.kt")))
        content = read(repo("app/src/main/java/com/openfree/client/DictionaryAppFunctions.kt"))
        self.assertIn("@AppFunction", content)
        self.assertIn("addDictionaryCorrection", content)

    def test_scenario_download_saves_model_path_to_prefs(self):
        """Scenario: after download, model path written to SharedPreferences."""
        content = read(repo("app/src/main/java/com/openfree/client/SettingsActivity.kt"))
        self.assertIn("KEY_MODEL_PATH", content)
        self.assertIn("putString", content)

    def test_scenario_ime_loads_model_on_create(self):
        content = read(repo("app/src/main/java/com/openfree/client/OpenFreeIME.kt"))
        self.assertIn("reloadModel", content)
        self.assertIn("onCreate", content)

    def test_scenario_ime_unloads_model_on_destroy(self):
        content = read(repo("app/src/main/java/com/openfree/client/OpenFreeIME.kt"))
        self.assertIn("unloadModel", content)
        self.assertIn("onDestroy", content)


# ---------------------------------------------------------------------------

if __name__ == "__main__":
    unittest.main()
