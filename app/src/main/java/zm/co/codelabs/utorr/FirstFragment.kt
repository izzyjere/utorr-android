package zm.co.codelabs.utorr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import zm.co.codelabs.utorr.databinding.DialogAddTorrentBinding
import zm.co.codelabs.utorr.databinding.FragmentFirstBinding
import java.io.File

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private var torrentService: TorrentService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TorrentService.LocalBinder
            torrentService = binder.getService()
            isBound = true
            observeTorrents()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            torrentService = null
        }
    }

    private val selectTorrentFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val file = copyUriToFile(it)
            file?.let { torrentService?.addTorrentFile(it) }
        }
    }

    private lateinit var adapter: TorrentAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = TorrentAdapter(
            onPauseResume = { item ->
                if (item.status == TorrentItem.Status.PAUSED) torrentService?.resumeTorrent(item.id)
                else torrentService?.pauseTorrent(item.id)
            },
            onDelete = { item ->
                torrentService?.removeTorrent(item.id)
            }
        )
        binding.recyclerView.adapter = adapter

        requireActivity().findViewById<View>(R.id.fab).setOnClickListener {
            showAddTorrentDialog()
        }
    }

    private fun observeTorrents() {
        viewLifecycleOwner.lifecycleScope.launch {
            torrentService?.getTorrents()?.collectLatest { torrents ->
                adapter.submitList(torrents)
                binding.emptyView.visibility = if (torrents.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showAddTorrentDialog() {
        val dialogBinding = DialogAddTorrentBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnBrowseFile.setOnClickListener {
            selectTorrentFileLauncher.launch("application/x-bittorrent")
            dialog.dismiss()
        }

        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }

        dialogBinding.btnAdd.setOnClickListener {
            val link = dialogBinding.linkInput.text.toString()
            if (link.isNotBlank()) {
                torrentService?.addMagnet(link)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun copyUriToFile(uri: Uri): File? {
        return try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val file = File(requireContext().cacheDir, "temp.torrent")
            inputStream?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(requireContext(), TorrentService::class.java).also { intent ->
            requireActivity().startService(intent)
            requireActivity().bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            requireActivity().unbindService(connection)
            isBound = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}