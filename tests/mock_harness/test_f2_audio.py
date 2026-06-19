import unittest
import struct
import math

def pcm16_to_float(pcm_data: bytes) -> list:
    """
    Converts 16-bit PCM bytes (little-endian) to normalized FloatArray [-1.0, 1.0].
    Truncates odd trailing bytes.
    """
    if not pcm_data:
        return []
    
    num_samples = len(pcm_data) // 2
    if num_samples == 0:
        return []
        
    # Unpack as signed short integers (little-endian '<h')
    samples = struct.unpack(f"<{num_samples}h", pcm_data[:num_samples*2])
    
    # Normalize to [-1.0, 1.0] by dividing by 32768.0
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


class TestAudioCaptureEngine(unittest.TestCase):
    # --- Feature Coverage (Tier 1) ---

    def test_start_and_stop_recording_state(self):
        recorder = MockAudioRecorder()
        self.assertFalse(recorder.is_recording)
        
        def dummy_callback(samples):
            pass
            
        recorder.start_recording(dummy_callback)
        self.assertTrue(recorder.is_recording)
        self.assertEqual(recorder.callback, dummy_callback)
        
        recorder.stop_recording()
        self.assertFalse(recorder.is_recording)
        self.assertIsNone(recorder.callback)

    def test_basic_pcm_conversion(self):
        # 16-bit PCM: 0, 16384, -16384, 8192, -8192
        # Expected floats: 0.0, 0.5, -0.5, 0.25, -0.25
        pcm_values = [0, 16384, -16384, 8192, -8192]
        pcm_bytes = struct.pack(f"<{len(pcm_values)}h", *pcm_values)
        
        floats = pcm16_to_float(pcm_bytes)
        self.assertEqual(len(floats), 5)
        self.assertEqual(floats[0], 0.0)
        self.assertEqual(floats[1], 0.5)
        self.assertEqual(floats[2], -0.5)
        self.assertEqual(floats[3], 0.25)
        self.assertEqual(floats[4], -0.25)

    def test_sine_wave_sound_conversion(self):
        # Generate a short 1000Hz sine wave at 16kHz sample rate
        sample_rate = 16000
        frequency = 1000
        amplitude = 16384  # 0.5 amplitude in float
        duration = 0.01  # 10ms (160 samples)
        
        num_samples = int(sample_rate * duration)
        pcm_values = []
        for i in range(num_samples):
            t = i / sample_rate
            val = int(amplitude * math.sin(2 * math.pi * frequency * t))
            pcm_values.append(val)
            
        pcm_bytes = struct.pack(f"<{num_samples}h", *pcm_values)
        floats = pcm16_to_float(pcm_bytes)
        
        self.assertEqual(len(floats), num_samples)
        for i in range(num_samples):
            expected = pcm_values[i] / 32768.0
            self.assertAlmostEqual(floats[i], expected, places=5)

    def test_callback_buffer_dispatch(self):
        recorder = MockAudioRecorder()
        received_floats = []
        
        def callback(samples):
            received_floats.extend(samples)
            
        recorder.start_recording(callback)
        
        pcm_values = [1000, -2000, 3000]
        pcm_bytes = struct.pack(f"<{len(pcm_values)}h", *pcm_values)
        
        recorder.process_raw_pcm_bytes(pcm_bytes)
        
        self.assertEqual(len(received_floats), 3)
        self.assertEqual(received_floats[0], 1000 / 32768.0)
        self.assertEqual(received_floats[1], -2000 / 32768.0)
        self.assertEqual(received_floats[2], 3000 / 32768.0)

    def test_multiple_buffers_sequential(self):
        recorder = MockAudioRecorder()
        received_chunks = []
        
        def callback(samples):
            received_chunks.append(samples)
            
        recorder.start_recording(callback)
        
        # Chunk 1
        recorder.process_raw_pcm_bytes(struct.pack("<2h", 100, 200))
        # Chunk 2
        recorder.process_raw_pcm_bytes(struct.pack("<2h", -300, -400))
        
        self.assertEqual(len(received_chunks), 2)
        self.assertEqual(received_chunks[0], [100 / 32768.0, 200 / 32768.0])
        self.assertEqual(received_chunks[1], [-300 / 32768.0, -400 / 32768.0])


    # --- Boundary & Corner Cases (Tier 2) ---

    def test_empty_bytes(self):
        floats = pcm16_to_float(b"")
        self.assertEqual(floats, [])

    def test_odd_byte_count(self):
        # 3 bytes is 1 sample and 1 leftover byte.
        # It should process the 1 sample and safely ignore the trailing byte.
        pcm_bytes = struct.pack("<h", 1234) + b"\x00"
        self.assertEqual(len(pcm_bytes), 3)
        
        floats = pcm16_to_float(pcm_bytes)
        self.assertEqual(len(floats), 1)
        self.assertEqual(floats[0], 1234 / 32768.0)

    def test_maximum_negative_value(self):
        # Minimum short value -32768
        pcm_bytes = struct.pack("<h", -32768)
        floats = pcm16_to_float(pcm_bytes)
        self.assertEqual(len(floats), 1)
        self.assertEqual(floats[0], -1.0)

    def test_maximum_positive_value(self):
        # Maximum short value 32767
        pcm_bytes = struct.pack("<h", 32767)
        floats = pcm16_to_float(pcm_bytes)
        self.assertEqual(len(floats), 1)
        self.assertEqual(floats[0], 32767 / 32768.0)

    def test_zero_silence(self):
        pcm_bytes = struct.pack("<3h", 0, 0, 0)
        floats = pcm16_to_float(pcm_bytes)
        self.assertEqual(floats, [0.0, 0.0, 0.0])

if __name__ == "__main__":
    unittest.main()
