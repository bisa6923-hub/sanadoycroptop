package com.sanadoy.croptop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.canhub.cropper.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private val MODEL = "claude-sonnet-5-5"
    private lateinit var etKey: EditText
    private lateinit var ivPreview: ImageView
    private lateinit var tvAnswer: TextView
    private lateinit var progress: ProgressBar

    private val cropLauncher = registerForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            val uri = result.uriContent ?: return@registerForActivityResult
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: return@registerForActivityResult
            ivPreview.setImageBitmap(bmp)
            answer(bmp)
        } else {
            tvAnswer.text = "Crop dibatalkan."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        etKey = findViewById(R.id.etKey)
        ivPreview = findViewById(R.id.ivPreview)
        tvAnswer = findViewById(R.id.tvAnswer)
        progress = findViewById(R.id.progress)

        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        etKey.setText(prefs.getString("key", ""))

        findViewById<Button>(R.id.btnCrop).setOnClickListener {
            val key = etKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Isi API key dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("key", key).apply()
            cropLauncher.launch(
                CropImageContractOptions(
                    uri = null,
                    cropImageOptions = CropImageOptions(
                        imageSourceIncludeGallery = true,
                        imageSourceIncludeCamera = true,
                        guidelines = CropImageView.Guidelines.ON
                    )
                )
            )
        }
    }

    private fun answer(bmp: Bitmap) {
        progress.visibility = View.VISIBLE
        tvAnswer.text = "Sedang menjawab..."
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try { ask(bmp, etKey.text.toString().trim()) } catch (e: Exception) { "Error: ${e.message}" }
            }
            progress.visibility = View.GONE
            tvAnswer.text = text
        }
    }

    private fun ask(bmp: Bitmap, key: String): String {
        val maxSide = 1568f
        val scale = minOf(1f, maxSide / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f)
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, bos)
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)

        val body = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 1500)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray()
                    .put(JSONObject().apply {
                        put("type", "image")
                        put("source", JSONObject().apply {
                            put("type", "base64"); put("media_type", "image/jpeg"); put("data", b64)
                        })
                    })
                    .put(JSONObject().apply {
                        put("type", "text")
                        put("text", "Jawab soal pada gambar ini. Tulis JAWABAN AKHIR di baris pertama, lalu penjelasan singkat langkah-langkahnya. Gunakan Bahasa Indonesia.")
                    }))
            }))
        }

        val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", key)
            setRequestProperty("anthropic-version", "2023-06-01")
            connectTimeout = 30000; readTimeout = 90000
            doOutput = true
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val resp = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()
        if (code !in 200..299) return "Error $code: $resp"
        val arr = JSONObject(resp).getJSONArray("content")
        return buildString { for (i in 0 until arr.length()) append(arr.getJSONObject(i).optString("text")) }
    }
}
