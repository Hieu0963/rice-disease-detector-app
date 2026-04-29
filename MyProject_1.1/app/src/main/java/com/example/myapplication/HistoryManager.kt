package com.example.myapplication

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

object HistoryManager {

    private const val PREF_NAME = "rice_doctor_history"
    private const val KEY_HISTORY_DATA = "history_json"
    private const val KEY_DELETED_IDS = "deleted_ids_json"

    private val db = FirebaseFirestore.getInstance()
    private const val COLLECTION_NAME = "history"
    private const val DETAILS_COLLECTION = "disease_details"
    private const val LOCAL_DETAILS_FILE = "disease_details_offline.json"

    // Triệt để: Dùng ConcurrentHashMap để làm cơ chế khóa, an toàn cho đa luồng.
    private val pushingInProgress = ConcurrentHashMap.newKeySet<String>()

    fun addRecord(context: Context, diseaseName: String, confidence: Float) {
        val timestamp = Date().time

        val currentList = getLocalHistory(context).toMutableList()

        val newItem = HistoryItem(
            timestamp = timestamp,
            diseaseName = diseaseName,
            confidence = "%.2f".format(confidence), documentId = UUID.randomUUID().toString(),
            isSynced = false
        )

        currentList.add(0, newItem)
        saveLocalHistory(context, currentList)
        syncOfflineRecords(context)
    }

    fun syncOfflineRecords(context: Context) {
        pushUnsyncedItems(context)
        pullNewItemsAndCleanup(context)
    }

    fun deleteItems(context: Context, itemsToDelete: List<HistoryItem>, onComplete: () -> Unit) {
        val currentList = getLocalHistory(context).toMutableList()
        val deletedIds = getDeletedIds(context)
        val idsToRemove = itemsToDelete.mapNotNull { it.documentId }

        currentList.removeAll { it.documentId in idsToRemove }
        saveLocalHistory(context, currentList)

        for (item in itemsToDelete) {
            if (!item.documentId.isNullOrEmpty()) {
                deletedIds.add(item.documentId)
                if (item.isSynced && !isTempId(item.documentId)) {
                    db.collection(COLLECTION_NAME).document(item.documentId).delete()
                }
            }
        }
        saveDeletedIds(context, deletedIds)
        onComplete()
    }

    private fun pushUnsyncedItems(context: Context) {
        // Chỉ lấy những mục chưa được đẩy lên và không đang trong quá trình đẩy.
        val itemsToPush = getLocalHistory(context).filter {
            !it.isSynced && isTempId(it.documentId) && !pushingInProgress.contains(it.documentId)
        }

        if (itemsToPush.isEmpty()) return

        for (item in itemsToPush) {
            val tempId = item.documentId
            pushingInProgress.add(tempId) // Khóa mục này lại, ngăn đẩy trùng lặp.

            val dateObj = Date(item.timestamp)
            val historyMap = hashMapOf(
                "disease" to item.diseaseName,
                "confidence" to (item.confidence.replace(",", ".").toFloatOrNull() ?: 0f),
                "timestamp" to dateObj
            )

            // Triệt để: Đơn giản hóa listener, chỉ dùng addOnSuccessListener của tác vụ add().
            db.collection(COLLECTION_NAME).add(historyMap)
                .addOnSuccessListener { docRef ->
                    // Tác vụ này chỉ chạy MỘT LẦN khi server xác nhận đã ghi thành công.
                    updateItemAfterSync(context, tempId, docRef.id)
                    pushingInProgress.remove(tempId) // Mở khóa
                }
                .addOnFailureListener {
                    // Nếu thất bại, chỉ cần mở khóa để thử lại lần sau.
                    pushingInProgress.remove(tempId)
                }
        }
    }

