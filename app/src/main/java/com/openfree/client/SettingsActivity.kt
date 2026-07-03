package com.openfree.client

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * SettingsActivity
 *
 * Configuration UI for OpenFree. Allows users to:
 *  - Set the local path to a GGML Whisper model (or download one).
 *  - View and clear corrections dictionary mappings.
 *
 * Preferences are persisted in the "openfree_prefs" [SharedPreferences] file
 * and read by [OpenFreeIME] on each keyboard activation.
 */
class SettingsActivity : AppCompatActivity() {

    // ── Views ──────────────────────────────────────────────────────────────────

    private lateinit var editModelPath: EditText
    private lateinit var btnDownload: Button
    private lateinit var btnSave: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var txtDownloadStatus: TextView
    private lateinit var txtDictionaryList: TextView
    private lateinit var btnClearDictionary: Button
    private lateinit var btnToggleFloating: Button
    private lateinit var editDictWrongSettings: EditText
    private lateinit var editDictCorrectSettings: EditText
    private lateinit var btnDictAddSettings: Button

    private lateinit var circularProgressBar: com.google.android.material.progressindicator.CircularProgressIndicator
    private lateinit var switchFloating: com.google.android.material.switchmaterial.SwitchMaterial

    // ── State ──────────────────────────────────────────────────────────────────

    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())
    private var downloadThread: Thread? = null

    private val PERMISSIONS_REQUEST_CODE = 101

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Apply Window Insets for modern Edge-to-Edge compliance
        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        prefs = getSharedPreferences(OpenFreeIME.PREFS_NAME, MODE_PRIVATE)
        migrateModelOutOfCache()

        editModelPath      = findViewById(R.id.edit_model_path)
        btnDownload        = findViewById(R.id.btn_download_model)
        btnSave            = findViewById(R.id.btn_save)
        progressBar        = findViewById(R.id.progress_download)
        txtDownloadStatus  = findViewById(R.id.txt_download_status)
        txtDictionaryList  = findViewById(R.id.txt_dictionary_list)
        btnClearDictionary = findViewById(R.id.btn_clear_dictionary)
        btnToggleFloating  = findViewById(R.id.btn_toggle_floating_service)

        editDictWrongSettings = findViewById(R.id.edit_dict_wrong_settings)
        editDictCorrectSettings = findViewById(R.id.edit_dict_correct_settings)
        btnDictAddSettings = findViewById(R.id.btn_dict_add_settings)

        circularProgressBar = findViewById(R.id.progress_download_circular)
        switchFloating      = findViewById(R.id.switch_floating_service)

        // Restore saved preferences
        editModelPath.setText(prefs.getString(OpenFreeIME.KEY_MODEL_PATH, ""))

        // Restore theme
        val savedTheme = prefs.getString("pref_key_theme", "classic") ?: "classic"
        val themeButtonId = when (savedTheme) {
            "frosted" -> R.id.btn_theme_frosted
            "oled" -> R.id.btn_theme_oled
            else -> R.id.btn_theme_classic
        }
        findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggle_group_theme).check(themeButtonId)

        // Restore contrast
        val savedContrast = prefs.getString("pref_key_contrast", "standard") ?: "standard"
        val contrastButtonId = when (savedContrast) {
            "medium" -> R.id.btn_contrast_medium
            "high" -> R.id.btn_contrast_high
            else -> R.id.btn_contrast_standard
        }
        findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggle_group_contrast).check(contrastButtonId)

        // Restore blur radius
        val savedBlur = prefs.getInt("pref_key_blur_radius", 20)
        findViewById<com.google.android.material.slider.Slider>(R.id.slider_blur_radius).value = savedBlur.toFloat()

        btnSave.setOnClickListener { saveSettings() }
        btnDownload.setOnClickListener { downloadModel() }
        btnClearDictionary.setOnClickListener { clearDictionary() }
        btnDictAddSettings.setOnClickListener {
            val wrong = editDictWrongSettings.text.toString().trim()
            val correct = editDictCorrectSettings.text.toString().trim()
            if (wrong.isNotEmpty() && correct.isNotEmpty()) {
                addDictionaryItem(wrong, correct)
                editDictWrongSettings.setText("")
                editDictCorrectSettings.setText("")
                updateDictionaryDisplay()
                Toast.makeText(this, "Correction added", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter both words", Toast.LENGTH_SHORT).show()
            }
        }
        btnToggleFloating.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        switchFloating.isChecked = isAccessibilityServiceEnabled()
        switchFloating.setOnCheckedChangeListener { _, _ ->
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        updateDictionaryDisplay()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateFloatingServiceButtonState()
        if (::switchFloating.isInitialized) {
            switchFloating.isChecked = isAccessibilityServiceEnabled()
        }
        updateDictionaryDisplay()
    }

    private fun updateFloatingServiceButtonState() {
        if (isAccessibilityServiceEnabled()) {
            btnToggleFloating.text = "Disable Floating Widget (Manage)"
            btnToggleFloating.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.border_focus)
            )
        } else {
            btnToggleFloating.text = "Enable Floating Widget"
            btnToggleFloating.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.primary)
            )
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, FloatingOpenFreeService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSIONS_REQUEST_CODE
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadThread?.interrupt()
    }

    // ── Settings persistence ───────────────────────────────────────────────────

    private fun saveSettings() {
        val modelPath = editModelPath.text.toString().trim()

        val toggleTheme: com.google.android.material.button.MaterialButtonToggleGroup = findViewById(R.id.toggle_group_theme)
        val selectedTheme = when (toggleTheme.checkedButtonId) {
            R.id.btn_theme_frosted -> "frosted"
            R.id.btn_theme_oled -> "oled"
            else -> "classic"
        }

        val toggleContrast: com.google.android.material.button.MaterialButtonToggleGroup = findViewById(R.id.toggle_group_contrast)
        val selectedContrast = when (toggleContrast.checkedButtonId) {
            R.id.btn_contrast_medium -> "medium"
            R.id.btn_contrast_high -> "high"
            else -> "standard"
        }

        val sliderBlur: com.google.android.material.slider.Slider = findViewById(R.id.slider_blur_radius)
        val blurRadius = sliderBlur.value.toInt()

        prefs.edit().apply {
            putString(OpenFreeIME.KEY_MODEL_PATH, modelPath)
            remove("pref_key_remote_fallback_url") // clean up removed remote-fallback setting
            putString("pref_key_theme", selectedTheme)
            putString("pref_key_contrast", selectedContrast)
            putInt("pref_key_blur_radius", blurRadius)
            apply()
        }

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
    }

    private fun clearDictionary() {
        DictionaryStore.clear(prefs)
        updateDictionaryDisplay()
        Toast.makeText(this, "Corrections dictionary cleared", Toast.LENGTH_SHORT).show()
    }

    private fun updateDictionaryDisplay() {
        val mappings = DictionaryStore.getMappings(prefs)
        if (mappings.isEmpty()) {
            txtDictionaryList.text = "No active dictionary corrections."
            return
        }
        txtDictionaryList.text = mappings.entries.joinToString("\n") { "${it.key} → ${it.value}" }
    }

    // ── Model storage ──────────────────────────────────────────────────────────

    /**
     * Models were originally downloaded into [getCacheDir], which Android may
     * purge under storage pressure — silently breaking dictation. Move any
     * previously cached model into [getFilesDir] and repoint the preference.
     */
    private fun migrateModelOutOfCache() {
        val cached = File(cacheDir, MODEL_FILENAME)
        if (!cached.exists()) return

        val target = File(filesDir, MODEL_FILENAME)
        val moved = if (target.exists()) {
            cached.delete() // a copy already lives in filesDir; drop the cache duplicate
            true
        } else {
            cached.renameTo(target)
        }

        if (moved) {
            val savedPath = prefs.getString(OpenFreeIME.KEY_MODEL_PATH, "") ?: ""
            if (savedPath.isBlank() || savedPath == cached.absolutePath) {
                prefs.edit().putString(OpenFreeIME.KEY_MODEL_PATH, target.absolutePath).apply()
            }
        }
    }

    // ── Model downloader ───────────────────────────────────────────────────────

    private fun downloadModel() {
        val outputFile = File(filesDir, MODEL_FILENAME)

        btnDownload.isEnabled = false
        progressBar.progress  = 0
        circularProgressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        circularProgressBar.visibility = View.VISIBLE
        txtDownloadStatus.text = getString(R.string.download_progress)

        downloadThread = Thread {
            try {
                val url        = URL(HF_MODEL_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod  = "GET"
                connection.connectTimeout = 30_000
                connection.readTimeout    = 60_000
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("HTTP ${connection.responseCode}")
                }

                val totalBytes     = connection.contentLength.toLong()
                var downloadedBytes = 0L

                BufferedInputStream(connection.inputStream).use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(8_192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            if (Thread.interrupted()) throw InterruptedException()
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0) {
                                val pct = ((downloadedBytes * 100) / totalBytes).toInt()
                                mainHandler.post { 
                                    progressBar.progress = pct 
                                    circularProgressBar.progress = pct
                                }
                            }
                        }
                    }
                }
                connection.disconnect()

                mainHandler.post {
                    val path = outputFile.absolutePath
                    editModelPath.setText(path)
                    prefs.edit().putString(OpenFreeIME.KEY_MODEL_PATH, path).apply()
                    txtDownloadStatus.text = getString(R.string.download_success)
                    progressBar.visibility  = View.GONE
                    circularProgressBar.visibility = View.GONE
                    btnDownload.isEnabled   = true
                }

            } catch (e: InterruptedException) {
                mainHandler.post {
                    txtDownloadStatus.text = "Download cancelled"
                    progressBar.visibility  = View.GONE
                    circularProgressBar.visibility = View.GONE
                    btnDownload.isEnabled   = true
                }
            } catch (e: Exception) {
                mainHandler.post {
                    txtDownloadStatus.text = "${getString(R.string.download_failed)}: ${e.message}"
                    progressBar.visibility  = View.GONE
                    circularProgressBar.visibility = View.GONE
                    btnDownload.isEnabled   = true
                }
            }
        }.also { it.start() }
    }

    private fun addDictionaryItem(wrong: String, correct: String) {
        DictionaryStore.addMapping(prefs, wrong, correct)
    }

    // ── Constants ──────────────────────────────────────────────────────────────

    companion object {
        private const val HF_MODEL_URL  =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin"
        private const val MODEL_FILENAME = "ggml-base.en-q5_1.bin"
    }
}
