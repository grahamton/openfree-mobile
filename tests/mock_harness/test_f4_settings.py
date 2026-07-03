import unittest
import os
import tempfile
import threading
import urllib.request
from http.server import HTTPServer, SimpleHTTPRequestHandler

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


class MockDownloader:
    def __init__(self, prefs: MockSharedPreferences):
        self.prefs = prefs
        
    def download_model(self, url: str, dest_path: str) -> bool:
        temp_path = dest_path + ".tmp"
        try:
            # Cleanup previous temp file if any
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
            
            # Check empty file boundary
            if os.path.getsize(temp_path) == 0:
                if os.path.exists(temp_path):
                    os.remove(temp_path)
                return False
                
            # Rename temp file to final destination
            if os.path.exists(dest_path):
                os.remove(dest_path)
            os.rename(temp_path, dest_path)
            
            # Update settings preference
            self.prefs.putString("pref_key_model_path", dest_path)
            return True
            
        except Exception:
            # Cleanup temp file on failure
            if os.path.exists(temp_path):
                try:
                    os.remove(temp_path)
                except OSError:
                    pass
            return False


class SilentHTTPRequestHandler(SimpleHTTPRequestHandler):
    def log_message(self, format, *args):
        # Suppress logging request details to stderr during test runs
        pass


class TestSettingsAndDownloader(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Create a temp directory for downloads and cache
        cls.temp_dir = tempfile.TemporaryDirectory()
        cls.cache_dir = os.path.join(cls.temp_dir.name, "cache")
        os.makedirs(cls.cache_dir, exist_ok=True)
        
        # Create dummy file to serve
        cls.model_content = b"whisper-model-data-mock-ggml"
        cls.serve_dir = os.path.join(cls.temp_dir.name, "serve")
        os.makedirs(cls.serve_dir, exist_ok=True)
        cls.model_filename = "ggml-base.en-q5_1.bin"
        with open(os.path.join(cls.serve_dir, cls.model_filename), "wb") as f:
            f.write(cls.model_content)
            
        # Empty file to serve for boundary test
        cls.empty_filename = "empty-model.bin"
        with open(os.path.join(cls.serve_dir, cls.empty_filename), "wb") as f:
            f.write(b"")

        # Start HTTP server
        # Change working directory of the server to serve files from serve_dir
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
        # Stop HTTP server
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.server_thread.join()
        cls.temp_dir.cleanup()

    def setUp(self):
        self.prefs = MockSharedPreferences(self.cache_dir)
        self.downloader = MockDownloader(self.prefs)

    # --- Feature Coverage (Tier 1) ---

    def test_default_preferences(self):
        expected_default = os.path.join(self.cache_dir, "ggml-base.en-q5_1.bin")
        self.assertEqual(self.prefs.get_model_path(), expected_default)

    def test_update_preferences(self):
        custom_path = os.path.join(self.cache_dir, "custom-model.bin")
        self.prefs.putString("pref_key_model_path", custom_path)
        self.assertEqual(self.prefs.get_model_path(), custom_path)

    def test_download_model_success(self):
        url = f"{self.base_url}/{self.model_filename}"
        dest_path = os.path.join(self.cache_dir, "downloaded-model.bin")
        
        success = self.downloader.download_model(url, dest_path)
        self.assertTrue(success)
        self.assertTrue(os.path.exists(dest_path))
        
        with open(dest_path, "rb") as f:
            downloaded_data = f.read()
        self.assertEqual(downloaded_data, self.model_content)

    def test_download_updates_preference(self):
        url = f"{self.base_url}/{self.model_filename}"
        dest_path = os.path.join(self.cache_dir, "downloaded-model.bin")
        
        success = self.downloader.download_model(url, dest_path)
        self.assertTrue(success)
        self.assertEqual(self.prefs.get_model_path(), dest_path)

    # --- Boundary & Corner Cases (Tier 2) ---

    def test_download_http_404(self):
        url = f"{self.base_url}/nonexistent-file.bin"
        dest_path = os.path.join(self.cache_dir, "should-not-exist.bin")
        
        success = self.downloader.download_model(url, dest_path)
        self.assertFalse(success)
        self.assertFalse(os.path.exists(dest_path))
        # Preference should NOT be updated to the non-existent file path
        self.assertNotEqual(self.prefs.get_model_path(), dest_path)

    def test_download_connection_refused(self):
        # Connect to a port that is highly likely to be inactive
        url = "http://127.0.0.1:59999/ggml.bin"
        dest_path = os.path.join(self.cache_dir, "conn-refused.bin")
        
        success = self.downloader.download_model(url, dest_path)
        self.assertFalse(success)
        self.assertFalse(os.path.exists(dest_path))

    def test_download_empty_file(self):
        url = f"{self.base_url}/{self.empty_filename}"
        dest_path = os.path.join(self.cache_dir, "empty-download.bin")
        
        success = self.downloader.download_model(url, dest_path)
        # Downloader should fail and clean up empty file
        self.assertFalse(success)
        self.assertFalse(os.path.exists(dest_path))

    def test_downloader_temp_file_cleanup(self):
        # Induce a download failure by pointing to a nonexistent file
        url = f"{self.base_url}/another-404-error"
        dest_path = os.path.join(self.cache_dir, "cleanup-test.bin")
        temp_path = dest_path + ".tmp"
        
        success = self.downloader.download_model(url, dest_path)
        self.assertFalse(success)
        self.assertFalse(os.path.exists(dest_path))
        self.assertFalse(os.path.exists(temp_path), "Temp download file was not cleaned up!")

    def test_themes_and_contrast_settings(self):
        # Default theme and contrast
        theme = self.prefs.getString("pref_key_theme", "classic")
        contrast = self.prefs.getString("pref_key_contrast", "standard")
        self.assertEqual(theme, "classic")
        self.assertEqual(contrast, "standard")

        # Set custom values
        self.prefs.putString("pref_key_theme", "frosted")
        self.prefs.putString("pref_key_contrast", "high")
        self.assertEqual(self.prefs.getString("pref_key_theme", "classic"), "frosted")
        self.assertEqual(self.prefs.getString("pref_key_contrast", "standard"), "high")

        self.prefs.putString("pref_key_theme", "oled")
        self.prefs.putString("pref_key_contrast", "medium")
        self.assertEqual(self.prefs.getString("pref_key_theme", "classic"), "oled")
        self.assertEqual(self.prefs.getString("pref_key_contrast", "standard"), "medium")

    def test_frosted_glass_blur_radius(self):
        # Retrieve default or fallback
        blur_radius = self.prefs.prefs.get("pref_key_blur_radius", 20)
        self.assertEqual(blur_radius, 20)

        # Update and verify
        self.prefs.prefs["pref_key_blur_radius"] = 35
        self.assertEqual(self.prefs.prefs.get("pref_key_blur_radius", 20), 35)

if __name__ == "__main__":
    unittest.main()
