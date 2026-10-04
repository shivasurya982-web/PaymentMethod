package com.example.notifyforwarder

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TransactionAdapter(private var items: List<TransactionRecord>) :
    RecyclerView.Adapter<TransactionAdapter.ViewHolder>() {

    private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val itemAmount: TextView = view.findViewById(R.id.itemAmount)
        val itemForwardedBadge: TextView = view.findViewById(R.id.itemForwardedBadge)
        val itemAppPkg: TextView = view.findViewById(R.id.itemAppPkg)
        val itemTimestamp: TextView = view.findViewById(R.id.itemTimestamp)
        val itemSenderAndUtr: TextView = view.findViewById(R.id.itemSenderAndUtr)
        val itemServerStatus: TextView = view.findViewById(R.id.itemServerStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transaction, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        val amountDisplay = if (item.amount != "Not available in notification") {
            "${item.amount} ${item.classification.lowercase()}"
        } else {
            "${item.classification} notification"
        }
        holder.itemAmount.text = amountDisplay

        holder.itemAppPkg.text = "Source: ${item.packageName}"
        holder.itemTimestamp.text = timeFormatter.format(Date(item.timestamp))

        val senderInfo = if (item.sender != "Not available in notification") "Sender: ${item.sender}" else "Sender: N/A"
        val utrInfo = if (item.utr != "Not available in notification") "UTR: ${item.utr}" else "UTR: N/A"
        holder.itemSenderAndUtr.text = "$senderInfo | $utrInfo"

        holder.itemServerStatus.text = "Server status: ${item.serverStatus}"

        when (item.forwardingStatus) {
            "YES" -> {
                holder.itemForwardedBadge.text = "Forwarded: YES"
                holder.itemForwardedBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#DCFCE7"))
                holder.itemForwardedBadge.setTextColor(Color.parseColor("#16A34A"))
            }
            "IGNORED" -> {
                holder.itemForwardedBadge.text = "Ignored"
                holder.itemForwardedBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F1F5F9"))
                holder.itemForwardedBadge.setTextColor(Color.parseColor("#475569"))
            }
            else -> {
                holder.itemForwardedBadge.text = "Forwarded: NO"
                holder.itemForwardedBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FEE2E2"))
                holder.itemForwardedBadge.setTextColor(Color.parseColor("#DC2626"))
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<TransactionRecord>) {
        this.items = newItems
        notifyDataSetChanged()
    }
}
