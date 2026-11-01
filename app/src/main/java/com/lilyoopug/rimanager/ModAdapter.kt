package com.lilyoopug.rimanager

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lilyoopug.rimanager.databinding.ItemModBinding

data class ModRow(
    val folder: String,
    val name: String,
    val pid: String,
    val enabled: Boolean,
)

class ModAdapter : RecyclerView.Adapter<ModAdapter.VH>() {

    private var rows: List<ModRow> = emptyList()
    private val selected = mutableSetOf<String>()  // folder names
    var onSelectionChanged: (Int) -> Unit = {}

    fun submit(newRows: List<ModRow>) {
        rows = newRows
        selected.retainAll(newRows.map { it.folder }.toSet())
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    fun allRows(): List<ModRow> = rows

    fun selectedRows(): List<ModRow> = rows.filter { it.folder in selected }

    inner class VH(val b: ItemModBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemModBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        holder.b.name.text = row.name
        holder.b.meta.text = "${row.pid.ifEmpty { "no packageId" }} · ${row.folder}"
        holder.b.state.text = if (row.enabled) "ON" else "OFF"
        holder.b.checkbox.setOnCheckedChangeListener(null)
        holder.b.checkbox.isChecked = row.folder in selected

        val toggle = {
            if (!selected.add(row.folder)) selected.remove(row.folder)
            notifyItemChanged(holder.bindingAdapterPosition)
            onSelectionChanged(selected.size)
        }
        holder.b.root.setOnClickListener { toggle() }
        holder.b.checkbox.setOnClickListener { toggle() }
    }
}
