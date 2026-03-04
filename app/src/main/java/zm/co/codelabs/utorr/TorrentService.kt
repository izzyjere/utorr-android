package zm.co.codelabs.utorr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class TorrentService : Service() {

    private val binder = LocalBinder()
    private lateinit var torrentManager: TorrentManager
    private lateinit var settingsManager: SettingsManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isForeground = false

    inner class LocalBinder : Binder() {
        fun getService(): TorrentService = this@TorrentService
    }

    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(this)
        val saveDir = File(settingsManager.downloadPath)
        val maxConns = settingsManager.maxConns
        torrentManager = TorrentManager(this, rootDir = saveDir, maxConns = maxConns)

        serviceScope.launch {
            torrentManager.torrents.collect { list ->
                val hasDownloading = list.any { it.status == TorrentItem.Status.DOWNLOADING }
                if (hasDownloading && !isForeground) {
                    startForegroundService()
                } else if (!hasDownloading && isForeground) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    isForeground = false
                }
            }
        }
    }

    private fun startForegroundService() {
        isForeground = true
        val channelId = "torrent_channel"
        val channel = NotificationChannel(
            channelId, "Torrent Downloads",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Utorr is downloading")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun getTorrents(): StateFlow<List<TorrentItem>> = torrentManager.torrents

    fun addMagnet(uri: String) {
        torrentManager.addMagnet(uri)
    }

    fun addTorrentFile(file: java.io.File) {
        torrentManager.addTorrentFile(file)
    }

    fun pauseTorrent(id: String) = torrentManager.pauseTorrent(id)
    fun resumeTorrent(id: String) = torrentManager.resumeTorrent(id)
    fun pauseAll() = torrentManager.pauseAll()
    fun resumeAll() = torrentManager.resumeAll()
    fun removeTorrent(id: String) = torrentManager.removeTorrent(id, true)

    override fun onDestroy() {
        serviceScope.cancel()
        torrentManager.stop()
        super.onDestroy()
    }
}