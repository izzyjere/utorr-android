package zm.co.codelabs.utorr

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.libtorrent4j.*
import org.libtorrent4j.alerts.*
import org.libtorrent4j.swig.remove_flags_t
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * TorrentManager that interfaces with libtorrent4j.
 */
class TorrentManager(private val context: Context) {

    private val _torrents = MutableStateFlow<List<TorrentItem>>(emptyList())
    val torrents: StateFlow<List<TorrentItem>> = _torrents

    private val sessionManager = SessionManager()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handles = ConcurrentHashMap<String, TorrentHandle>()

    private val listener = object : AlertListener {
        override fun types(): IntArray? = null // Listen to all alerts

        override fun alert(alert: Alert<*>) {
            when (alert.type()) {
                AlertType.ADD_TORRENT -> {
                    val ata = alert as AddTorrentAlert
                    val handle = ata.handle()
                    handles[handle.infoHash().toString()] = handle
                    handle.resume()
                    updateTorrentList()
                }

                AlertType.TORRENT_REMOVED -> {
                    val tra = alert as TorrentRemovedAlert
                    handles.remove(tra.handle().infoHash().toString())
                    updateTorrentList()
                }

                AlertType.TORRENT_FINISHED,
                AlertType.STATE_UPDATE,
                AlertType.TORRENT_PAUSED,
                AlertType.TORRENT_RESUMED -> {
                    updateTorrentList()
                }

                else -> {}
            }
        }
    }

    init {
        sessionManager.addListener(listener)
        sessionManager.start()

        // Periodically request state updates
        scope.launch {
            while (isActive) {
                sessionManager.postTorrentUpdates()
                delay(1000)
            }
        }
    }

    fun addMagnet(uri: String, saveDir: File) {
        if (!saveDir.exists()) saveDir.mkdirs()
        sessionManager.download(uri, saveDir, torrent_flags_t())
    }

    fun addTorrentFile(file: File, saveDir: File) {
        if (!saveDir.exists()) saveDir.mkdirs()
        val torrentInfo = TorrentInfo(file)
        sessionManager.download(torrentInfo, saveDir)
    }

    fun pauseTorrent(id: String) {
        handles[id]?.pause()
    }

    fun resumeTorrent(id: String) {
        handles[id]?.resume()
    }

    fun pauseAll() {
        handles.values.forEach { it.pause() }
    }

    fun resumeAll() {
        handles.values.forEach { it.resume() }
    }

    fun removeTorrent(id: String, deleteFiles: Boolean) {
        handles[id]?.let { handle ->
            val flags = if (deleteFiles) remove_flags_t.from_int(1) else remove_flags_t()
            sessionManager.remove(handle, flags)
        }
    }

    private fun updateTorrentList() {
        val items = handles.values.map { handle ->
            val status = handle.status()
            val infoHashString = handle.infoHash().toString()
            val name = status.name() ?: "Unknown"

            TorrentItem(
                id = infoHashString,
                name = name,
                progress = status.progress() * 100,
                downloadSpeed = status.downloadPayloadRate().toLong(),
                uploadSpeed = status.uploadPayloadRate().toLong(),
                totalSize = status.totalDone() + status.totalWanted(),
                downloadedSize = status.totalDone(),
                status = mapStatus(status),
                peers = status.numPeers(),
                seeds = status.numSeeds(),
                filePath = status.swig().save_path + File.separator + name
            )
        }
        _torrents.value = items
    }

    private fun mapStatus(status: TorrentStatus): TorrentItem.Status {
        if (status.flags().eq(torrent_flags_t.from_int(16))) {
            return TorrentItem.Status.PAUSED
        }
        return when (status.state()) {
            TorrentStatus.State.CHECKING_FILES -> TorrentItem.Status.CHECKING
            TorrentStatus.State.DOWNLOADING_METADATA -> TorrentItem.Status.DOWNLOADING
            TorrentStatus.State.DOWNLOADING -> TorrentItem.Status.DOWNLOADING
            TorrentStatus.State.FINISHED -> TorrentItem.Status.FINISHED
            TorrentStatus.State.SEEDING -> TorrentItem.Status.FINISHED
            TorrentStatus.State.CHECKING_RESUME_DATA -> TorrentItem.Status.CHECKING
            else -> TorrentItem.Status.QUEUED
        }
    }

    fun stop() {
        sessionManager.removeListener(listener)
        sessionManager.stop()
        scope.cancel()
    }
}