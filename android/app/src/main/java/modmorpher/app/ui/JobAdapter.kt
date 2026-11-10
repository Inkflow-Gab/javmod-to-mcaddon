package modmorpher.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import modmorpher.app.R
import modmorpher.app.data.JobRecord
import modmorpher.app.databinding.ItemJobBinding
import java.text.DateFormat
import java.util.Date

class JobAdapter(
    private val onSave: (JobRecord) -> Unit,
    private val onShare: (JobRecord) -> Unit,
    private val onNotes: (JobRecord) -> Unit,
    private val onDelete: (JobRecord) -> Unit,
) : ListAdapter<JobRecord, JobAdapter.Holder>(JOB_DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemJobBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemJobBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(job: JobRecord) {
            val context = binding.root.context
            binding.fileName.text = job.fileName
            binding.statusText.text = job.status.uppercase()
            binding.statusText.setTextColor(
                ContextCompat.getColor(context, statusColor(job.status))
            )

            binding.messageText.text = job.message.ifBlank { "Waiting…" }
            binding.messageText.visibility = View.VISIBLE

            val meta = StringBuilder()
            if (job.sizeBytes > 0) {
                meta.append(String.format("%.1f MB", job.sizeBytes / 1024.0 / 1024.0))
            }
            if (job.createdAt > 0) {
                if (meta.isNotEmpty()) meta.append("  ·  ")
                val stamp = DateFormat.getDateTimeInstance(
                    DateFormat.SHORT, DateFormat.SHORT
                ).format(Date(job.createdAt))
                meta.append(stamp)
            }
            binding.metaText.text = meta.toString()
            binding.metaText.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE

            val ready = job.hasAddon && job.isFinished
            binding.saveButton.isEnabled = ready
            binding.shareButton.isEnabled = ready
            binding.saveButton.alpha = if (ready) 1f else 0.4f
            binding.shareButton.alpha = if (ready) 1f else 0.4f

            binding.notesButton.visibility = if (job.hasNotes) View.VISIBLE else View.GONE

            binding.saveButton.setOnClickListener { onSave(job) }
            binding.shareButton.setOnClickListener { onShare(job) }
            binding.notesButton.setOnClickListener { onNotes(job) }
            binding.deleteButton.setOnClickListener { onDelete(job) }
        }

        private fun statusColor(status: String): Int = when (status) {
            "succeeded" -> R.color.status_success
            "failed" -> R.color.status_error
            "running" -> R.color.status_running
            else -> R.color.status_idle
        }
    }

}

private val JOB_DIFF = object : DiffUtil.ItemCallback<JobRecord>() {
    override fun areItemsTheSame(a: JobRecord, b: JobRecord) = a.id == b.id
    override fun areContentsTheSame(a: JobRecord, b: JobRecord) = a == b
}
