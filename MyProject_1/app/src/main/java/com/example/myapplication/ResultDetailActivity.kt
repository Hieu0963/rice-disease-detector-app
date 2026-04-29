package com.example.myapplication

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.Locale

class ResultDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result_detail)

        val diseaseId = intent.getStringExtra("DISEASE_NAME")
        val confidence = intent.getFloatExtra("CONFIDENCE", 0f)
        val translatedDiseaseName = getTranslatedDiseaseName(diseaseId)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarDetail)
        toolbar.title = translatedDiseaseName
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val tvDiseaseName = findViewById<TextView>(R.id.tvDetailDiseaseName)
        val tvConfidence = findViewById<TextView>(R.id.tvDetailConfidence)
        val tvSymptoms = findViewById<TextView>(R.id.tvDetailSymptoms)
        val tvWarning = findViewById<TextView>(R.id.tvDetailWarning)
        val tvAction = findViewById<TextView>(R.id.tvDetailAction)
        val tvNotes = findViewById<TextView>(R.id.tvDetailNotes)

        tvDiseaseName.text = translatedDiseaseName
        tvConfidence.text = "${getString(R.string.label_confidence)}: ${String.format(Locale.getDefault(), "%.2f", confidence)}%"

        if (diseaseId != null) {
            fetchDiseaseDetails(diseaseId, tvSymptoms, tvWarning, tvAction, tvNotes)
        }
    }

    private fun getTranslatedDiseaseName(diseaseId: String?): String {
        if (diseaseId == null) return getString(R.string.unknown_result)
        val formattedId = diseaseId.lowercase(Locale.ROOT).replace(" ", "_")
        val resourceId = resources.getIdentifier("disease_$formattedId", "string", packageName)
        return if (resourceId != 0) getString(resourceId) else diseaseId
    }

    private fun fetchDiseaseDetails(
        diseaseId: String,
        tvSymptoms: TextView, tvWarning: TextView, tvAction: TextView, tvNotes: TextView
    ) {
        val currentLang = Locale.getDefault().language

        // 1. Thử lấy từ Offline (đã sync)
        val localData = getDetailsFromInternalStorage(diseaseId)
        
        if (localData != null) {
            // Hiển thị dữ liệu offline, nếu thiếu trường nào thì lấy từ assets bù vào
            displayDataWithFallback(localData, diseaseId, currentLang, tvSymptoms, tvWarning, tvAction, tvNotes)
        } else {
            // 2. Nếu không có offline, tải từ Firebase
            val db = FirebaseFirestore.getInstance()
            db.collection("disease_details").document(diseaseId).get()
                .addOnSuccessListener { document ->
                    if (document != null && document.exists()) {
                        // Fix lỗi biên dịch: Cast rõ ràng kiểu Map
                        val dataMap = document.data ?: mapOf<String, Any>()
                        val firebaseData = JSONObject(dataMap)
                        
                        // Lấy notes riêng
                        db.collection("disease_details").document("important_notes").get()
                            .addOnSuccessListener { noteDoc ->
                                if (noteDoc != null && noteDoc.exists()) {
                                    val noteMap = noteDoc.data ?: mapOf<String, Any>()
                                    val notesData = JSONObject(noteMap)
                                    firebaseData.put("important_notes", notesData)
                                }
                                displayDataWithFallback(firebaseData, diseaseId, currentLang, tvSymptoms, tvWarning, tvAction, tvNotes)
                            }
                            .addOnFailureListener { 
                                displayDataWithFallback(firebaseData, diseaseId, currentLang, tvSymptoms, tvWarning, tvAction, tvNotes)
                            }
                    } else {
                        // Fallback hoàn toàn về Assets
                        loadDetailsFromAssets(diseaseId, tvSymptoms, tvWarning, tvAction, tvNotes)
                    }
                }
                .addOnFailureListener {
                    // Mất mạng -> Assets
                    loadDetailsFromAssets(diseaseId, tvSymptoms, tvWarning, tvAction, tvNotes)
                }
        }
    }

    private fun displayDataWithFallback(
        mainData: JSONObject,
        diseaseId: String,
        lang: String,
        tvSymptoms: TextView, tvWarning: TextView, tvAction: TextView, tvNotes: TextView
    ) {
        // Lấy dữ liệu từ nguồn chính (Firebase/Offline)
        // Check cả key đúng "symptoms" và key bị sai chính tả trên Firebase "symtoms"
        var symptoms = mainData.optString("symptoms_$lang")
        if (symptoms.isEmpty()) {
            symptoms = mainData.optString("symtoms_$lang")
        }

        var warning = mainData.optString("warning_$lang")
        var action = mainData.optString("action_$lang")
        
        // Lấy notes từ object lồng nhau hoặc field phẳng (tuỳ cấu trúc lưu)
        var notes = ""
        if (mainData.has("important_notes")) {
            val noteObj = mainData.optJSONObject("important_notes")
            if (noteObj != null) {
                notes = noteObj.optString("notes_$lang")
            }
        }

        // Nếu dữ liệu chính bị thiếu, tải Assets để bù vào
        if (symptoms.isEmpty() || warning.isEmpty() || action.isEmpty() || notes.isEmpty()) {
            val assetsData = getAssetsData(diseaseId)
            if (assetsData != null) {
                if (symptoms.isEmpty()) symptoms = assetsData.optString("symptoms_$lang")
                if (warning.isEmpty()) warning = assetsData.optString("warning_$lang")
                if (action.isEmpty()) action = assetsData.optString("action_$lang")
                
                if (notes.isEmpty() && assetsData.has("important_notes")) {
                    notes = assetsData.optJSONObject("important_notes")?.optString("notes_$lang") ?: ""
                }
            }
        }

        tvSymptoms.text = symptoms
        tvWarning.text = warning
        tvAction.text = action
        tvNotes.text = notes
    }

    private fun getDetailsFromInternalStorage(diseaseId: String): JSONObject? {
        try {
            val file = HistoryManager.getLocalDiseaseDetailsFile(this)
            if (file != null) {
                val jsonString = file.readText(Charset.forName("UTF-8"))
                val fullJson = JSONObject(jsonString)
                
                var key = diseaseId
                if (!fullJson.has(key)) key = diseaseId.replace(" ", "")

                if (fullJson.has(key)) {
                    val data = fullJson.getJSONObject(key)
                    // Thử lấy thêm important_notes từ root nếu có
                    if (fullJson.has("important_notes")) {
                        data.put("important_notes", fullJson.getJSONObject("important_notes"))
                    }
                    return data
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun getAssetsData(diseaseId: String): JSONObject? {
        try {
            val jsonString = assets.open("disease_details.json").bufferedReader(Charset.forName("UTF-8")).use { it.readText() }
            val fullJson = JSONObject(jsonString)
            
            var key = diseaseId
            if (!fullJson.has(key)) key = diseaseId.replace(" ", "")

            if (fullJson.has(key)) {
                val data = fullJson.getJSONObject(key)
                if (fullJson.has("important_notes")) {
                    data.put("important_notes", fullJson.getJSONObject("important_notes"))
                }
                return data
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun loadDetailsFromAssets(
        diseaseId: String, 
        tvSymptoms: TextView, tvWarning: TextView, tvAction: TextView, tvNotes: TextView
    ) {
        val currentLang = Locale.getDefault().language
        val data = getAssetsData(diseaseId)
        if (data != null) {
            displayDataWithFallback(data, diseaseId, currentLang, tvSymptoms, tvWarning, tvAction, tvNotes)
        }
    }
}
