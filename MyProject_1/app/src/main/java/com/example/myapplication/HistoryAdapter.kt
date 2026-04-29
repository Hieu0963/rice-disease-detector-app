package com.example.myapplication

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private var historyList: List<HistoryItem>,
    private val onItemSelectChanged: (Boolean) -> Unit,
    private val onItemClicked: (HistoryItem) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    class HistoryViewHolder(val itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        val tvDisease: TextView = itemView.findViewById(R.id.tvDisease)
        val tvConfidence: TextView = itemView.findViewById(R.id.tvConfidence)
        val cbSelect: CheckBox = itemView.findViewById(R.id.cbSelect)
        val layoutSyncStatus: LinearLayout? = itemView.findViewById(R.id.layoutSyncStatus)
        val tvSyncStatus: TextView? = itemView.findViewById(R.id.tvSyncStatus)
        val imgSyncStatus: ImageView? = itemView.findViewById(R.id.imgSyncStatus)
        val btnDetail: ImageView? = itemView.findViewById(R.id.btnDetail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val item = historyList[position]
        val context = holder.itemView.context

        // Format Long timestamp to String for display
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val formattedTime = sdf.format(Date(item.timestamp))
        holder.tvTimestamp.text = formattedTime

        val translatedDiseaseName = getTranslatedDiseaseName(context, item.diseaseName)

        val labelDisease = context.getString(R.string.label_disease)
        val labelConfidence = context.getString(R.string.label_confidence)
        
        holder.tvDisease.text = "$labelDisease: $translatedDiseaseName"
        holder.tvConfidence.text = "$labelConfidence: ${item.confidence}%"

        holder.cbSelect.setOnCheckedChangeListener(null)
        holder.cbSelect.isChecked = item.isSelected

        val selectionClickListener = View.OnClickListener {
            val newState = !item.isSelected
            item.isSelected = newState
            holder.cbSelect.isChecked = newState
            onItemSelectChanged(anyItemSelected())
        }

        holder.cbSelect.setOnClickListener(selectionClickListener)
        holder.itemView.setOnClickListener(selectionClickListener)

        holder.btnDetail?.setOnClickListener {
            onItemClicked(item)
        }

        if (holder.layoutSyncStatus != null) {
            if (item.isSynced) {
                holder.tvSyncStatus?.text = context.getString(R.string.status_synced)
                holder.tvSyncStatus?.setTextColor(Color.parseColor("#4CAF50")) // Green
                holder.imgSyncStatus?.setImageResource(android.R.drawable.checkbox_on_background)
                holder.imgSyncStatus?.setColorFilter(Color.parseColor("#4CAF50"))
            } else {
                holder.tvSyncStatus?.text = context.getString(R.string.status_not_synced)
                holder.tvSyncStatus?.setTextColor(Color.parseColor("#FF9800")) // Orange
                holder.imgSyncStatus?.setImageResource(android.R.drawable.ic_popup_sync)
                holder.imgSyncStatus?.setColorFilter(Color.parseColor("#FF9800"))
            }
        }
    }

    private fun getTranslatedDiseaseName(context: Context, diseaseId: String): String {
        val formattedId = diseaseId.lowercase(Locale.ROOT).replace(" ", "_")
        val resourceId = context.resources.getIdentifier("disease_$formattedId", "string", context.packageName)
        return if (resourceId != 0) context.getString(resourceId) else diseaseId
    }

    override fun getItemCount(): Int = historyList.size

    fun updateData(newList: List<HistoryItem>) {
        newList.forEach { it.isSelected = false }
        historyList = newList
        notifyDataSetChanged()
    }

    fun selectAll(isSelected: Boolean) {
        for (item in historyList) {
            item.isSelected = isSelected
        }
        notifyDataSetChanged()
        onItemSelectChanged(anyItemSelected())
    }

    fun getSelectedItems(): List<HistoryItem> {
        return historyList.filter { it.isSelected }
    }

    private fun anyItemSelected(): Boolean {
        return historyList.any { it.isSelected }
    }
}
