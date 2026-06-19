import unittest
import os
import sys
import ctypes
import subprocess
import tempfile

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

class TestWhisperJniWrapper(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Determine paths
        cls.test_dir = os.path.dirname(os.path.abspath(__file__))
        cls.native_mock_dir = os.path.join(cls.test_dir, "native_mock")
        cls.build_dir = os.path.join(cls.native_mock_dir, "build")
        
        # Build the DLL
        os.makedirs(cls.build_dir, exist_ok=True)
        
        # Configure cmake
        print("Configuring CMake for mock native library...", flush=True)
        configure_cmd = ["cmake", "-S", cls.native_mock_dir, "-B", cls.build_dir]
        result = subprocess.run(configure_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if result.returncode != 0:
            print("CMake configure stdout:\n", result.stdout)
            print("CMake configure stderr:\n", result.stderr)
            raise RuntimeError(f"CMake configure failed with code {result.returncode}")
            
        # Build cmake
        print("Building mock native library...", flush=True)
        build_cmd = ["cmake", "--build", cls.build_dir, "--config", "Release"]
        result = subprocess.run(build_cmd, stdout=subprocess.PIPE, text=True, stderr=subprocess.PIPE)
        if result.returncode != 0:
            print("CMake build stdout:\n", result.stdout)
            print("CMake build stderr:\n", result.stderr)
            raise RuntimeError(f"CMake build failed with code {result.returncode}")
            
        # Find the compiled shared library
        cls.lib_path = cls._find_shared_library(cls.build_dir)
        if not cls.lib_path:
            raise FileNotFoundError(f"Could not find compiled shared library in {cls.build_dir}")
            
        print(f"Loading native mock library from {cls.lib_path}", flush=True)
        cls.dll = ctypes.CDLL(cls.lib_path)
        cls._setup_signatures(cls.dll)

    @classmethod
    def _find_shared_library(cls, search_dir):
        extensions = [".dll", ".so", ".dylib"]
        # Recursively search for any compiled library files
        for root, _, files in os.walk(search_dir):
            for file in files:
                name = file.lower()
                if any(name.endswith(ext) for ext in extensions) and "whisper_mock" in name:
                    return os.path.join(root, file)
        return None

    @classmethod
    def _setup_signatures(cls, dll):
        # Setup GetMockModelLoadedState
        dll.GetMockModelLoadedState.restype = ctypes.c_bool
        dll.GetMockModelLoadedState.argtypes = []

        # Setup SetMockModelLoadedState
        dll.SetMockModelLoadedState.restype = None
        dll.SetMockModelLoadedState.argtypes = [ctypes.c_bool]

        # Setup GetMockTranscription
        dll.GetMockTranscription.restype = ctypes.c_char_p
        dll.GetMockTranscription.argtypes = []

        # Setup SetMockTranscription
        dll.SetMockTranscription.restype = None
        dll.SetMockTranscription.argtypes = [ctypes.c_char_p]

        # JNI Methods (mocked via ctypes)
        dll.Java_com_openfree_client_WhisperEngine_loadModel.restype = ctypes.c_bool
        dll.Java_com_openfree_client_WhisperEngine_loadModel.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_char_p]

        dll.Java_com_openfree_client_WhisperEngine_unloadModel.restype = None
        dll.Java_com_openfree_client_WhisperEngine_unloadModel.argtypes = [ctypes.c_void_p, ctypes.c_void_p]

        dll.Java_com_openfree_client_WhisperEngine_transcribe.restype = ctypes.c_char_p
        dll.Java_com_openfree_client_WhisperEngine_transcribe.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]

        dll.Java_com_openfree_client_WhisperEngine_transcribeBytes.restype = ctypes.c_char_p
        dll.Java_com_openfree_client_WhisperEngine_transcribeBytes.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int64, ctypes.c_void_p]

        dll.Java_com_openfree_client_WhisperEngine_setThreadCount.restype = ctypes.c_bool
        dll.Java_com_openfree_client_WhisperEngine_setThreadCount.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int]

        dll.SetMockJniErrorState.restype = None
        dll.SetMockJniErrorState.argtypes = [ctypes.c_bool]

        dll.GetMockThreadCount.restype = ctypes.c_int
        dll.GetMockThreadCount.argtypes = []

    def setUp(self):
        # Reset mock state before each test
        self.dll.SetMockModelLoadedState(False)
        self.dll.SetMockTranscription(b"mocked whisper transcription")
        self.dll.SetMockJniErrorState(False)
        self.dll.Java_com_openfree_client_WhisperEngine_setThreadCount(None, None, 4)

    # --- Feature Coverage (Tier 1) ---

    def test_load_and_unload_model(self):
        # Initial state should be false
        self.assertFalse(self.dll.GetMockModelLoadedState())
        
        # Load model via JNI signature (passing None matches backwards compatibility)
        loaded = self.dll.Java_com_openfree_client_WhisperEngine_loadModel(None, None, None)
        self.assertTrue(loaded)
        self.assertTrue(self.dll.GetMockModelLoadedState())
        
        # Unload model via JNI signature
        self.dll.Java_com_openfree_client_WhisperEngine_unloadModel(None, None)
        self.assertFalse(self.dll.GetMockModelLoadedState())

    def test_transcribe_with_model_loaded(self):
        # Load model first
        self.dll.Java_com_openfree_client_WhisperEngine_loadModel(None, None, None)
        
        # Set expected transcription
        expected_text = b"test transcription string"
        self.dll.SetMockTranscription(expected_text)
        
        # Call JNI transcribe
        result = self.dll.Java_com_openfree_client_WhisperEngine_transcribe(None, None, None)
        self.assertEqual(result, expected_text)

    def test_transcribe_without_model_loaded(self):
        # Call JNI transcribe without loading model
        result = self.dll.Java_com_openfree_client_WhisperEngine_transcribe(None, None, None)
        self.assertEqual(result, b"")

    def test_transcribe_bytes_with_model_loaded(self):
        # Load model first
        self.dll.Java_com_openfree_client_WhisperEngine_loadModel(None, None, None)
        
        # Set expected transcription
        expected_text = b"transcribe bytes result"
        self.dll.SetMockTranscription(expected_text)
        
        # Call JNI transcribeBytes
        result = self.dll.Java_com_openfree_client_WhisperEngine_transcribeBytes(None, None, 0, None)
        self.assertEqual(result, expected_text)

    def test_transcribe_bytes_without_model_loaded(self):
        # Call JNI transcribeBytes without loading model
        result = self.dll.Java_com_openfree_client_WhisperEngine_transcribeBytes(None, None, 0, None)
        self.assertEqual(result, b"")

    # --- Boundary & Corner Cases (Tier 2) ---

    def test_invalid_model_load_failure(self):
        # Path to a file that does not exist
        invalid_path = b"non_existent_model_file.bin"
        loaded = self.dll.Java_com_openfree_client_WhisperEngine_loadModel(None, None, invalid_path)
        self.assertFalse(loaded)
        self.assertFalse(self.dll.GetMockModelLoadedState())

    def test_transcribe_empty_audio_samples(self):
        # Load model first
        self.dll.Java_com_openfree_client_WhisperEngine_loadModel(None, None, None)
        
        # Empty samples (length 0)
        empty_arr = make_mock_float_array([])
        result = self.dll.Java_com_openfree_client_WhisperEngine_transcribe(
            None, None, ctypes.byref(empty_arr)
        )
        self.assertEqual(result, b"")

    def test_transcribe_tiny_audio_samples(self):
        # Load model first
        self.dll.Java_com_openfree_client_WhisperEngine_loadModel(None, None, None)
        
        # Tiny samples (< 100 samples)
        tiny_arr = make_mock_float_array([0.1] * 50)
        result = self.dll.Java_com_openfree_client_WhisperEngine_transcribe(
            None, None, ctypes.byref(tiny_arr)
        )
        self.assertEqual(result, b"audio_too_short")

    def test_thread_parameter_validation(self):
        # Valid threads (1 to 64)
        success = self.dll.Java_com_openfree_client_WhisperEngine_setThreadCount(None, None, 8)
        self.assertTrue(success)
        self.assertEqual(self.dll.GetMockThreadCount(), 8)
        
        # Invalid thread counts
        self.assertFalse(self.dll.Java_com_openfree_client_WhisperEngine_setThreadCount(None, None, 0))
        self.assertFalse(self.dll.Java_com_openfree_client_WhisperEngine_setThreadCount(None, None, -5))
        self.assertFalse(self.dll.Java_com_openfree_client_WhisperEngine_setThreadCount(None, None, 65))
        
        # Should remain unchanged at the last valid value (8)
        self.assertEqual(self.dll.GetMockThreadCount(), 8)

    def test_jni_error_handling_path(self):
        # Simulate native error state
        self.dll.SetMockJniErrorState(True)
        
        # Any attempt to load model or transcribe should fail/return empty
        loaded = self.dll.Java_com_openfree_client_WhisperEngine_loadModel(None, None, None)
        self.assertFalse(loaded)
        
        result = self.dll.Java_com_openfree_client_WhisperEngine_transcribe(None, None, None)
        self.assertEqual(result, b"")

if __name__ == "__main__":
    unittest.main()
