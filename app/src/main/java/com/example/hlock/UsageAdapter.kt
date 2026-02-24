package com.example.hlock

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class UsageAdapter(private var usageList: List<AppUsageInfo>) :
    RecyclerView.Adapter<UsageAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val appIcon: ImageView = view.findViewById(R.id.appIcon)
        val appName: TextView = view.findViewById(R.id.appName)
        val appTime: TextView = view.findViewById(R.id.appTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_usage, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = usageList[position]
        holder.appName.text = item.appName
        holder.appTime.text = item.usageTime
        holder.appIcon.setImageDrawable(item.appIcon)
    }

    override fun getItemCount() = usageList.size

    fun updateData(newList: List<AppUsageInfo>) {
        usageList = newList
        notifyDataSetChanged()
    }
}
