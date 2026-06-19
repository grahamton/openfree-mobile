import unittest
import os
import sys
import ctypes
import subprocess
import tempfile
import struct
import math
import urllib.request
import threading
from http.server import HTTPServer, SimpleHTTPRequestHandler

# Import or define F2's Audio Conversion & Recorder
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


# Import or define F3's JFloatArray mapping
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


# Import or define F4's SharedPreferences & Downloader
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


class SilentHTTPRequestHandler(SimpleHTTPRequestHandler):
    def log_message(self, format, *args):
        pass


class TestTier3CrossFeature(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Temp dir
        cls.temp_dir = tempfile.TemporaryDirectory()
        cls.cache_dir = os.path.join(cls.temp_dir.name, "cache")
        os.makedirs(cls.cache_dir, exist_ok=True)
        
        # Resolve path to DLL
        cls.test_dir = os.path.dirname(os.path.abspath(__file__))
        cls.native_mock_dir = os.path.join(cls.test_dir, "native_mock")
        cls.build_dir = os.path.join(cls.native_mock_dir, "build")
        
        # Load the DLL
        cls.lib_path = cls._find_shared_library(cls.build_dir)
        if not cls.lib_path:
            raise FileNotFoundError(f"Could not find compiled shared library in {cls.build_dir}")
            
        cls.dll = ctypes.CDLL(cls.lib_path)
        cls._setup_signatures(cls.dll)
        
        # Create dummy file to serve
        cls.model_content = b"ggml-model-binary-data"
        cls.serve_dir = os.path.join(cls.temp_dir.name, "serve")
        os.makedirs(cls.serve_dir, exist_ok=True)
        cls.model_filename = "ggml-base.en-q5_1.bin"
        with open(os.path.join(cls.serve_dir, cls.model_filename), "wb") as f:
            f.write(cls.model_content)
            
        # Start HTTP server
        class CustomHandler(SilentHTTPRequestHandler):
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

        dll.Java_com_openfree_client_WhisperEngine_transcribeBytes.restype = ctypes.c_char_p
        dll.Java_com_openfree_client_WhisperEngine_transcribeBytes.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int64, ctypes.c_void_p]

    def setUp(self):
        self.dll.SetMockModelLoadedState(False)
        self.dll.SetMockTranscription(b"mocked whisper transcription")
        self.prefs = MockSharedPreferences(self.cache_dir)
        self.downloader = MockDownloader(self.prefs)
        self.recorder = MockAudioRecorder()

    def test_f2_f3_integration_direct_pcm_stream(self):
        """
        F2 <-> F3: AudioRecorder converts PCM and feeds Whisper JNI transcribe directly.
        """
        # 1. Load Whisper model
        loaded = self.dll.Java_com_openfree_client_WhisperEngine_loadModel(None, None, None)
        self.assertTrue(loaded)
        
        # 2. Setup recorder and transcription callback
        transcribed_texts = []
        
        def on_audio_data(samples):
            # Feed directly to Whisper JNI transcribe
            mock_arr = make_mock_float_array(samples)
            res = self.dll.Java_com_openfree_client_WhisperEngine_transcribe(
                None, None, ctypes.byref(mock_arr)
            )
            transcribed_texts.append(res)
            
        self.recorder.start_recording(on_audio_data)
        
        # 3. Simulate recording PCM samples (duration 100ms at 16kHz is 1600 samples)
        # We write a 1600-sample PCM buffer
        pcm_data = struct.pack("<1600h", *([1000] * 1600))
        self.recorder.process_raw_pcm_bytes(pcm_data)
        
        # 4. Assert direct integration worked
        self.assertEqual(len(transcribed_texts), 1)
        self.assertEqual(transcribed_texts[0], b"mocked whisper transcription")

    def test_f4_f3_integration_custom_preference_path(self):
        """
        F4 <-> F3: SharedPreferences custom model path loaded by WhisperEngine.
        """
        # 1. Create a dummy model file on disk
        custom_model_path = os.path.join(self.cache_dir, "my_custom_model.bin")
        with open(custom_model_path, "wb") as f:
            f.write(b"mock_model_data_custom")
            
        # 2. Update SharedPreferences custom path
        self.prefs.putString("pref_key_model_path", custom_model_path)
        
        # 3. Retrieve path and load in WhisperEngine JNI
        path_from_prefs = self.prefs.get_model_path()
        self.assertEqual(path_from_prefs, custom_model_path)
        
        loaded = self.dll.Java_com_openfree_client_WhisperEngine_loadModel(
            None, None, path_from_prefs.encode('utf-8')
        )
        self.assertTrue(loaded)
        self.assertTrue(self.dll.GetMockModelLoadedState())

    def test_f4_f3_downloader_updates_preferences_and_engine_loads(self):
        """
        F4 <-> F3: Downloader pulls model to cache, updates SharedPreferences path, which WhisperEngine then loads.
        """
        # 1. Start download
        download_url = f"{self.base_url}/{self.model_filename}"
        dest_path = os.path.join(self.cache_dir, "new-downloaded-model.bin")
        
        success = self.downloader.download_model(download_url, dest_path)
        self.assertTrue(success)
        self.assertTrue(os.path.exists(dest_path))
        
        # 2. Verify SharedPreferences path is updated
        updated_path = self.prefs.get_model_path()
        self.assertEqual(updated_path, dest_path)
        
        # 3. WhisperEngine loads the newly downloaded model path
        loaded = self.dll.Java_com_openfree_client_WhisperEngine_loadModel(
            None, None, updated_path.encode('utf-8')
        )
        self.assertTrue(loaded)
        self.assertTrue(self.dll.GetMockModelLoadedState())

    def test_f2_f4_fallback_dictation_flow(self):
        """
        F2 <-> F4: AudioRecorder recording when local model is unavailable, triggering remote fallback request structure.
        """
        # 1. local model is unavailable (not loaded / doesn't exist)
        local_model_loaded = self.dll.GetMockModelLoadedState()
        self.assertFalse(local_model_loaded)
        
        # 2. Setup fallback URL preference
        fallback_url = "http://fallback-server.internal/api/transcribe"
        self.prefs.putString("pref_key_remote_fallback_url", fallback_url)
        
        # 3. AudioRecorder starts and captures PCM, but local model load fails
        fallback_triggered = False
        captured_samples = []
        
        def on_audio_data(samples):
            nonlocal fallback_triggered, captured_samples
            captured_samples.extend(samples)
            # Try to use local model first
            local_loaded = self.dll.GetMockModelLoadedState()
            if not local_loaded:
                # Local model failed, check fallback
                fallback_target = self.prefs.get_remote_fallback_url()
                if fallback_target == fallback_url:
                    fallback_triggered = True
                    
        self.recorder.start_recording(on_audio_data)
        
        # 4. Feed audio
        self.recorder.process_raw_pcm_bytes(struct.pack("<100h", *([500] * 100)))
        
        # 5. Verify fallback was triggered
        self.assertTrue(fallback_triggered)
        self.assertEqual(len(captured_samples), 100)


if __name__ == "__main__":
    unittest.main()
