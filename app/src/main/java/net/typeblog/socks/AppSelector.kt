package net.typeblog.socks

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Filter
import android.widget.Filterable
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class AppSelector {

    fun interface OnAppSelectedListener {
        fun onAppSelected(appList: String)
    }

    companion object {
        fun show(activity: Activity, currentApps: String?, listener: OnAppSelectedListener) {
            val pm = activity.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val appList = mutableListOf<AppInfo>()

            val selectedPackages = mutableSetOf<String>()
            if (!currentApps.isNullOrEmpty()) {
                for (p in currentApps.split("\n")) {
                    selectedPackages.add(p.trim())
                }
            }

            for (packageInfo in packages) {
                val info = AppInfo()
                info.name = packageInfo.loadLabel(pm).toString()
                info.packageName = packageInfo.packageName
                info.icon = packageInfo.loadIcon(pm)
                info.selected = selectedPackages.contains(info.packageName)
                appList.add(info)
            }

            appList.sortWith(Comparator { o1, o2 ->
                o1.name.compareTo(o2.name, ignoreCase = true)
            })

            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }

            val searchBox = EditText(activity).apply {
                hint = activity.getString(R.string.search_apps)
                isSingleLine = true
            }
            layout.addView(searchBox)

            val listView = ListView(activity)
            val adapter = AppAdapter(activity, appList)
            listView.adapter = adapter

            val listParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f
            )
            layout.addView(listView, listParams)

            searchBox.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    adapter.filter.filter(s)
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            AlertDialog.Builder(activity)
                .setTitle(R.string.adv_app_selector)
                .setView(layout)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val result = appList.filter { it.selected }
                        .joinToString("\n") { it.packageName }
                    listener.onAppSelected(result)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private class AppInfo {
        var name: String = ""
        var packageName: String = ""
        lateinit var icon: Drawable
        var selected: Boolean = false
    }

    private class AppAdapter(
        private val context: Context,
        private val appsOriginal: List<AppInfo>
    ) : BaseAdapter(), Filterable {

        private var appsFiltered: List<AppInfo> = appsOriginal
        private var filter: AppFilter? = null

        override fun getCount(): Int = appsFiltered.size

        override fun getItem(position: Int): Any = appsFiltered[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.app_item, parent, false)

            val info = appsFiltered[position]
            val text = v.findViewById<TextView>(R.id.app_name)
            val pkg = v.findViewById<TextView>(R.id.app_package)
            val icon = v.findViewById<ImageView>(R.id.app_icon)
            val cb = v.findViewById<CheckBox>(R.id.app_check)

            text.text = info.name
            pkg.text = info.packageName
            icon.setImageDrawable(info.icon)
            cb.isChecked = info.selected

            v.setOnClickListener {
                info.selected = !info.selected
                cb.isChecked = info.selected
            }

            return v
        }

        override fun getFilter(): Filter {
            if (filter == null) {
                filter = AppFilter()
            }
            return filter!!
        }

        private inner class AppFilter : Filter() {
            @Suppress("UNCHECKED_CAST")
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val results = FilterResults()
                if (constraint.isNullOrEmpty()) {
                    results.values = appsOriginal
                    results.count = appsOriginal.size
                } else {
                    val query = constraint.toString().lowercase()
                    val filtered = appsOriginal.filter {
                        it.name.lowercase().contains(query) ||
                                it.packageName.lowercase().contains(query)
                    }
                    results.values = filtered
                    results.count = filtered.size
                }
                return results
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                appsFiltered = results.values as List<AppInfo>
                notifyDataSetChanged()
            }
        }
    }
}
