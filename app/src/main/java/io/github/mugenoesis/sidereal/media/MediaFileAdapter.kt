package io.github.mugenoesis.sidereal.media

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.mugenoesis.sidereal.R
import io.github.mugenoesis.sidereal.camera.DownloadProgress
import io.github.mugenoesis.sidereal.camera.DownloadStatus
import dji.sdk.media.MediaFile

/**
 * Grid adapter for the camera's SD card file list. Thumbnails are fetched
 * lazily per-item (MediaFile.fetchThumbnail() pulls it over WiFi from the
 * camera) and cached on the MediaFile object itself by the SDK - bind()
 * only re-fetches when getThumbnail() is still null, so scrolling a cell
 * back into view doesn't re-request it. boundFile equality-checking in the
 * fetch callback guards against a slow fetch landing after the ViewHolder
 * has already been recycled onto a different item.
 */
class MediaFileAdapter(
    private val onItemClick: (MediaFile) -> Unit
) : RecyclerView.Adapter<MediaFileAdapter.ViewHolder>() {

    private var items: List<MediaFile> = emptyList()
    private var selectMode = false
    private var selectedNames: Set<String> = emptySet()
    private var progressByName: Map<String, DownloadProgress> = emptyMap()

    fun submitFiles(files: List<MediaFile>) {
        items = files
        notifyDataSetChanged()
    }

    fun setSelectMode(enabled: Boolean) {
        if (selectMode == enabled) return
        selectMode = enabled
        notifyDataSetChanged()
    }

    fun setSelected(names: Set<String>) {
        selectedNames = names
        notifyDataSetChanged()
    }

    fun setProgress(progress: Map<String, DownloadProgress>) {
        progressByName = progress
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail = itemView.findViewById<ImageView>(R.id.thumbnailImage)
        private val scrim = itemView.findViewById<View>(R.id.selectedScrim)
        private val videoBadge = itemView.findViewById<View>(R.id.videoBadge)
        private val durationText = itemView.findViewById<TextView>(R.id.videoDurationText)
        private val selectionIndicator = itemView.findViewById<ImageView>(R.id.selectionIndicator)
        private val progressBar = itemView.findViewById<ProgressBar>(R.id.downloadProgressBar)
        private val statusIcon = itemView.findViewById<ImageView>(R.id.downloadStatusIcon)

        private var boundFile: MediaFile? = null

        fun bind(mediaFile: MediaFile) {
            boundFile = mediaFile

            val cached = mediaFile.thumbnail
            if (cached != null) {
                thumbnail.setImageBitmap(cached)
            } else {
                thumbnail.setImageDrawable(null)
                mediaFile.fetchThumbnail { error ->
                    if (error == null && boundFile == mediaFile) {
                        thumbnail.post {
                            if (boundFile == mediaFile) thumbnail.setImageBitmap(mediaFile.thumbnail)
                        }
                    }
                }
            }

            val isVideo = mediaFile.mediaType == MediaFile.MediaType.MOV || mediaFile.mediaType == MediaFile.MediaType.MP4
            videoBadge.visibility = if (isVideo) View.VISIBLE else View.GONE
            if (isVideo) {
                val totalSeconds = mediaFile.durationInSeconds.toInt()
                durationText.text = String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
            }

            val isSelected = selectedNames.contains(mediaFile.fileName)
            selectionIndicator.visibility = if (selectMode) View.VISIBLE else View.GONE
            selectionIndicator.setImageResource(if (isSelected) R.drawable.ic_check_circle else R.drawable.ic_circle_outline)
            scrim.visibility = if (selectMode && isSelected) View.VISIBLE else View.GONE

            val progress = progressByName[mediaFile.fileName]
            when (progress?.status) {
                DownloadStatus.DOWNLOADING -> {
                    progressBar.visibility = View.VISIBLE
                    statusIcon.visibility = View.GONE
                    progressBar.progress = if (progress.bytesTotal > 0) ((progress.bytesDownloaded * 100) / progress.bytesTotal).toInt() else 0
                }
                DownloadStatus.DONE -> {
                    progressBar.visibility = View.GONE
                    statusIcon.visibility = View.VISIBLE
                    statusIcon.setImageResource(R.drawable.ic_check_circle)
                }
                DownloadStatus.FAILED -> {
                    progressBar.visibility = View.GONE
                    statusIcon.visibility = View.VISIBLE
                    statusIcon.setImageResource(R.drawable.ic_close)
                }
                else -> {
                    progressBar.visibility = View.GONE
                    statusIcon.visibility = View.GONE
                }
            }

            itemView.setOnClickListener { onItemClick(mediaFile) }
        }
    }
}
