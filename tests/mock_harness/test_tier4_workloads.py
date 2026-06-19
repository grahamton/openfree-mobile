import unittest
import os
import sys
import ctypes
import subprocess
import tempfile
import struct
import math
import urllib.request
import json
import threading
from http.server import HTTPServer, SimpleHTTPRequestHandler

# Reuse mock structures locally for self-containment and absolute reliability

def pcm16_to_float(pcm_data: bytes) -> list:
    if not pcm_data:
        return []
    num_samples = len(pcm_data) // 2
    if num_samples == 0:
        return []
    samples = struct.unpack(f"<{num_samples}h", pcm_data[:num_samples*2])
    return [s / 32768.0 for s in samples]


class MockAudioRecorder:
    def __init__(self):
        self.callback = None
        self.is_recording = False
        
    def start_recording(self, callback):
        self.callback = callback
        self.is_recording = True
        
    def stop_recording(self):
        self.is_recording = False
        self.callback = None
        
    def process_raw_pcm_bytes(self, pcm_bytes: bytes):
        if not self.is_recording or not self.callback:
            return
        floats = pcm16_to_float(pcm_bytes)
        self.callback(floats)


class MockFloatArray(ctypes.Structure):
    _fields_ = [
        ("length", ctypes.c_int),
        ("data", ctypes.POINTER(ctypes.c_float))
    ]

def make_mock_float_array(floats_list):
    arr_type = ctypes.c_float * len(floats_list)
    arr_data = arr_type(*floats_list)
    mock_arr = MockFloatArray()
    mock_arr.length = len(floats_list)
    mock_arr._data_ref = arr_data
    mock_arr.data = ctypes.cast(arr_data, ctypes.POINTER(ctypes.c_float))
    return mock_arr


class MockSharedPreferences:
    def __init__(self, cache_dir):
        self.cache_dir = cache_dir
        self.prefs = {}
        
    def getString(self, key: str, default: str) -> str:
        return self.prefs.get(key, default)
        
    def putString(self, key: str, value: str):
        self.prefs[key] = value

    def get_model_path(self) -> str:
        default_path = os.path.join(self.cache_dir, "ggml-base.en-q5_1.bin")
        return self.getString("pref_key_model_path", default_path)
        
    def get_remote_fallback_url(self) -> str:
        return self.getString("pref_key_remote_fallback_url", "")


class MockDownloader:
    def __init__(self, prefs: MockSharedPreferences):
        self.prefs = prefs
        
    def download_model(self, url: str, dest_path: str) -> bool:
        temp_path = dest_path + ".tmp"
        try:
            if os.path.exists(temp_path):
                os.remove(temp_path)
                
            req = urllib.request.Request(url, headers={'User-Agent': 'OpenFreeDownloader'})
            with urllib.request.urlopen(req, timeout=2.0) as response:
                if response.status != 200:
                    return False
                with open(temp_path, "wb") as f:
                    while True:
                        chunk = response.read(4096)
                        if not chunk:
                            break
                        f.write(chunk)
            
            if os.path.getsize(temp_path) == 0:
                if os.path.exists(temp_path):
                    os.remove(temp_path)
                return False
                
            if os.path.exists(dest_path):
                os.remove(dest_path)
            os.rename(temp_path, dest_path)
            
            self.prefs.putString("pref_key_model_path", dest_path)
            return True
        except Exception:
            if os.path.exists(temp_path):
                try:
                    os.remove(temp_path)
                except OSError:
                    pass
            return False


class MockInputConnection:
    def __init__(self):
        self.committed_text = ""
        
    def commitText(self, text, cursor_position):
        self.committed_text += text
        return True