    private fun pullNewItemsAndCleanup(context: Context) {
        db.collection(COLLECTION_NAME).orderBy("timestamp", Query.Direction.DESCENDING).get()
            .addOnSuccessListener { documents ->
                if (documents.metadata.hasPendingWrites()) {
                    return@addOnSuccessListener
                }

                val localList = getLocalHistory(context).toMutableList()
                val deletedIds = getDeletedIds(context)

                var hasChanges = false

                for (doc in documents) {
                    val firestoreId = doc.id
                    if (deletedIds.contains(firestoreId) || localList.any { it.documentId == firestoreId }) {
                        continue
                    }

                    val disease = doc.getString("disease") ?: ""
                    val confidenceVal = doc.getDouble("confidence")?.toFloat() ?: 0f
                    val firestoreTimestamp = doc.getTimestamp("timestamp")?.toDate()?.time ?: 0L

                    // Logic chống trùng lặp đáng tin cậy.
                    val TIME_TOLERANCE_MS = 2000L // 2 giây sai số
                    val isPotentialDuplicate = localList.any {
                        !it.isSynced &&
                        isTempId(it.documentId) &&
                        it.diseaseName == disease &&
                        abs(it.timestamp - firestoreTimestamp) < TIME_TOLERANCE_MS
                    }

                    if (isPotentialDuplicate) {
                        continue // Tuyệt đối không thêm. Để cho tiến trình Push tự xử lý.
                    }

                    localList.add(
                        HistoryItem(
                            timestamp = firestoreTimestamp,
                            diseaseName = disease,
                            confidence = "%.2f".format(confidenceVal),
                            documentId = firestoreId,
                            isSynced = true
                        )
                    )
                    hasChanges = true
                }

                if (hasChanges) {
                    localList.sortByDescending { it.timestamp }
                    saveLocalHistory(context, localList)
                }
            }
    }

    fun getHistoryList(context: Context, onComplete: (List<HistoryItem>) -> Unit) {
        onComplete(getLocalHistory(context))
    }

    private fun getLocalHistory(context: Context): List<HistoryItem> {
        val jsonString = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_HISTORY_DATA, "[]")
        val result = ArrayList<HistoryItem>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                result.add(HistoryItem(
                    obj.getLong("timestamp"),
                    obj.getString("diseaseName"),
                    obj.getString("confidence"),
                    obj.getString("documentId"),
                    obj.getBoolean("isSynced")
                ))
            }
        } catch (_: Exception) {}
        return result
    }

    private fun saveLocalHistory(context: Context, list: List<HistoryItem>) {
        val jsonArray = JSONArray()
        list.forEach { item ->
            val obj = JSONObject()
            obj.put("timestamp", item.timestamp)
            obj.put("diseaseName", item.diseaseName)
            obj.put("confidence", item.confidence)
            obj.put("documentId", item.documentId)
            obj.put("isSynced", item.isSynced)
            jsonArray.put(obj)
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(KEY_HISTORY_DATA, jsonArray.toString()).commit()
    }

    private fun updateItemAfterSync(context: Context, tempId: String?, newFirebaseId: String) {
        val list = getLocalHistory(context).toMutableList()
        val index = list.indexOfFirst { it.documentId == tempId }
        if (index != -1) {
            if (list[index].isSynced) return
            list[index] = list[index].copy(documentId = newFirebaseId, isSynced = true)
            saveLocalHistory(context, list)
        }
    }

    private fun getDeletedIds(context: Context): MutableSet<String> {
        val jsonString = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_DELETED_IDS, "[]")
        val set = mutableSetOf<String>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) { set.add(jsonArray.getString(i)) }
        } catch (_: Exception) {}
        return set
    }

    private fun saveDeletedIds(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(KEY_DELETED_IDS, JSONArray(ids).toString()).commit()
    }

    private fun isTempId(id: String?): Boolean = id?.contains("-") == true

    fun syncDiseaseDetails(context: Context) {
        db.collection(DETAILS_COLLECTION).get().addOnSuccessListener { documents ->
            if (!documents.isEmpty) {
                val fullJson = JSONObject()
                for (doc in documents) { fullJson.put(doc.id, JSONObject(doc.data)) }
                try {
                    context.openFileOutput(LOCAL_DETAILS_FILE, Context.MODE_PRIVATE).use { it.write(fullJson.toString().toByteArray()) }
                } catch (_: Exception) {}
            }
        }
    }
    fun getLocalDiseaseDetailsFile(context: Context): File? {
        val file = File(context.filesDir, LOCAL_DETAILS_FILE)
        return if (file.exists()) file else null
    }
}