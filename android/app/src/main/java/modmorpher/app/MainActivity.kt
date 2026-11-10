package modmorpher.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import modmorpher.app.data.JobRecord
import modmorpher.app.data.Prefs
import modmorpher.app.databinding.ActivityMainBinding
import modmorpher.app.ui.JobAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: JobAdapter
    private val viewModel: MainViewModel by viewModels()

    /** Set while the "save as" picker is open, so the result knows its job. */
    private var pendingSaveJobId: String? = null

    private val pickJar = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.addJar(uri)
    }

    private val createAddon = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val jobId = pendingSaveJobId
        pendingSaveJobId = null
        if (uri != null && jobId != null) viewModel.saveAddon(jobId, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = JobAdapter(
            onSave = { job -> startSave(job) },
            onShare = { job -> viewModel.shareAddon(job.id) },
            onNotes = { job -> showNotes(job) },
            onDelete = { job -> confirmDelete(job) },
        )
        binding.jobList.adapter = adapter

        binding.addJar.setOnClickListener {
            // Jars have no reliable MIME type, so accept anything and check
            // the extension after the user picks.
            pickJar.launch(arrayOf("*/*"))
        }

        observe()
    }

    override fun onResume() {
        super.onResume()
        val prefs = Prefs(this)
        // On-device mode needs nothing; only nag when server mode is incomplete.
        if (prefs.usesServer && !prefs.isConfigured) promptForServer()
        viewModel.reloadMode()
        viewModel.refresh()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.jobs.collect { jobs ->
                        adapter.submitList(jobs)
                        binding.emptyState.visibility =
                            if (jobs.isEmpty()) View.VISIBLE else View.GONE
                        binding.jobList.visibility =
                            if (jobs.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
                launch {
                    viewModel.busy.collect { busy ->
                        binding.addJar.isEnabled = !busy
                    }
                }
                launch {
                    viewModel.message.collect { text ->
                        if (!text.isNullOrBlank()) {
                            Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()
                            viewModel.clearMessage()
                        }
                    }
                }
                launch {
                    viewModel.shareUri.collect { uri ->
                        if (uri != null) {
                            shareAddon(uri)
                            viewModel.clearShare()
                        }
                    }
                }
            }
        }
    }

    private fun startSave(job: JobRecord) {
        pendingSaveJobId = job.id
        val name = "ModMorpher_${job.fileName.removeSuffix(".jar")}.mcaddon"
        createAddon.launch(name)
    }

    private fun shareAddon(uri: android.net.Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_addon)))
    }

    private fun showNotes(job: JobRecord) {
        viewModel.loadNotes(job.id) { text ->
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.porting_notes)
                .setMessage(text.ifBlank { getString(R.string.notes_empty) })
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun confirmDelete(job: JobRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_job)
            .setMessage(getString(R.string.delete_job_body, job.fileName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteJob(job.id) }
            .show()
    }

    private fun promptForServer() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setup_title)
            .setMessage(R.string.setup_body)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_refresh -> {
                viewModel.refresh()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
