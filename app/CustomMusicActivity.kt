package com.ideawav.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class CustomMusicActivity : AppCompatActivity() {

    private lateinit var soundManager: SoundManager
    private var selectedUri: Uri? = null

    private lateinit var btnPickFile: Button
    private lateinit var tvSelectedFile: TextView
    private lateinit var etUrl: EditText

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // 某些 provider 不支持持久化，忽略
            }
            selectedUri = it
            tvSelectedFile.text = queryDisplayName(it) ?: "未知文件"
            updatePickButton()
            // 已选本地文件，清空网络链接
            etUrl.setText("")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_music)
        soundManager = SoundManager.getInstance(this)

        btnPickFile = findViewById(R.id.btnPickFile)
        tvSelectedFile = findViewById(R.id.tvSelectedFile)
        etUrl = findViewById(R.id.etUrl)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { cancelAndClose() }

        btnPickFile.setOnClickListener {
            pickAudio.launch(arrayOf("audio/*"))
        }

        // 粘贴网络链接时，若已选本地文件则清除本地
        etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!s.isNullOrEmpty() && selectedUri != null) {
                    selectedUri = null
                    tvSelectedFile.text = "未选择文件"
                    updatePickButton()
                }
            }
        })

        findViewById<Button>(R.id.btnConfirm).setOnClickListener { confirm() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { cancelAndClose() }
    }

    private fun updatePickButton() {
        if (selectedUri != null) {
            btnPickFile.text = "已选择音频文件"
            btnPickFile.setBackgroundResource(R.drawable.btn_green)
        } else {
            btnPickFile.text = "选择本地音频文件"
            btnPickFile.setBackgroundResource(R.drawable.btn_primary)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name
    }

    private fun confirm() {
        val urlInput = etUrl.text.toString().trim()
        val isLocal = selectedUri != null
        val uriString: String? = when {
            isLocal -> selectedUri.toString()
            urlInput.isNotEmpty() -> if (isValidUrl(urlInput)) urlInput else null
            else -> null
        }

        if (uriString == null) {
            Toast.makeText(this, "请选择本地文件或输入有效的网络链接", Toast.LENGTH_SHORT).show()
            return
        }

        val name = if (isLocal) {
            queryDisplayName(selectedUri!!) ?: "本地音乐"
        } else {
            "网络音乐"
        }

        findViewById<Button>(R.id.btnConfirm).isEnabled = false
        Toast.makeText(this, "正在加载…", Toast.LENGTH_SHORT).show()

        soundManager.loadCustomMusic(uriString) { success ->
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, "加载成功", Toast.LENGTH_SHORT).show()
                    val data = Intent()
                        .putExtra("custom_uri", uriString)
                        .putExtra("custom_name", name)
                    setResult(Activity.RESULT_OK, data)
                    finish()
                } else {
                    findViewById<Button>(R.id.btnConfirm).isEnabled = true
                    Toast.makeText(this, "加载失败，请检查文件或链接", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun isValidUrl(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    private fun cancelAndClose() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }
}
