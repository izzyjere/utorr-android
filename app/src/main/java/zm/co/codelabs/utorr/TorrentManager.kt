package zm.co.codelabs.utorr

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import utorr.Listener
import utorr.StatusList

/**
 * TorrentManager that interfaces with the Go (anacrolix/torrent) engine built via gomobile.
 *
 * Expected Go API (from gomobile bind):
 *  - Engine()
 *  - start(rootDir: String, sessionDir: String, maxConns: Long, listener: Listener, debug: Boolean)
 *  - stop()
 *  - addMagnet(magnet: String): String
 *  - addTorrentBytes(data: ByteArray): String
 *  - pause(id: String)
 *  - resume(id: String)
 *  - pauseAll()
 *  - resumeAll()
 *  - remove(id: String, deleteFiles: Boolean)
 *
 * Note: the Go engine is responsible for:
 *  - session persistence (Bolt in sessionDir)
 *  - restoring torrents on startup from its registry
 *  - emitting status snapshots periodically (OnStatus)
 */
class TorrentManager(
    private val context: Context,
    private val rootDir: File = File(context.filesDir, "downloads"),
    private val sessionDir: File = File(context.filesDir, "session"),
    private val maxConns: Int = 80,
    private val debug: Boolean = false,
) {

    private val _torrents = MutableStateFlow<List<TorrentItem>>(emptyList())
    val torrents: StateFlow<List<TorrentItem>> = _torrents

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Keep IDs we've seen, mostly useful for quick existence checks/UI actions
    private val ids = ConcurrentHashMap<String, Boolean>()

    private val engine = utorr.Utorr.newEngine()

    private val listener = object : Listener {
        override fun onAdded(id: String?, name: String?) {
            if (id.isNullOrBlank()) return
            ids[id] = true
            // We don't update the list here; OnStatus is the source of truth and arrives every tick.
        }

        override fun onRemoved(id: String?) {
            if (id.isNullOrBlank()) return
            ids.remove(id)
        }

        override fun onStatus(list: StatusList?) {
            if (list == null) return

            val items = ArrayList<TorrentItem>(list.len().toInt())
            for (i in 0 until list.len()) {
                val s = list.get(i) ?: continue

                val id = s.id ?: continue
                ids[id] = true

                val name: String = s.name ?: "Unknown"

                items += TorrentItem(
                    id = id,
                    name = name,
                    progress = s.progress, // already 0..100 from Go
                    downloadSpeed = s.downloadBps,
                    uploadSpeed = s.uploadBps,
                    totalSize = s.totalSize,
                    downloadedSize = s.downloaded,
                    status = mapStatus(s.state),
                    peers = s.peers.toInt(),
                    seeds = s.seeds.toInt(), // Go engine may not supply seeds; keep 0 for now
                    filePath = buildFilePath(s.savePath, name)
                )
            }

            _torrents.value = items
        }

        override fun onError(msg: String?) {
            android.util.Log.e("TorrentManager", msg ?: "unknown engine error")
        }
    }

    init {
        if (!rootDir.exists()) rootDir.mkdirs()
        if (!sessionDir.exists()) sessionDir.mkdirs()
        android.util.Log.i("TorrentManager", "rootDir: ${rootDir.absolutePath}")
        android.util.Log.i("TorrentManager", "sessionDir: ${sessionDir.absolutePath}")
        android.util.Log.i("TorrentManager", "maxConns: $maxConns")


        // Start Go engine. It will restore torrents on startup and begin status ticks.
        engine.start(
            rootDir.absolutePath,
            sessionDir.absolutePath,
            maxConns.toLong(),
            listener,
            debug
        )
    }

    /**
     * Add magnet.
     */
    fun addMagnet(uri: String) {
        scope.launch {
            try {
                engine.addMagnet(uri)
            } catch (e: Exception) {
                android.util.Log.e("TorrentManager", "Error adding magnet", e)
            }
        }
    }

    /**
     * Add .torrent file.
     */
    fun addTorrentFile(file: File) {
        scope.launch {
            try {
                val bytes = file.readBytes()
                engine.addTorrentBytes(bytes)
            } catch (e: Exception) {
                android.util.Log.e("TorrentManager", "Error adding torrent file", e)
            }
        }
    }

    fun pauseTorrent(id: String) {
        engine.pause(id)
    }

    fun resumeTorrent(id: String) {
        engine.resume(id)
    }

    fun pauseAll() {
        engine.pauseAll()
    }

    fun resumeAll() {
        engine.resumeAll()
    }

    fun removeTorrent(id: String, deleteFiles: Boolean) {
        engine.remove(id, deleteFiles)
    }

    fun stop() {
        engine.stop()
        scope.cancel()
    }

    private fun mapStatus(state: String?): TorrentItem.Status =
        when (state?.uppercase()) {
            "PAUSED" -> TorrentItem.Status.PAUSED
            "CHECKING" -> TorrentItem.Status.CHECKING
            "DOWNLOADING" -> TorrentItem.Status.DOWNLOADING
            "FINISHED" -> TorrentItem.Status.FINISHED
            "SEEDING" -> TorrentItem.Status.FINISHED
            else -> TorrentItem.Status.QUEUED
        }

    private fun buildFilePath(savePath: String?, name: String): String {
        // Go engine uses rootDir as savePath base;
        val base = savePath?.takeIf { it.isNotBlank() } ?: rootDir.absolutePath
        val file = File(base, name)
        return file.absolutePath
    }
}