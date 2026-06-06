package sols.sync.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.enableEdgeToEdge
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import sols.sync.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import coil.load
import coil.dispose

class ReceivedFilesActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "received_files"
        private const val KEY_FILES = "files_json"

        fun start(context: Context) {
            context.startActivity(Intent(context, ReceivedFilesActivity::class.java))
        }

        /** Add a file entry to the persisted list. Called from SyncService. */
        fun addReceivedFile(context: Context, filePath: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_FILES, "[]") ?: "[]"
            val arr = JSONArray(existing)
            val obj = org.json.JSONObject()
            obj.put("path", filePath)
            obj.put("timestamp", System.currentTimeMillis())
            arr.put(obj)
            prefs.edit().putString(KEY_FILES, arr.toString()).apply()
        }

        /** Load all received file entries, most recent first. */
        fun loadReceivedFiles(context: Context): MutableList<ReceivedFileEntry> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_FILES, "[]") ?: "[]"
            val arr = JSONArray(json)
            val result = mutableListOf<ReceivedFileEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(ReceivedFileEntry(path = obj.getString("path"), timestamp = obj.getLong("timestamp")))
            }
            return result.sortedByDescending { it.timestamp }.toMutableList()
        }

        private fun saveEntries(context: Context, entries: List<ReceivedFileEntry>) {
            val arr = JSONArray()
            for (e in entries) {
                val obj = org.json.JSONObject()
                obj.put("path", e.path)
                obj.put("timestamp", e.timestamp)
                arr.put(obj)
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_FILES, arr.toString()).apply()
        }
    }

    data class ReceivedFileEntry(val path: String, val timestamp: Long)

    private lateinit var adapter: ReceivedFilesAdapter
    private val allEntries = mutableListOf<ReceivedFileEntry>()
    private val entries = mutableListOf<ReceivedFileEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_received_files)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.received_files_root)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear_all) {
                confirmClearAll()
                true
            } else false
        }

        val searchItem = toolbar.menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? androidx.appcompat.widget.SearchView
        searchView?.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                filterList(newText)
                return true
            }
        })

        allEntries.addAll(loadReceivedFiles(this))
        entries.addAll(allEntries)

        val recycler = findViewById<RecyclerView>(R.id.recycler_files)
        val emptyLayout = findViewById<View>(R.id.layout_empty)

        adapter = ReceivedFilesAdapter(
            context = this,
            entries = entries,
            onOpen = { openFile(it) },
            onShare = { shareFile(it) },
            onDelete = { entry, position -> deleteEntry(entry, position, emptyLayout) }
        )

        updateEmptyState(emptyLayout)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
    }

    private fun updateEmptyState(emptyLayout: View) {
        val recycler = findViewById<RecyclerView>(R.id.recycler_files)
        if (entries.isEmpty()) {
            recycler.visibility = View.GONE
            emptyLayout.visibility = View.VISIBLE
        } else {
            recycler.visibility = View.VISIBLE
            emptyLayout.visibility = View.GONE
        }
    }

    private fun filterList(query: String?) {
        entries.clear()
        if (query.isNullOrBlank()) {
            entries.addAll(allEntries)
        } else {
            val lowerQuery = query.lowercase()
            entries.addAll(allEntries.filter { 
                File(it.path).name.lowercase().contains(lowerQuery) 
            })
        }
        adapter.notifyDataSetChanged()
        updateEmptyState(findViewById(R.id.layout_empty))
    }

    private fun confirmClearAll() {
        if (allEntries.isEmpty()) {
            Toast.makeText(this, "Nothing to clear", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear all files?")
            .setMessage("This removes all entries from the list. Files already saved to Downloads will not be deleted.")
            .setPositiveButton("Clear All") { _, _ ->
                val count = allEntries.size
                allEntries.clear()
                entries.clear()
                adapter.notifyDataSetChanged()
                saveEntries(this, allEntries)
                updateEmptyState(findViewById(R.id.layout_empty))
                Toast.makeText(this, "$count entries cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteEntry(entry: ReceivedFileEntry, position: Int, emptyLayout: View) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove entry?")
            .setMessage("Remove \"${File(entry.path).name}\" from the list? The file in Downloads will not be deleted.")
            .setPositiveButton("Remove") { _, _ ->
                allEntries.remove(entry)
                entries.remove(entry)
                adapter.notifyDataSetChanged()
                saveEntries(this, allEntries)
                updateEmptyState(emptyLayout)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getUri(file: File) = FileProvider.getUriForFile(
        this, "${packageName}.fileprovider", file
    )

    private fun openFile(file: File) {
        if (!file.exists()) {
            Toast.makeText(this, "File no longer exists in Downloads", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = getUri(file)
            val mime = contentResolver.getType(uri) ?: "*/*"
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "No app available to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(file: File) {
        if (!file.exists()) {
            Toast.makeText(this, "File no longer exists in Downloads", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = getUri(file)
            val mime = contentResolver.getType(uri) ?: "*/*"
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND)
                        .setType(mime)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "Share ${file.name}"
                )
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to share file", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Adapter ─────────────────────────────────────────────────────────────

    private class ReceivedFilesAdapter(
        private val context: Context,
        private val entries: MutableList<ReceivedFileEntry>,
        private val onOpen: (File) -> Unit,
        private val onShare: (File) -> Unit,
        private val onDelete: (ReceivedFileEntry, Int) -> Unit,
    ) : RecyclerView.Adapter<ReceivedFilesAdapter.ViewHolder>() {

        private val timeFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cardIconContainer: com.google.android.material.card.MaterialCardView = view.findViewById(R.id.card_icon_container)
            val ivPreview: ImageView = view.findViewById(R.id.iv_file_preview)
            val ivIcon: ImageView = view.findViewById(R.id.iv_file_icon)
            val tvFilename: TextView = view.findViewById(R.id.tv_filename)
            val tvExt: TextView = view.findViewById(R.id.tv_file_ext)
            val tvSize: TextView = view.findViewById(R.id.tv_file_size)
            val tvTime: TextView = view.findViewById(R.id.tv_file_time)
            val ivShare: ImageView = view.findViewById(R.id.iv_share)
            val ivMore: ImageView = view.findViewById(R.id.iv_more)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_received_file, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]
            val file = File(entry.path)
            val ext = file.extension.lowercase()

            holder.tvFilename.text = file.name
            holder.tvTime.text = timeFormat.format(Date(entry.timestamp))

            if (ext.isNotEmpty()) {
                holder.tvExt.text = ext
                holder.tvExt.visibility = View.VISIBLE
            } else {
                holder.tvExt.visibility = View.GONE
            }

            if (file.exists()) {
                holder.tvSize.text = Formatter.formatShortFileSize(context, file.length())
                holder.ivIcon.alpha = 1f
            } else {
                holder.tvSize.text = "Missing from Downloads"
                holder.ivIcon.alpha = 0.4f
            }

            val isImage = ext in listOf("jpg", "jpeg", "png", "gif", "webp", "heic")

            if (isImage && file.exists()) {
                holder.ivPreview.visibility = View.VISIBLE
                holder.ivIcon.visibility = View.GONE
                holder.cardIconContainer.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
                holder.ivPreview.load(file) {
                    crossfade(true)
                }
            } else {
                holder.ivPreview.dispose()
                holder.ivPreview.visibility = View.GONE
                holder.ivIcon.visibility = View.VISIBLE
                
                val iconRes = when (ext) {
                    "pdf" -> R.drawable.ic_file_document
                    "jpg", "jpeg", "png", "gif", "webp", "heic" -> R.drawable.ic_file_image
                    "mp4", "mkv", "avi", "mov", "webm" -> R.drawable.ic_file_video
                    "mp3", "flac", "aac", "wav", "ogg" -> R.drawable.ic_file_audio
                    "zip", "rar", "7z", "tar", "gz" -> R.drawable.ic_file_archive
                    "apk" -> R.drawable.ic_file_apk
                    "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md" -> R.drawable.ic_file_document
                    else -> R.drawable.ic_file_generic
                }
                holder.ivIcon.setImageResource(iconRes)
                
                holder.cardIconContainer.setCardBackgroundColor(fileTypeColor(ext))
                holder.ivIcon.setColorFilter(android.graphics.Color.WHITE)
            }

            holder.itemView.setOnClickListener { onOpen(file) }
            holder.ivShare.setOnClickListener { onShare(file) }
            holder.ivMore.setOnClickListener {
                showMoreMenu(holder.ivMore, entry, holder.adapterPosition)
            }
        }

        override fun getItemCount() = entries.size

        private fun showMoreMenu(anchor: View, entry: ReceivedFileEntry, position: Int) {
            val popup = androidx.appcompat.widget.PopupMenu(context, anchor)
            popup.menu.add(0, 1, 0, "Open")
            popup.menu.add(0, 2, 1, "Share")
            popup.menu.add(0, 3, 2, "Remove from list")
            popup.setOnMenuItemClickListener { item ->
                val file = File(entry.path)
                when (item.itemId) {
                    1 -> onOpen(file)
                    2 -> onShare(file)
                    3 -> onDelete(entry, position)
                }
                true
            }
            popup.show()
        }

        private fun fileTypeColor(ext: String): Int {
            return when (ext) {
                "pdf" -> 0xFFE53935.toInt()
                "jpg", "jpeg", "png", "gif", "webp", "heic" -> 0xFF8E24AA.toInt()
                "mp4", "mkv", "avi", "mov", "webm" -> 0xFF1E88E5.toInt()
                "mp3", "flac", "aac", "wav", "ogg" -> 0xFF00ACC1.toInt()
                "zip", "rar", "7z", "tar", "gz" -> 0xFFF4511E.toInt()
                "doc", "docx" -> 0xFF1565C0.toInt()
                "xls", "xlsx" -> 0xFF2E7D32.toInt()
                "ppt", "pptx" -> 0xFFE65100.toInt()
                "apk" -> 0xFF43A047.toInt()
                "txt", "md", "log" -> 0xFF546E7A.toInt()
                else -> 0xFF757575.toInt()
            }
        }
    }
}
