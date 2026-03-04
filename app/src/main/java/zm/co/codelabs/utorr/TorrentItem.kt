package zm.co.codelabs.utorr

data class TorrentItem(
    val id: String,
    val name: String,
    val progress: Double,
    val downloadSpeed: Long,
    val uploadSpeed: Long,
    val totalSize: Long,
    val downloadedSize: Long,
    val status: Status,
    val peers: Int,
    val seeds: Int,
    val filePath: String? = null
) {
    enum class Status {
        DOWNLOADING,
        PAUSED,
        FINISHED,
        ERROR,
        QUEUED,
        CHECKING
    }
}