class MockOpenFreeIME:
    def __init__(self, dll, prefs):
        self.dll = dll
        self.prefs = prefs
        self.is_created = False
        self.is_input_view_started = False
        self.status_text = "Idle"
        self.btn_mic_active = False
        self.input_connection = None
        self.editor_info = None
        self.accumulated_audio = []
        self.audio_recorder = None
        
    def onCreate(self):
        self.is_created = True
        
    def onStartInputView(self, editor_info, restarting):
        self.is_input_view_started = True
        self.editor_info = editor_info
        self.status_text = "Ready"
        
    def onFinishInputView(self):
        if self.btn_mic_active:
            self.stop_dictation()
        self.is_input_view_started = False
        self.editor_info = None
        self.status_text = "Idle"
        
    def onDestroy(self):
        self.is_created = False
        
    def setInputConnection(self, ic):
        self.input_connection = ic
        
    def handleMicTap(self, recorder):
        if not self.is_input_view_started:
            return
            
        self.audio_recorder = recorder
        if not self.btn_mic_active:
            self.start_dictation()
        else:
            self.stop_dictation()
            
    def start_dictation(self):
        self.btn_mic_active = True
        self.status_text = "Recording"
        self.accumulated_audio = []
        
        def on_audio_data(samples):
            self.accumulated_audio.extend(samples)
            # Continuous preview check
            if len(self.accumulated_audio) >= 100:
                # Transcribe preview
                mock_arr = make_mock_float_array(self.accumulated_audio)
                res = self.dll.Java_com_openfree_client_WhisperEngine_transcribe(
                    None, None, ctypes.byref(mock_arr)
                )
                if res:
                    self.status_text = f"Transcription Preview: {res.decode('utf-8')}"
            
        self.audio_recorder.start_recording(on_audio_data)
        
    def stop_dictation(self):
        self.btn_mic_active = False
        self.audio_recorder.stop_recording()
        
        # Check if local model loaded
        local_model_loaded = self.dll.GetMockModelLoadedState()
        if local_model_loaded:
            # Local Offline Dictation flow
            if len(self.accumulated_audio) > 0:
                mock_arr = make_mock_float_array(self.accumulated_audio)
                res = self.dll.Java_com_openfree_client_WhisperEngine_transcribe(
                    None, None, ctypes.byref(mock_arr)
                )
                if res and res != b"audio_too_short":
                    text = res.decode('utf-8')
                    if self.input_connection:
                        self.input_connection.commitText(text, 1)
                    self.status_text = "Done"
                else:
                    self.status_text = "Ready"
            else:
                self.status_text = "Ready"
        else:
            # Fallback Dictation flow
            fallback_url = self.prefs.get_remote_fallback_url()
            if fallback_url:
                self.status_text = "Sending to fallback..."
                try:
                    # Package raw float audio data back to PCM bytes for post
                    pcm_shorts = [int(s * 32768.0) for s in self.accumulated_audio]
                    pcm_bytes = struct.pack(f"<{len(pcm_shorts)}h", *pcm_shorts)
                    
                    text = self.perform_remote_fallback(fallback_url, pcm_bytes)
                    if text:
                        if self.input_connection:
                            self.input_connection.commitText(text, 1)
                        self.status_text = "Done"
                    else:
                        self.status_text = "Fallback failed"
                except Exception:
                    self.status_text = "Fallback failed"
            else:
                self.status_text = "Ready"

    def perform_remote_fallback(self, url, pcm_bytes):
        boundary = "Boundary-1234567890"
        headers = {
            'Content-Type': f'multipart/form-data; boundary={boundary}',
            'User-Agent': 'OpenFreeIME-Fallback'
        }
        body = (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="audio"; filename="audio.pcm"\r\n'
            f"Content-Type: application/octet-stream\r\n\r\n"
        ).encode('utf-8')
        body += pcm_bytes + f"\r\n--{boundary}--\r\n".encode('utf-8')
        
        req = urllib.request.Request(url, data=body, headers=headers, method='POST')
        with urllib.request.urlopen(req, timeout=2.0) as response:
            if response.status == 200:
                res_data = response.read().decode('utf-8')
                res_json = json.loads(res_data)
                return res_json.get("text", "")
        return ""


