package zm.co.codelabs.utorr

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import zm.co.codelabs.utorr.databinding.ItemTorrentBinding
import kotlin.math.log10

class TorrentAdapter(
    private val onPauseResume: (TorrentItem) -> Unit,
    private val onDelete: (TorrentItem) -> Unit,
    private val onOpen: (TorrentItem) -> Unit
) : ListAdapter<TorrentItem, TorrentAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTorrentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            val bundle = payloads[0] as android.os.Bundle
            holder.update(bundle, getItem(position))
        }
    }

    inner class ViewHolder(private val binding: ItemTorrentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: TorrentItem) {
            binding.torrentName.text = item.name
            updateProgressAndStats(item)
            binding.torrentPeers.text = "Peers: ${item.peers}(${item.seeds})"

            val isFinished = item.status == TorrentItem.Status.FINISHED
            binding.btnPauseResume.visibility = if (isFinished) android.view.View.GONE else android.view.View.VISIBLE
            binding.btnOpen.visibility = if (isFinished) android.view.View.VISIBLE else android.view.View.GONE

            binding.btnPauseResume.text = if (item.status == TorrentItem.Status.PAUSED) "Resume" else "Pause"
            binding.btnPauseResume.setIconResource(
                if (item.status == TorrentItem.Status.PAUSED) android.R.drawable.ic_media_play 
                else android.R.drawable.ic_media_pause
            )

            binding.btnPauseResume.setOnClickListener { onPauseResume(item) }
            binding.btnDelete.setOnClickListener { onDelete(item) }
            binding.btnOpen.setOnClickListener { onOpen(item) }
        }

        fun update(bundle: android.os.Bundle, item: TorrentItem) {
            if (bundle.containsKey("progress") || bundle.containsKey("downloadSpeed") || 
                bundle.containsKey("downloadedSize") || bundle.containsKey("totalSize")) {
                updateProgressAndStats(item)
            }
            if (bundle.containsKey("peers") || bundle.containsKey("seeds")) {
                binding.torrentPeers.text = "Peers: ${item.peers}(${item.seeds})"
            }
            if (bundle.containsKey("status")) {
                val isFinished = item.status == TorrentItem.Status.FINISHED
                binding.btnPauseResume.visibility = if (isFinished) android.view.View.GONE else android.view.View.VISIBLE
                binding.btnOpen.visibility = if (isFinished) android.view.View.VISIBLE else android.view.View.GONE

                binding.btnPauseResume.text = if (item.status == TorrentItem.Status.PAUSED) "Resume" else "Pause"
                binding.btnPauseResume.setIconResource(
                    if (item.status == TorrentItem.Status.PAUSED) android.R.drawable.ic_media_play 
                    else android.R.drawable.ic_media_pause
                )
            }
        }

        private fun updateProgressAndStats(item: TorrentItem) {
            binding.torrentProgress.progress = item.progress.toInt()
            val speedText = formatSpeed(item.downloadSpeed)
            val sizeText = "${formatSize(item.downloadedSize)} of ${formatSize(item.totalSize)}"
            binding.torrentStats.text = "${item.progress.toInt()}% • $sizeText • $speedText"
        }

    private fun formatSpeed(bytesPerSecond: Long): String {
            if (bytesPerSecond <= 0) return "0 B/s"
            val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
            val i = (log10(bytesPerSecond.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)
            return String.format("%.1f %s", bytesPerSecond / Math.pow(1024.0, i.toDouble()), units[i])
        }

        private fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val i = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)
            return String.format("%.1f %s", bytes / Math.pow(1024.0, i.toDouble()), units[i])
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<TorrentItem>() {
        override fun areItemsTheSame(oldItem: TorrentItem, newItem: TorrentItem): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: TorrentItem, newItem: TorrentItem): Boolean = oldItem == newItem

        override fun getChangePayload(oldItem: TorrentItem, newItem: TorrentItem): Any? {
            val diff = android.os.Bundle()
            if (oldItem.progress != newItem.progress) diff.putDouble("progress", newItem.progress)
            if (oldItem.downloadSpeed != newItem.downloadSpeed) diff.putLong("downloadSpeed", newItem.downloadSpeed)
            if (oldItem.downloadedSize != newItem.downloadedSize) diff.putLong("downloadedSize", newItem.downloadedSize)
            if (oldItem.totalSize != newItem.totalSize) diff.putLong("totalSize", newItem.totalSize)
            if (oldItem.peers != newItem.peers) diff.putInt("peers", newItem.peers)
            if (oldItem.seeds != newItem.seeds) diff.putInt("seeds", newItem.seeds)
            if (oldItem.status != newItem.status) diff.putString("status", newItem.status.name)
            
            return if (diff.size() > 0) diff else super.getChangePayload(oldItem, newItem)
        }
    }
}