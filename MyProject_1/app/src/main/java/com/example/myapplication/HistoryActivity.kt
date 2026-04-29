package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class HistoryActivity : AppCompatActivity() {

    private lateinit var rvHistory: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnSelectAll: ImageView
    private lateinit var btnDelete: ImageView
    private lateinit var adapter: HistoryAdapter
    private var historyList: MutableList<HistoryItem> = mutableListOf()
    private var isAllSelected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        supportActionBar?.hide()

        val btnBack = findViewById<ImageView>(R.id.btnBack)
        rvHistory = findViewById(R.id.rvHistory)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnDelete = findViewById(R.id.btnDelete)

        setupRecyclerView()

        btnBack.setOnClickListener {
            finish()
        }

        btnSelectAll.setOnClickListener {
            isAllSelected = !isAllSelected
            adapter.selectAll(isAllSelected)
            updateToolbarState()
        }

        btnDelete.setOnClickListener {
            val itemsToDelete = adapter.getSelectedItems()
            if (itemsToDelete.isNotEmpty()) {
                showDeleteConfirmation(itemsToDelete)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Khi quay lại màn hình, luôn đồng bộ và tải lại dữ liệu mới nhất
        HistoryManager.syncOfflineRecords(this)
        loadHistoryData()
    }

    private fun setupRecyclerView() {
        rvHistory.layoutManager = LinearLayoutManager(this)
        adapter = HistoryAdapter(historyList,
            onItemSelectChanged = {
                updateToolbarState()
            },
            onItemClicked = { item ->
                val intent = Intent(this, ResultDetailActivity::class.java).apply {
                    putExtra("DISEASE_NAME", item.diseaseName)

                    val cleanConfidence = item.confidence.replace(",", ".")
                    putExtra("CONFIDENCE", cleanConfidence.toFloatOrNull() ?: 0f)
                }
                startActivity(intent)
            }
        )
        rvHistory.adapter = adapter
    }

    private fun loadHistoryData() {
        // Sửa lại cách gọi hàm cho đúng với HistoryManager mới
        HistoryManager.getHistoryList(this) { list ->
            historyList.clear()
            historyList.addAll(list)

            if (historyList.isNotEmpty()) {
                rvHistory.visibility = View.VISIBLE
                tvEmpty.visibility = View.GONE
                btnSelectAll.visibility = View.VISIBLE
                adapter.updateData(historyList)
            } else {
                rvHistory.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
                btnSelectAll.visibility = View.GONE
                btnDelete.visibility = View.GONE
            }
            isAllSelected = false
            updateToolbarState()
        }
    }

    private fun updateToolbarState() {
        val selectedCount = adapter.getSelectedItems().size
        btnDelete.visibility = if (selectedCount > 0) View.VISIBLE else View.GONE

        if (historyList.isNotEmpty()) {
            btnSelectAll.visibility = View.VISIBLE
            isAllSelected = selectedCount == historyList.size
            if (isAllSelected) {
                btnSelectAll.setImageResource(R.drawable.ic_custom_checkbox_checked) // Cập nhật icon cho đúng
            } else {
                btnSelectAll.setImageResource(R.drawable.ic_custom_checkbox_unchecked)
            }
        } else {
            // Khi danh sách trống, ẩn cả 2 nút
            btnSelectAll.visibility = View.GONE
            btnDelete.visibility = View.GONE
        }
    }

    private fun showDeleteConfirmation(itemsToDelete: List<HistoryItem>) {
        val title = getString(R.string.delete_title)
        val message = getString(R.string.delete_message, itemsToDelete.size)
        val confirm = getString(R.string.delete_confirm)
        val cancel = getString(R.string.delete_cancel)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(confirm) { _, _ ->
                HistoryManager.deleteItems(this, itemsToDelete) {
                    // Sau khi xóa, tải lại dữ liệu để cập nhật giao diện
                    loadHistoryData()
                }
            }
            .setNegativeButton(cancel, null)
            .show()
    }
}
