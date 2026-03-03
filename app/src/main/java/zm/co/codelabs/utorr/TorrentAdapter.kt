package zm.co.codelabs.utorr

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import zm.co.codelabs.utorr.databinding.ItemTorrentBinding

class TorrentAdapter(
    private val onPauseResume: (TorrentItem) -> Unit,
    private val onDelete: (TorrentItem) -> Unit
) : ListAdapter<TorrentItem, TorrentAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTorrentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemTorrentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: TorrentItem) {
            binding.torrentName.text = item.name
            binding.torrentProgress.progress = item.progress.toInt()
            
            val speedText = formatSpeed(item.downloadSpeed)
            val sizeText = "${formatSize(item.downloadedSize)} of ${formatSize(item.totalSize)}"
            binding.torrentStats.text = "${item.progress.toInt()}% • $sizeText • $speedText"
            binding.torrentPeers.text = "Peers: ${item.peers}(${item.seeds})"

            binding.btnPauseResume.text = if (item.status == TorrentItem.Status.PAUSED) "Resume" else "Pause"
            binding.btnPauseResume.setIconResource(
                if (item.status == TorrentItem.Status.PAUSED) android.R.drawable.ic_media_play 
                else android.R.drawable.ic_media_pause
            )

            binding.btnPauseResume.setOnClickListener { onPauseResume(item) }
            binding.btnDelete.setOnClickListener { onDelete(item) }
        }

    private fun formatSpeed(bytesPerSecond: Long): String {
            if (bytesPerSecond <= 0) return "0 B/s"
            val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
            val i = (Math.log10(bytesPerSecond.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
            return String.format("%.1f %s", bytesPerSecond / Math.pow(1024.0, i.toDouble()), units[i])
        }

        private fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val i = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
            return String.format("%.1f %s", bytes / Math.pow(1024.0, i.toDouble()), units[i])
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<TorrentItem>() {
        override fun areItemsTheSame(oldItem: TorrentItem, newItem: TorrentItem): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: TorrentItem, newItem: TorrentItem): Boolean = oldItem == newItem
    }
}