package com.example.myapplication

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.persistentCacheSettings
import com.google.firebase.initialize

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Quan trọng: Khởi tạo Firebase trước khi sử dụng bất kỳ dịch vụ nào
        Firebase.initialize(this)

        // Kích hoạt bộ nhớ đệm của Firestore (cách làm mới)
        val firestore = FirebaseFirestore.getInstance()
        firestore.firestoreSettings = firestoreSettings {
            setLocalCacheSettings(persistentCacheSettings {})
        }

        // Đồng bộ hóa các bản ghi chưa được lưu khi ứng dụng khởi động
        HistoryManager.syncOfflineRecords(this)
    }
}
