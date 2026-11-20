package com.dualframe.recorder

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

data class Capture(val uri: Uri, val video: Boolean, val name: String, val durationMs: Long) {
    val shape get() = if (name.contains("_landscape")) "16:9" else "9:16"
}

/** DualFrame's own photos and videos, newest first; each take's portrait file comes before its landscape file. */
fun queryCaptures(context: Context): List<Capture> {
    val out = ArrayList<Capture>()
    val sources = listOf(
        Triple(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, "Movies/DualFrame%"),
        Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, "Pictures/DualFrame%"),
    )
    for ((collection, video, path) in sources) {
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DURATION)
        context.contentResolver.query(collection, cols, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf(path), null)
            ?.use { c ->
                while (c.moveToNext()) out += Capture(ContentUris.withAppendedId(collection, c.getLong(0)), video, c.getString(1) ?: "", c.getLong(2))
            }
    }
    // names are DualFrame_<yyyyMMdd_HHmmss>_<portrait|landscape>; descending keeps a take's pair together, portrait first
    return out.sortedByDescending { it.name }
}

fun openCapture(activity: Activity, c: Capture) = try {
    activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(c.uri, if (c.video) "video/mp4" else "image/jpeg")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
} catch (e: ActivityNotFoundException) {
    Toast.makeText(activity, "No app on this phone can open this file.", Toast.LENGTH_SHORT).show()
}

fun shareCapture(activity: Activity, c: Capture) = activity.startActivity(Intent.createChooser(
    Intent(Intent.ACTION_SEND).setType(if (c.video) "video/mp4" else "image/jpeg")
        .putExtra(Intent.EXTRA_STREAM, c.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))

/** Loads MediaStore thumbnails off the main thread into ImageViews, with a small memory cache. */
class Thumbnails(private val context: Context) {
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val cache = LruCache<Uri, Bitmap>(80)

    fun load(uri: Uri, into: ImageView) {
        into.tag = uri
        cache.get(uri)?.let { into.setImageBitmap(it); return }
        into.setImageDrawable(null)
        pool.execute {
            val bmp = runCatching { context.contentResolver.loadThumbnail(uri, Size(360, 360), null) }.getOrNull() ?: return@execute
            cache.put(uri, bmp)
            main.post { if (into.tag == uri) into.setImageBitmap(bmp) } // the view may have been reused for another file
        }
    }

    /** Runs [work] off the main thread and hands its result to [done] on the main thread. */
    fun <T> background(work: () -> T, done: (T) -> Unit) = pool.execute { val r = work(); main.post { done(r) } }

    fun shutdown() = pool.shutdownNow()
}

class GalleryActivity : Activity() {
    private lateinit var thumbs: Thumbnails
    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private var items: List<Capture> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        thumbs = Thumbnails(this)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, i, _ -> openCapture(this, items[i]) }
        grid.setOnItemLongClickListener { _, _, i, _ -> shareCapture(this, items[i]); true }
    }

    override fun onResume() {
        super.onResume()
        thumbs.background({ queryCaptures(this) }) { // files may have been deleted in another app
            items = it
            empty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
        }
    }

    override fun onDestroy() {
        thumbs.shutdown()
        super.onDestroy()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: layoutInflater.inflate(R.layout.item_capture, parent, false)
            v.layoutParams.height = grid.columnWidth // square cells
            val c = items[i]
            thumbs.load(c.uri, v.findViewById(R.id.thumb))
            val s = c.durationMs / 1000
            v.findViewById<TextView>(R.id.badge).text =
                if (c.video) "▶ ${c.shape} · %d:%02d".format(s / 60, s % 60) else c.shape
            return v
        }
    }
}
