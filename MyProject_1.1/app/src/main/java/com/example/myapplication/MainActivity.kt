package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import com.example.myapplication.databinding.ActivityMainBinding
import com.example.myapplication.ml.ModelFp32
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedBitmap: Bitmap? = null
    private var latestTmpUri: Uri? = null

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { isSuccess ->
            if (isSuccess) {
                latestTmpUri?.let { uri ->
                    val bm = loadBitmapFromUri(uri)
                    setSelectedImage(bm)
                }
            }
        }

    private val pickFromGalleryLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            uri?.let {
                val bm = loadBitmapFromUri(it)
                setSelectedImage(bm)
            }
        }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) launchCamera()
            else Toast.makeText(this, "Bạn cần cấp quyền Camera để sử dụng tính năng này", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.title = ""

        binding.btnPick.setOnClickListener { showImageSourceDialog() }

        binding.btnPredict.setOnClickListener {
            val bm = selectedBitmap
            if (bm == null) {
                binding.textView.text = getString(R.string.select_image_before_predicting)
                binding.btnViewDetail.visibility = View.GONE
            } else {
                binding.textView.text = getString(R.string.processing_image)
                binding.btnViewDetail.visibility = View.GONE
                classifyImage(bm)
            }
        }

        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        binding.btnInfo.setOnClickListener { showInfoDialog() }

        // Gọi đồng bộ khi mở app
        HistoryManager.syncOfflineRecords(this)
    }

    override fun onResume() {
        super.onResume()
        // Gọi đồng bộ mỗi khi app quay lại foreground (phòng trường hợp vừa bật mạng lại)
        HistoryManager.syncOfflineRecords(this)
    }

    private fun showImageSourceDialog() {
        val options = arrayOf(getString(R.string.source_camera), getString(R.string.source_gallery))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.source_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> checkCameraPermissionAndLaunch()
                    1 -> launchGalleryPicker()
                }
            }
            .show()
    }

    private fun checkCameraPermissionAndLaunch() {
        when (PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) -> launchCamera()
            else -> requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        getTmpFileUri().let {
            latestTmpUri = it
            takePictureLauncher.launch(it)
        }
    }

    private fun getTmpFileUri(): Uri {
        val tmpFile = File.createTempFile("tmp_image_file", ".png", cacheDir).apply {
            createNewFile()
            deleteOnExit()
        }
        return FileProvider.getUriForFile(
            applicationContext,
            "${applicationContext.packageName}.provider",
            tmpFile
        )
    }

    private fun launchGalleryPicker() {
        pickFromGalleryLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun setSelectedImage(bm: Bitmap) {
        selectedBitmap = bm
        binding.imageView.setImageBitmap(bm)
        binding.textView.text = getString(R.string.image_selected_ready)
        binding.btnViewDetail.visibility = View.GONE
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_language -> {
                showLanguageDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showLanguageDialog() {
        val languages = arrayOf(getString(R.string.language_vi), getString(R.string.language_en))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.change_language))
            .setItems(languages) { _, which ->
                when (which) {
                    0 -> setLocale("vi")
                    1 -> setLocale("en")
                }
            }
            .show()
    }

    private fun setLocale(languageCode: String) {
        val appLocale = LocaleListCompat.forLanguageTags(languageCode)
        AppCompatDelegate.setApplicationLocales(appLocale)
    }

    private fun showInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_info_title))
            .setMessage(getString(R.string.app_info_message))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(contentResolver, uri)
        }.copy(Bitmap.Config.ARGB_8888, true)
    }

    private fun getTranslatedDiseaseName(diseaseId: String?): String {
        if (diseaseId.isNullOrBlank()) return getString(R.string.unknown_result)
        // Chuyển dấu cách thành dấu gạch dưới để khớp với resource ID
        val formattedId = diseaseId.lowercase(Locale.ROOT).replace(" ", "_")
        val key = "disease_$formattedId"
        val resId = resources.getIdentifier(key, "string", packageName)
        return if (resId != 0) getString(resId) else diseaseId
    }

    private fun loadLabels(): List<String> {
        return assets.open("labels.txt").bufferedReader()
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun classifyImage(bitmap: Bitmap) {
        var model: ModelFp32? = null
        try {
            val imageSize = 224
            model = ModelFp32.newInstance(this)

            // Trình xử lý hình ảnh cho mô hình float32
            val imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(imageSize, imageSize, ResizeOp.ResizeMethod.BILINEAR))
                // Rescale to [0, 1]
                .add(NormalizeOp(0.0f, 255.0f))
                .build()

            // Tải và xử lý hình ảnh
            var tensorImage = TensorImage(DataType.FLOAT32)
            tensorImage.load(bitmap)
            tensorImage = imageProcessor.process(tensorImage)

            val inputFeature0 = tensorImage.tensorBuffer

            // Chạy suy luận
            val startTime = System.currentTimeMillis()
            val outputs = model.process(inputFeature0)
            val endTime = System.currentTimeMillis()
            val inferenceTime = endTime - startTime

            val outputFeature0 = outputs.outputFeature0AsTensorBuffer
            val predictions = outputFeature0.floatArray

            val maxIdx = predictions.indices.maxByOrNull { predictions[it] } ?: -1
            if (maxIdx == -1) {
                binding.textView.text = getString(R.string.no_object_detected)
                binding.btnViewDetail.visibility = View.GONE
                return
            }

            val labels = loadLabels()
            if (predictions.size != labels.size) {
                binding.textView.text = getString(
                    R.string.model_processing_error,
                    "Output=${predictions.size} != labels=${labels.size}"
                )
                binding.btnViewDetail.visibility = View.GONE
                return
            }

            val confidence = predictions[maxIdx] * 100f
            val diseaseId = labels[maxIdx]
            val translatedDiseaseName = getTranslatedDiseaseName(diseaseId)

            val confidenceLevel = when {
                confidence >= 85f -> getString(R.string.conf_high)
                confidence >= 65f -> getString(R.string.conf_medium)
                else -> getString(R.string.conf_low)
            }

            // Tạo chuỗi kết quả
            var resultText = getString(
                R.string.result_format,
                translatedDiseaseName,
                "%.2f".format(confidence),
                confidenceLevel,
                inferenceTime.toString()
            )

            // Thêm cảnh báo nếu độ tin cậy thấp
            if (confidence < 65f) {
                resultText += "\n\n" + getString(R.string.warning_low_confidence)
            } else if (confidence >= 65f && confidence < 85f) {
                resultText += "\n" + getString(R.string.recommendation_medium_confidence)
            }

            binding.textView.text = resultText

            HistoryManager.addRecord(this, diseaseId, confidence)

            binding.btnViewDetail.visibility = View.VISIBLE
            binding.btnViewDetail.setOnClickListener {
                val intent = Intent(this, ResultDetailActivity::class.java).apply {
                    putExtra("DISEASE_NAME", diseaseId)
                    putExtra("CONFIDENCE", confidence)
                }
                startActivity(intent)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            binding.textView.text = getString(R.string.model_processing_error, e.message)
            binding.btnViewDetail.visibility = View.GONE
        } finally {
            try { model?.close() } catch (_: Exception) {}
        }
    }
}
