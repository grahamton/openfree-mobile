package com.openfree.client

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 *  - Configure an optional remote fallback transcription endpoint.
 *  - View and clear corrections dictionary mappings.
 *
 * Preferences are persisted in the "openfree_prefs" [SharedPreferences] file
 * and read by [OpenFreeIME] on each keyboard activation.
 */
class SettingsActivity : AppCompatActivity() {

    // ── Views ──────────────────────────────────────────────────────────────────

    private lateinit var editModelPath: EditText
    private lateinit var editFallbackUrl: EditText
    private lateinit var btnDownload: Button
    private lateinit var btnSave: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var txtDownloadStatus: TextView
    private lateinit var txtDictionaryList: TextView
    private lateinit var btnClearDictionary: Button

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

        editModelPath      = findViewById(R.id.edit_model_path)
        editFallbackUrl    = findViewById(R.id.edit_fallback_url)
        btnDownload        = findViewById(R.id.btn_download_model)
        btnSave            = findViewById(R.id.btn_save)
        progressBar        = findViewById(R.id.progress_download)
        txtDownloadStatus  = findViewById(R.id.txt_download_status)
        txtDictionaryList  = findViewById(R.id.txt_dictionary_list)
        btnClearDictionary = findViewById(R.id.btn_clear_dictionary)

        // Restore saved preferences
        editModelPath.setText(prefs.getString(OpenFreeIME.KEY_MODEL_PATH, ""))
        editFallbackUrl.setText(prefs.getString(OpenFreeIME.KEY_REMOTE_FALLBACK_URL, ""))

        btnSave.setOnClickListener { saveSettings() }
        btnDownload.setOnClickListener { downloadModel() }
        btnClearDictionary.setOnClickListener { clearDictionary() }

        updateDictionaryDisplay()
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        // ACCESS_LOCAL_NETWORK is a string literal to compile on older target SDKs, but runs on Android 17 (API 37)+
        val localNetworkPermission = "android.permission.ACCESS_LOCAL_NETWORK"
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            if (ContextCompat.checkSelfPermission(this, localNetworkPermission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(localNetworkPermission)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
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
        val modelPath   = editModelPath.text.toString().trim()
        val fallbackUrl = editFallbackUrl.text.toString().trim()

        prefs.edit().apply {
            putString(OpenFreeIME.KEY_MODEL_PATH, modelPath)
            putString(OpenFreeIME.KEY_REMOTE_FALLBACK_URL, fallbackUrl)
            apply()
        }

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
    }

    private fun clearDictionary() {
        prefs.edit().remove(OpenFreeIME.KEY_DICTIONARY_MAPPINGS).apply()
        updateDictionaryDisplay()
        Toast.makeText(this, "Corrections dictionary cleared", Toast.LENGTH_SHORT).show()
    }

    private fun updateDictionaryDisplay() {
        val raw = prefs.getString(OpenFreeIME.KEY_DICTIONARY_MAPPINGS, "") ?: ""
        if (raw.isBlank()) {
            txtDictionaryList.text = "No active dictionary corrections."
            return
        }
        val sb = java.lang.StringBuilder()
        raw.split(";").forEach {
            val parts = it.split("->")
            if (parts.size == 2) {
                sb.append("${parts[0]} → ${parts[1]}\n")
            }
        }
        txtDictionaryList.text = sb.toString().trim()
    }

    // ── Model downloader ───────────────────────────────────────────────────────

    private fun downloadModel() {
        val outputFile = File(cacheDir, MODEL_FILENAME)

        btnDownload.isEnabled = false
        progressBar.progress  = 0
        progressBar.visibility = View.VISIBLE
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
                                mainHandler.post { progressBar.progress = pct }
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
                    btnDownload.isEnabled   = true
                }

            } catch (e: InterruptedException) {
                mainHandler.post {
                    txtDownloadStatus.text = "Download cancelled"
                    progressBar.visibility  = View.GONE
                    btnDownload.isEnabled   = true
                }
            } catch (e: Exception) {
                mainHandler.post {
                    txtDownloadStatus.text = "${getString(R.string.download_failed)}: ${e.message}"
                    progressBar.visibility  = View.GONE
                    btnDownload.isEnabled   = true
                }
            }
        }.also { it.start() }
    }

    // ── Constants ──────────────────────────────────────────────────────────────

    companion object {
        private const val HF_MODEL_URL  =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin"
        private const val MODEL_FILENAME = "ggml-base.en-q5_1.bin"
    }
}