class WorkloadHTTPRequestHandler(SimpleHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def do_POST(self):
        if self.path == "/transcribe":
            content_type = self.headers.get('Content-Type', '')
            if 'multipart/form-data' in content_type:
                content_length = int(self.headers.get('Content-Length', 0))
                body = self.rfile.read(content_length)
                if len(body) > 0:
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/json')
                    self.end_headers()
                    response_json = json.dumps({"text": "transcription from remote server"})
                    self.wfile.write(response_json.encode('utf-8'))
                    return
            self.send_response(400)
            self.end_headers()
        else:
            self.send_response(404)
            self.end_headers()


class TestTier4Workloads(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Temp directory for cache & serving
        cls.temp_dir = tempfile.TemporaryDirectory()
        cls.cache_dir = os.path.join(cls.temp_dir.name, "cache")
        cls.serve_dir = os.path.join(cls.temp_dir.name, "serve")
        os.makedirs(cls.cache_dir, exist_ok=True)
        os.makedirs(cls.serve_dir, exist_ok=True)
        
        # Resolve JNI DLL
        cls.test_dir = os.path.dirname(os.path.abspath(__file__))
        cls.native_mock_dir = os.path.join(cls.test_dir, "native_mock")
        cls.build_dir = os.path.join(cls.native_mock_dir, "build")
        cls.lib_path = cls._find_shared_library(cls.build_dir)
        if not cls.lib_path:
            raise FileNotFoundError(f"Could not find compiled shared library in {cls.build_dir}")
            
        cls.dll = ctypes.CDLL(cls.lib_path)
        cls._setup_signatures(cls.dll)
        
        # Create mock HF model file
        cls.model_filename = "ggml-base.en-q5_1.bin"
        with open(os.path.join(cls.serve_dir, cls.model_filename), "wb") as f:
            f.write(b"mock-hf-model-content")
            
        # Start Workload HTTP Server serving files and handling fallback POST
        class CustomHandler(WorkloadHTTPRequestHandler):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, directory=cls.serve_dir, **kwargs)
                
        cls.httpd = HTTPServer(('127.0.0.1', 0), CustomHandler)
        cls.server_port = cls.httpd.server_port
        cls.server_thread = threading.Thread(target=cls.httpd.serve_forever)
        cls.server_thread.daemon = True
        cls.server_thread.start()
        
        cls.base_url = f"http://127.0.0.1:{cls.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.server_thread.join()
        cls.temp_dir.cleanup()

    @classmethod
    def _find_shared_library(cls, search_dir):
        extensions = [".dll", ".so", ".dylib"]
        for root, _, files in os.walk(search_dir):
            for file in files:
                name = file.lower()
                if any(name.endswith(ext) for ext in extensions) and "whisper_mock" in name:
                    return os.path.join(root, file)
        return None

    @classmethod
    def _setup_signatures(cls, dll):
        dll.GetMockModelLoadedState.restype = ctypes.c_bool
        dll.GetMockModelLoadedState.argtypes = []

        dll.SetMockModelLoadedState.restype = None
        dll.SetMockModelLoadedState.argtypes = [ctypes.c_bool]

        dll.SetMockTranscription.restype = None
        dll.SetMockTranscription.argtypes = [ctypes.c_char_p]

        dll.Java_com_openfree_client_WhisperEngine_loadModel.restype = ctypes.c_bool
        dll.Java_com_openfree_client_WhisperEngine_loadModel.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_char_p]

        dll.Java_com_openfree_client_WhisperEngine_unloadModel.restype = None
        dll.Java_com_openfree_client_WhisperEngine_unloadModel.argtypes = [ctypes.c_void_p, ctypes.c_void_p]

        dll.Java_com_openfree_client_WhisperEngine_transcribe.restype = ctypes.c_char_p
        dll.Java_com_openfree_client_WhisperEngine_transcribe.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]

    def setUp(self):
        self.dll.SetMockModelLoadedState(False)
        self.dll.SetMockTranscription(b"mocked whisper transcription")
        self.prefs = MockSharedPreferences(self.cache_dir)
        self.downloader = MockDownloader(self.prefs)
        self.recorder = MockAudioRecorder()
        
        # Instantiate keyboard
        self.ime = MockOpenFreeIME(self.dll, self.prefs)
        self.ime.onCreate()
        
        self.ic = MockInputConnection()
        self.ime.setInputConnection(self.ic)
        
        # Start input session
        self.ime.onStartInputView({}, restarting=False)

    def test_offline_dictation_workload(self):
        """
        Offline Dictation flow (Mic tap -> record -> transcribe -> commitText)
        """
        # 1. Load local model path successfully
        loaded = self.dll.Java_com_openfree_client_WhisperEngine_loadModel(None, None, None)
        self.assertTrue(loaded)
        
        # 2. Mic tap to start recording
        self.ime.handleMicTap(self.recorder)
        self.assertTrue(self.ime.btn_mic_active)
        self.assertEqual(self.ime.status_text, "Recording")
        
        # 3. Simulate audio samples (160 samples = 10ms)
        self.recorder.process_raw_pcm_bytes(struct.pack("<160h", *([100] * 160)))
        
        # 4. Mic tap again to stop dictation and transcribe
        self.dll.SetMockTranscription(b"Hello world offline transcription")
        self.ime.handleMicTap(self.recorder)
        
        # 5. Verify the flow completed successfully
        self.assertFalse(self.ime.btn_mic_active)
        self.assertEqual(self.ime.status_text, "Done")
        self.assertEqual(self.ic.committed_text, "Hello world offline transcription")

    def test_fallback_dictation_workload(self):
        """
        Fallback Dictation flow (No local model -> fallback to remote server -> multipart POST -> commit response)
        """
        # 1. No local model is loaded
        self.assertFalse(self.dll.GetMockModelLoadedState())
        
        # 2. Configure fallback url
        fallback_url = f"{self.base_url}/transcribe"
        self.prefs.putString("pref_key_remote_fallback_url", fallback_url)
        
        # 3. Mic tap to record
        self.ime.handleMicTap(self.recorder)
        self.assertTrue(self.ime.btn_mic_active)
        
        # 4. Feed audio samples
        self.recorder.process_raw_pcm_bytes(struct.pack("<160h", *([200] * 160)))
        
        # 5. Mic tap to stop, triggers multipart HTTP POST fallback
        self.ime.handleMicTap(self.recorder)
        
        # 6. Verify fallback text is committed successfully
        self.assertEqual(self.ime.status_text, "Done")
        self.assertEqual(self.ic.committed_text, "transcription from remote server")

    def test_settings_download_and_offline_dictation_workload(self):
        """
        Settings download & offline dictation flow (HF download -> preference update -> keyboard offline STT)
        """
        # 1. HF model is not downloaded yet, local model cannot be loaded
        invalid_path = os.path.join(self.cache_dir, "nonexistent-model.bin")
        loaded = self.dll.Java_com_openfree_client_WhisperEngine_loadModel(
            None, None, invalid_path.encode('utf-8')
        )
        self.assertFalse(loaded)
        
        # 2. Downloader pulls the model from mock server, updates path in prefs
        download_url = f"{self.base_url}/{self.model_filename}"
        dest_path = os.path.join(self.cache_dir, "ggml-base.en-q5_1.bin")
        
        success = self.downloader.download_model(download_url, dest_path)
        self.assertTrue(success)
        self.assertTrue(os.path.exists(dest_path))
        
        # 3. Keyboard retrieves model path from prefs and loads it successfully
        model_path_from_prefs = self.prefs.get_model_path()
        self.assertEqual(model_path_from_prefs, dest_path)
        
        loaded = self.dll.Java_com_openfree_client_WhisperEngine_loadModel(
            None, None, model_path_from_prefs.encode('utf-8')
        )
        self.assertTrue(loaded)
        
        # 4. Keyboard offline dictation
        self.dll.SetMockTranscription(b"Offline dictation works after settings download")
        
        self.ime.handleMicTap(self.recorder)
        self.recorder.process_raw_pcm_bytes(struct.pack("<160h", *([300] * 160)))
        self.ime.handleMicTap(self.recorder)
        
        self.assertEqual(self.ime.status_text, "Done")
        self.assertEqual(self.ic.committed_text, "Offline dictation works after settings download")

    def test_continuous_preview_dictation_workload(self):
        """
        Continuous preview dictation flow (Multiple PCM chunks -> dynamic status preview updates -> final commitText)
        """
        # 1. Load model
        loaded = self.dll.Java_com_openfree_client_WhisperEngine_loadModel(None, None, None)
        self.assertTrue(loaded)
        
        # 2. Tap mic to record
        self.ime.handleMicTap(self.recorder)
        self.assertTrue(self.ime.btn_mic_active)
        
        # 3. Send Chunk 1 (160 samples)
        self.dll.SetMockTranscription(b"Preview: hello")
        self.recorder.process_raw_pcm_bytes(struct.pack("<160h", *([150] * 160)))
        self.assertEqual(self.ime.status_text, "Transcription Preview: Preview: hello")
        
        # 4. Send Chunk 2 (160 samples)
        self.dll.SetMockTranscription(b"Preview: hello world")
        self.recorder.process_raw_pcm_bytes(struct.pack("<160h", *([250] * 160)))
        self.assertEqual(self.ime.status_text, "Transcription Preview: Preview: hello world")
        
        # 5. Tap mic to stop, final text is committed
        self.dll.SetMockTranscription(b"hello world")
        self.ime.handleMicTap(self.recorder)
        
        self.assertEqual(self.ime.status_text, "Done")
        self.assertEqual(self.ic.committed_text, "hello world")

    def test_interrupted_dictation_workload(self):
        """
        Interrupted dictation flow (Active recording -> IME input connection lost -> recorder stops gracefully)
        """
        # 1. Load model
        loaded = self.dll.Java_com_openfree_client_WhisperEngine_loadModel(None, None, None)
        self.assertTrue(loaded)
        
        # 2. Start recording
        self.ime.handleMicTap(self.recorder)
        self.assertTrue(self.ime.btn_mic_active)
        self.assertTrue(self.recorder.is_recording)
        self.assertEqual(self.ime.status_text, "Recording")
        
        # 3. Simulate input connection lost (IME onFinishInputView gets called)
        self.ime.onFinishInputView()
        
        # 4. Verify that recorder stops gracefully and state becomes Idle
        self.assertFalse(self.ime.btn_mic_active)
        self.assertFalse(self.recorder.is_recording)
        self.assertEqual(self.ime.status_text, "Idle")
        # No text committed
        self.assertEqual(self.ic.committed_text, "")


if __name__ == "__main__":
    unittest.main()
