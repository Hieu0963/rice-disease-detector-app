package com.example.myapplication

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

object HistoryManager {

    private const val TAG = "HistoryManager"
    private const val PREF_NAME = "rice_doctor_history"
    private const val KEY_HISTORY_DATA = "history_json"
    private const val KEY_DELETED_IDS = "deleted_ids_json"

    private val db = FirebaseFirestore.getInstance()
    private const val COLLECTION_NAME = "history"
    private const val DETAILS_COLLECTION = "disease_details"
    private const val LOCAL_DETAILS_FILE = "disease_details_offline.json"

    private val pushingInProgress = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    fun addRecord(context: Context, diseaseName: String, confidence: Float) {
        val timestamp = Date().time

        val currentList = getLocalHistory(context).toMutableList()

        val newItem = HistoryItem(
            timestamp = timestamp,
            diseaseName = diseaseName,
            confidence = "%.2f".format(Locale.US, confidence), // Sử dụng Locale.US để đảm bảo dấu chấm thập phân
            documentId = UUID.randomUUID().toString(),
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

    @Synchronized
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
                        .addOnSuccessListener {
                            Log.d(TAG, "Successfully deleted item from Firestore: ${item.documentId}")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to delete item from Firestore: ${item.documentId}", e)
                        }
                }
            }
        }
        saveDeletedIds(context, deletedIds)
        onComplete()
    }

    private fun pushUnsyncedItems(context: Context) {
        val itemsToPush = getLocalHistory(context).filter {
            !it.isSynced && !pushingInProgress.contains(it.documentId)
        }

        if (itemsToPush.isEmpty()) {
            return
        }

        Log.d(TAG, "Attempting to push ${itemsToPush.size} unsynced items.")

        for (item in itemsToPush) {
            val tempId = item.documentId
            pushingInProgress.add(tempId)
            
            val confidenceValue = item.confidence.toFloatOrNull()
            if (confidenceValue == null) {
                Log.e(TAG, "Failed to parse confidence string: ${item.confidence} for tempId: $tempId")
                pushingInProgress.remove(tempId)
                continue
            }

            val historyMap = hashMapOf(
                "disease" to item.diseaseName,
                "confidence" to confidenceValue,
                "timestamp" to Date(item.timestamp)
            )

            db.collection(COLLECTION_NAME).add(historyMap)
                .addOnSuccessListener { docRef ->
                    Log.d(TAG, "Successfully pushed record with tempId: $tempId, new ID: ${docRef.id}")
                    updateItemAfterSync(context, tempId, docRef.id)
                    pushingInProgress.remove(tempId)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to push record with tempId: $tempId. Error: ${e.message}", e)
                    pushingInProgress.remove(tempId)
                }
        }
    }

    private fun pullNewItemsAndCleanup(context: Context) {
        db.collection(COLLECTION_NAME).orderBy("timestamp", Query.Direction.DESCENDING).get()
            .addOnSuccessListener { documents ->
                if (documents.metadata.hasPendingWrites()) {
                    Log.d(TAG, "Firestore has pending writes, skipping pull to avoid conflicts.")
                    return@addOnSuccessListener
                }

                synchronized(this) {
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

                        val TIME_TOLERANCE_MS = 2000L
                        val isPotentialDuplicate = localList.any {
                            !it.isSynced &&
                            isTempId(it.documentId) &&
                            it.diseaseName == disease &&
                            abs(it.timestamp - firestoreTimestamp) < TIME_TOLERANCE_MS
                        }

                        if (isPotentialDuplicate) {
                            continue
                        }

                        localList.add(
                            HistoryItem(
                                timestamp = firestoreTimestamp,
                                diseaseName = disease,
                                confidence = "%.2f".format(Locale.US, confidenceVal), // Sử dụng Locale.US
                                documentId = firestoreId,
                                isSynced = true
                            )
                        )
                        hasChanges = true
                    }

                    if (hasChanges) {
                        Log.d(TAG, "Pulled new items from Firestore, saving updated local history.")
                        localList.sortByDescending { it.timestamp }
                        saveLocalHistory(context, localList)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to pull new items from Firestore. Error: ${e.message}", e)
            }
    }

    fun getHistoryList(context: Context, onComplete: (List<HistoryItem>) -> Unit) {
        onComplete(getLocalHistory(context))
    }

    private fun getLocalHistory(context: Context): List<HistoryItem> {
        val jsonString = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_HISTORY_DATA, "[]")
        val result = ArrayList<HistoryItem>()
        if (jsonString.isNullOrEmpty() || jsonString == "[]") return result

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
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing local history JSON", e)
        }
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
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(KEY_HISTORY_DATA, jsonArray.toString()).apply()
    }

    @Synchronized
    private fun updateItemAfterSync(context: Context, tempId: String?, newFirebaseId: String) {
        if (tempId == null) {
            Log.w(TAG, "updateItemAfterSync called with null tempId for newFirebaseId: $newFirebaseId")
            return
        }

        Log.d(TAG, "Attempting to update local item with tempId: $tempId to new Firebase ID: $newFirebaseId")
        val list = getLocalHistory(context).toMutableList()
        val index = list.indexOfFirst { it.documentId == tempId }

        if (index != -1) {
            val oldItem = list[index]
            Log.d(TAG, "Found item to update. Old ID: ${oldItem.documentId}, Old isSynced: ${oldItem.isSynced}")

            if (oldItem.isSynced) {
                Log.w(TAG, "Item with tempId $tempId was already marked as synced, but proceeding to update its ID to $newFirebaseId to ensure consistency.")
            }

            list[index] = oldItem.copy(documentId = newFirebaseId, isSynced = true)
            saveLocalHistory(context, list)
            Log.i(TAG, "Successfully updated local item from tempId $tempId to new ID: $newFirebaseId")
        } else {
            Log.w(TAG, "Could not find local item with tempId: $tempId to update after sync. It might have been deleted locally.")
        }
    }

    private fun getDeletedIds(context: Context): MutableSet<String> {
        val jsonString = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_DELETED_IDS, "[]")
        val set = mutableSetOf<String>()
        if (jsonString.isNullOrEmpty() || jsonString == "[]") return set

        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) { set.add(jsonArray.getString(i)) }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing deleted IDs JSON", e)
        }
        return set
    }

    private fun saveDeletedIds(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(KEY_DELETED_IDS, JSONArray(ids).toString()).apply()
    }

    private fun isTempId(id: String?): Boolean = id?.contains("-") == true

    fun syncDiseaseDetails(context: Context) {
        db.collection(DETAILS_COLLECTION).get()
            .addOnSuccessListener { documents ->
                if (!documents.isEmpty) {
                    val fullJson = JSONObject()
                    for (doc in documents) {
                        fullJson.put(doc.id, JSONObject(doc.data as Map<*, *>))
                    }
                    try {
                        context.openFileOutput(LOCAL_DETAILS_FILE, Context.MODE_PRIVATE).use {
                            it.write(fullJson.toString().toByteArray())
                        }
                        Log.d(TAG, "Successfully synced and saved disease details locally.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error writing local disease details file", e)
                    }
                } else {
                    Log.d(TAG, "No disease details found in Firestore to sync.")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to sync disease details from Firestore", e)
            }
    }

    fun getLocalDiseaseDetailsFile(context: Context): File? {
        val file = File(context.filesDir, LOCAL_DETAILS_FILE)
        return if (file.exists()) file else null
    }
}
