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
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class TorrentService : Service() {

    private val binder = LocalBinder()
    private lateinit var torrentManager: TorrentManager
    private lateinit var settingsManager: SettingsManager

    inner class LocalBinder : Binder() {
        fun getService(): TorrentService = this@TorrentService
    }

    override fun onCreate() {
        super.onCreate()
        torrentManager = TorrentManager(this)
        settingsManager = SettingsManager(this)
        startForegroundService()
    }

    private fun startForegroundService() {
        val channelId = "torrent_channel"
        val channel = NotificationChannel(
            channelId, "Torrent Downloads",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Utorr is running")
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
        val saveDir = File(settingsManager.downloadPath)
        torrentManager.addMagnet(uri, saveDir)
    }

    fun addTorrentFile(file: java.io.File) {
        val saveDir = File(settingsManager.downloadPath)
        torrentManager.addTorrentFile(file, saveDir)
    }

    fun pauseTorrent(id: String) = torrentManager.pauseTorrent(id)
    fun resumeTorrent(id: String) = torrentManager.resumeTorrent(id)
    fun pauseAll() = torrentManager.pauseAll()
    fun resumeAll() = torrentManager.resumeAll()
    fun removeTorrent(id: String) = torrentManager.removeTorrent(id, true)

    override fun onDestroy() {
        torrentManager.stop()
        super.onDestroy()
    }
}