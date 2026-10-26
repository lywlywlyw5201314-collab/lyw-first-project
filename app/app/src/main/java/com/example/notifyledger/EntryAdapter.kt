package com.example.notifyledger

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.notifyledger.model.LedgerEntry
import java.util.Locale

/**
 * 账本流水列表。
 *  - 点一行 = 改分类(触发学习闭环: updateEntryCategory + upsertLearnedRule)
 *  - 长按一行 = 删除
 * 数据来源为整表刷新(骨架阶段; Codex 可改增量/分页)。
 */
class EntryAdapter(
    private val onPickCategory: (LedgerEntry) -> Unit,
    private val onDelete: (LedgerEntry) -> Unit
) : RecyclerView.Adapter<EntryAdapter.EntryVH>() {

    var entries: List<LedgerEntry> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class EntryVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvAmount: TextView = view.findViewById(R.id.tvAmount)
        val tvMerchant: TextView = view.findViewById(R.id.tvMerchant)
        val tvMeta: TextView = view.findViewById(R.id.tvMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_entry, parent, false)
        return EntryVH(v)
    }

    override fun getItemCount(): Int = entries.size

    override fun onBindViewHolder(holder: EntryVH, position: Int) {
        val e = entries[position]
        holder.tvAmount.text = "¥" + String.format(Locale.getDefault(), "%.2f", e.amount)
        holder.tvMerchant.text = e.merchant ?: "(未识别商户)"
        holder.tvMeta.text = listOfNotNull(e.day.ifEmpty { null }, e.source, e.category)
            .joinToString(" · ")
        holder.itemView.setOnClickListener { onPickCategory(e) }
        holder.itemView.setOnLongClickListener {
            onDelete(e)
            true
        }
    }
}
