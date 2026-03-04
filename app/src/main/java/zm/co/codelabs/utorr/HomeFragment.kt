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
import zm.co.codelabs.utorr.databinding.FragmentHomeBinding
import java.io.File

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
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
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
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
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Remove Torrent")
                    .setMessage("Are you sure you want to remove '${item.name}'?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove") { _, _ ->
                        torrentService?.removeTorrent(item.id)
                    }
                    .show()
            },
            onOpen = { item ->
                item.filePath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        val folder = if (file.isDirectory) file else file.parentFile
                        folder?.let { f ->
                            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                setDataAndType(Uri.fromFile(f), "*/*")
                                addCategory(Intent.CATEGORY_OPENABLE)
                            }
                            try {
                                startActivity(intent)
                            } catch (e: Exception) {
                                // Fallback to ACTION_VIEW if ACTION_GET_CONTENT fails or if strict mode blocks file://
                                try {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        requireContext(),
                                        "${requireContext().packageName}.fileprovider",
                                        f
                                    )
                                    val fallback = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "vnd.android.document/directory")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    startActivity(fallback)
                                } catch (ex: Exception) {
                                    android.widget.Toast.makeText(requireContext(), "No file manager found", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } else {
                        android.widget.Toast.makeText(requireContext(), "Folder not found", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
        binding.recyclerView.adapter = adapter
    }

    private fun observeTorrents() {
        viewLifecycleOwner.lifecycleScope.launch {
            torrentService?.getTorrents()?.collectLatest { torrents ->
                adapter.submitList(torrents)
                binding.emptyView.visibility = if (torrents.isEmpty()) View.VISIBLE else View.GONE
                (activity as? MainActivity)?.updatePauseResumeAllMenu(torrents)
            }
        }
    }

    fun showAddTorrentDialog() {
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

    fun pauseAll() = torrentService?.pauseAll()
    fun resumeAll() = torrentService?.resumeAll()

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