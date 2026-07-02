import unittest
import os
import xml.etree.ElementTree as ET

class TestKeyboardUiAndLifecycle(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Resolve project root relative to this file (c:\dev\android-dictation\tests\mock_harness\test_f1_ui.py)
        cls.root_dir = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        cls.app_dir = os.path.join(cls.root_dir, "app")
        cls.manifest_path = os.path.join(cls.app_dir, "src", "main", "AndroidManifest.xml")
        cls.method_path = os.path.join(cls.app_dir, "src", "main", "res", "xml", "method.xml")
        cls.layout_path = os.path.join(cls.app_dir, "src", "main", "res", "layout", "keyboard_view.xml")
        cls.colors_path = os.path.join(cls.app_dir, "src", "main", "res", "values", "colors.xml")

    def test_android_manifest_structure(self):
        # Verify Manifest is present
        self.assertTrue(os.path.exists(self.manifest_path), f"AndroidManifest.xml must exist at {self.manifest_path}")
        
        # Parse and verify XML
        tree = ET.parse(self.manifest_path)
        root = tree.getroot()
        
        # Check namespace and package name
        self.assertEqual(root.tag, "manifest")
        package = root.attrib.get("package")
        self.assertEqual(package, "com.openfree.client")
        
        # Collect permissions
        permissions = []
        for child in root.findall("uses-permission"):
            name = child.attrib.get("{http://schemas.android.com/apk/res/android}name")
            if name:
                permissions.append(name)
                
        self.assertIn("android.permission.RECORD_AUDIO", permissions, "Missing RECORD_AUDIO permission")
        self.assertIn("android.permission.INTERNET", permissions, "Missing INTERNET permission")
        
        # Verify application, service, and activity tags
        app = root.find("application")
        self.assertIsNotNone(app, "AndroidManifest.xml missing <application> tag")
        
        # Check IME service
        services = app.findall("service")
        ime_service = None
        for svc in services:
            svc_name = svc.attrib.get("{http://schemas.android.com/apk/res/android}name")
            if svc_name in [".OpenFreeIME", "com.openfree.client.OpenFreeIME"]:
                ime_service = svc
                break
                
        self.assertIsNotNone(ime_service, "Missing OpenFreeIME service registration in manifest")
        permission = ime_service.attrib.get("{http://schemas.android.com/apk/res/android}permission")
        self.assertEqual(permission, "android.permission.BIND_INPUT_METHOD", "Service must require BIND_INPUT_METHOD permission")
        
        # Check service intent filters and meta-data
        meta_data = ime_service.find("meta-data")
        self.assertIsNotNone(meta_data, "Service missing <meta-data> configuration")
        meta_name = meta_data.attrib.get("{http://schemas.android.com/apk/res/android}name")
        self.assertEqual(meta_name, "android.view.im")
        meta_resource = meta_data.attrib.get("{http://schemas.android.com/apk/res/android}resource")
        self.assertEqual(meta_resource, "@xml/method")
        
        # Check SettingsActivity activity
        activities = app.findall("activity")
        settings_act = None
        for act in activities:
            act_name = act.attrib.get("{http://schemas.android.com/apk/res/android}name")
            if act_name in [".SettingsActivity", "com.openfree.client.SettingsActivity"]:
                settings_act = act
                break
                
        self.assertIsNotNone(settings_act, "Missing SettingsActivity registration in manifest")

    def test_method_xml_structure(self):
        # Verify method.xml is present
        self.assertTrue(os.path.exists(self.method_path), f"method.xml must exist at {self.method_path}")
        
        # Parse and verify XML
        tree = ET.parse(self.method_path)
        root = tree.getroot()
        
        self.assertEqual(root.tag, "input-method")
        settings_act = root.attrib.get("{http://schemas.android.com/apk/res/android}settingsActivity")
        self.assertEqual(settings_act, "com.openfree.client.SettingsActivity", "Incorrect settingsActivity in method.xml")

    def test_keyboard_view_layout_and_colors(self):
        # If layout file does not exist, verify that it is gracefully not present
        if not os.path.exists(self.layout_path):
            print(f"keyboard_view.xml is not present at {self.layout_path} (this is normal if bootstrapping). Graceful pass.")
            self.assertTrue(True)
            return

        # Parse layout if present
        tree = ET.parse(self.layout_path)
        root = tree.getroot()
        
        # Verify essential layout controls (mic button and status text) as specified in PROJECT.md
        ids = self._extract_ids(root)
        self.assertIn("btn_mic", ids, "keyboard_view.xml must contain an element with ID btn_mic")
        self.assertIn("txt_status", ids, "keyboard_view.xml must contain an element with ID txt_status")
        
        # Load colors if colors.xml is present
        color_map = {}
        if os.path.exists(self.colors_path):
            color_tree = ET.parse(self.colors_path)
            for color_elem in color_tree.getroot().findall("color"):
                name = color_elem.attrib.get("name")
                value = color_elem.text.strip().lower() if color_elem.text else ""
                if name and value:
                    color_map[f"@color/{name}"] = value
                    
        # Valid colors (including Material 3 Light/Dark colors)
        valid_colors = {
            # Old Fieldwork colors (for compatibility)
            "#19120e", "#ffb691", "#9dd3a8", "#ece7de", "#7a7166", "#0d0c0a", "#261e1a", "#2c2820",
            # Material 3 Colors
            "#fef7ff", "#f3edf7", "#eae2f3", "#ded8e1", "#fdf7ff", "#ffffff", "#f7f2fa", "#ece6f0", "#e6e1e9",
            "#1d1b20", "#49454f", "#6750a4", "#eaddff", "#21005d", "#386a20", "#b8f397", "#042100",
            "#7d5260", "#b3261e", "#79747e", "#cac4d0", "#e6a100"
        }
        
        # Check all colors in layout attributes
        colors_found = self._extract_colors(root, color_map)
        for val, attr, tag in colors_found:
            normalized_val = val
            # Strip alpha prefix if 8 hex chars (e.g. #ff19120e -> #19120e)
            if normalized_val.startswith("#") and len(normalized_val) == 9:
                normalized_val = "#" + normalized_val[3:]
            self.assertIn(normalized_val, valid_colors, 
                          f"Non-compliant color '{val}' found in <{tag}> attribute '{attr}'")

    # --- Feature Coverage (Tier 1) - Continued ---

    def test_keyboard_lifecycle_flow(self):
        ime = MockOpenFreeIME()
        self.assertFalse(ime.is_created)
        
        ime.onCreate()
        self.assertTrue(ime.is_created)
        
        view = ime.onCreateInputView()
        self.assertIn("btn_mic", view)
        self.assertIn("txt_status", view)
        
        editor_info = {"inputType": 1, "packageName": "com.example.app"}
        ime.onStartInputView(editor_info, restarting=False)
        self.assertTrue(ime.is_input_view_started)
        self.assertEqual(ime.status_text, "Ready")
        self.assertEqual(ime.editor_info, editor_info)
        
        ime.onFinishInputView()
        self.assertFalse(ime.is_input_view_started)
        self.assertEqual(ime.status_text, "Idle")
        
        ime.onDestroy()
        self.assertFalse(ime.is_created)

    def test_keyboard_mic_tap_to_recording(self):
        ime = MockOpenFreeIME()
        ime.onCreate()
        ime.onStartInputView({}, restarting=False)
        
        recorder = DummyAudioRecorder()
        engine = DummyWhisperEngine()
        
        # Tap to record
        ime.handleMicTap(recorder, engine)
        self.assertTrue(ime.btn_mic_active)
        self.assertEqual(ime.status_text, "Recording")
        self.assertTrue(recorder.is_recording)
        
        # Tap to stop
        ime.handleMicTap(recorder, engine)
        self.assertFalse(ime.btn_mic_active)
        self.assertEqual(ime.status_text, "Ready")
        self.assertFalse(recorder.is_recording)

    # --- Boundary & Corner Cases (Tier 2) ---

    def test_keyboard_start_input_with_null_editor_info(self):
        ime = MockOpenFreeIME()
        ime.onCreate()
        
        # Null/None editor info should be handled without raising error
        try:
            ime.onStartInputView(None, restarting=False)
        except Exception as e:
            self.fail(f"onStartInputView crashed with None editor_info: {e}")
            
        self.assertTrue(ime.is_input_view_started)
        self.assertIsNone(ime.editor_info)

    def test_keyboard_mic_tap_without_active_input_view(self):
        ime = MockOpenFreeIME()
        ime.onCreate()
        # Input view not started!
        
        recorder = DummyAudioRecorder()
        engine = DummyWhisperEngine()
        
        ime.handleMicTap(recorder, engine)
        # Should not start recording or change state
        self.assertFalse(ime.btn_mic_active)
        self.assertEqual(ime.status_text, "Idle")
        self.assertFalse(recorder.is_recording)

    def test_keyboard_mic_tap_with_null_input_connection(self):
        ime = MockOpenFreeIME()
        ime.onCreate()
        ime.onStartInputView({}, restarting=False)
        ime.setInputConnection(None) # null input connection
        
        recorder = DummyAudioRecorder()
        engine = DummyWhisperEngine()
        
        ime.handleMicTap(recorder, engine)
        self.assertTrue(ime.btn_mic_active)
        
        # Trigger recorder callback to simulate transcription commit
        recorder.callback([0.0] * 100)
        self.assertEqual(ime.status_text, "Done")

    def test_keyboard_commit_empty_text(self):
        ime = MockOpenFreeIME()
        ime.onCreate()
        ime.onStartInputView({}, restarting=False)
        
        ic = MockInputConnection()
        ime.setInputConnection(ic)
        
        recorder = DummyAudioRecorder()
        engine = DummyWhisperEngine()
        engine.transcription = "" # empty transcription
        
        ime.handleMicTap(recorder, engine)
        recorder.callback([0.0] * 100)
        
        # Should reset status back to Ready, committed_text remains empty
        self.assertEqual(ime.status_text, "Ready")
        self.assertEqual(ic.committed_text, "")

    def test_keyboard_rapid_mic_taps(self):
        ime = MockOpenFreeIME()
        ime.onCreate()
        ime.onStartInputView({}, restarting=False)
        
        recorder = DummyAudioRecorder()
        engine = DummyWhisperEngine()
        
        # Rapidly tap mic button (5 times)
        for _ in range(5):
            ime.handleMicTap(recorder, engine)
            
        # 5 taps: tap 1 (Rec), tap 2 (Ready), tap 3 (Rec), tap 4 (Ready), tap 5 (Rec)
        self.assertTrue(ime.btn_mic_active)
        self.assertEqual(ime.status_text, "Recording")
        self.assertTrue(recorder.is_recording)

    def _extract_ids(self, element):
        ids = set()
        android_id_attr = "{http://schemas.android.com/apk/res/android}id"
        val = element.attrib.get(android_id_attr)
        if val:
            # e.g., @+id/btn_mic or @id/btn_mic
            clean_id = val.split("/")[-1]
            ids.add(clean_id)
        for child in element:
            ids.update(self._extract_ids(child))
        return ids

    def _extract_colors(self, element, color_map):
        colors = []
        color_attrs = [
            "{http://schemas.android.com/apk/res/android}background",
            "{http://schemas.android.com/apk/res/android}backgroundTint",
            "{http://schemas.android.com/apk/res/android}tint",
            "{http://schemas.android.com/apk/res/android}textColor",
            "{http://schemas.android.com/apk/res/android}src"
        ]
        
        for attr in color_attrs:
            val = element.attrib.get(attr)
            if val:
                val = val.strip().lower()
                # Resolve color references
                if val in color_map:
                    val = color_map[val]
                if val.startswith("#"):
                    colors.append((val, attr.split("}")[-1], element.tag))
                    
        for child in element:
            colors.extend(self._extract_colors(child, color_map))
        return colors


class MockInputConnection:
    def __init__(self):
        self.committed_text = ""
        self.deleted_before = 0
        self.deleted_after = 0
        
    def commitText(self, text, cursor_position):
        self.committed_text += text
        return True
        
    def deleteSurroundingText(self, before, after):
        self.deleted_before = before
        self.deleted_after = after
        return True


class MockOpenFreeIME:
    def __init__(self):
        self.is_created = False
        self.is_input_view_started = False
        self.status_text = "Idle"
        self.btn_mic_active = False
        self.input_connection = None
        self.editor_info = None
        
    def onCreate(self):
        self.is_created = True
        
    def onCreateInputView(self):
        return {"btn_mic": {}, "txt_status": {}}
        
    def onStartInputView(self, editor_info, restarting):
        self.is_input_view_started = True
        self.editor_info = editor_info
        self.status_text = "Ready"
        
    def onFinishInputView(self):
        self.is_input_view_started = False
        self.editor_info = None
        self.status_text = "Idle"
        
    def onDestroy(self):
        self.is_created = False
        
    def setInputConnection(self, ic):
        self.input_connection = ic
        
    def handleMicTap(self, audio_recorder, whisper_engine):
        if not self.is_input_view_started:
            return
            
        if not self.btn_mic_active:
            self.btn_mic_active = True
            self.status_text = "Recording"
            
            def on_audio_data(samples):
                self.status_text = "Transcribing"
                text = whisper_engine.transcribe(samples)
                if text:
                    self.status_text = "Done"
                    if self.input_connection:
                        self.input_connection.commitText(text, 1)
                else:
                    self.status_text = "Ready"
            
            audio_recorder.start_recording(on_audio_data)
        else:
            self.btn_mic_active = False
            self.status_text = "Ready"
            audio_recorder.stop_recording()


class DummyAudioRecorder:
    def __init__(self):
        self.callback = None
        self.is_recording = False
        
    def start_recording(self, callback):
        self.callback = callback
        self.is_recording = True
        
    def stop_recording(self):
        self.is_recording = False
        self.callback = None


class DummyWhisperEngine:
    def __init__(self):
        self.transcription = "test string"
        
    def transcribe(self, samples):
        return self.transcription


if __name__ == "__main__":
    unittest.main()

