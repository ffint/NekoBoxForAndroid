package io.nekohasekai.sagernet.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.smart.SmartGroupManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class SmartTestProgressDialog(
    private val context: Context,
    fullTest: Boolean,
) {
    private val view = LayoutInflater.from(context).inflate(R.layout.dialog_smart_test_progress, null)
    private val progress = view.findViewById<LinearProgressIndicator>(R.id.smart_test_progress)
    private val status = view.findViewById<TextView>(R.id.smart_test_status)
    private val detail = view.findViewById<TextView>(R.id.smart_test_detail)
    private val modeNote = view.findViewById<TextView>(R.id.smart_test_mode_note)
    private var lastStage: SmartGroupManager.TestStage? = null
    private var lastCompleted = -1

    private val dialog: AlertDialog = MaterialAlertDialogBuilder(context)
        .setTitle(if (fullTest) R.string.smart_full_test else R.string.smart_quick_test)
        .setView(view)
        .setCancelable(false)
        .create()

    init {
        modeNote.setText(
            if (fullTest) R.string.smart_full_test_note else R.string.smart_quick_test_note
        )
        status.setText(R.string.smart_test_preparing)
        detail.text = ""
        progress.isIndeterminate = true
    }

    fun show() = dialog.show()

    fun update(value: SmartGroupManager.TestProgress) {
        status.post {
            if (!dialog.isShowing) return@post
            if (lastStage == value.stage && value.completed < lastCompleted) return@post
            if (lastStage != value.stage) {
                lastStage = value.stage
                lastCompleted = -1
            }
            lastCompleted = value.completed

            when (value.stage) {
                SmartGroupManager.TestStage.LATENCY -> {
                    progress.isIndeterminate = false
                    progress.max = value.total.coerceAtLeast(1)
                    progress.setProgressCompat(value.completed, true)
                    status.text = context.getString(
                        R.string.smart_test_progress_latency,
                        value.completed,
                        value.total,
                    )
                    detail.text = if (value.profileName.isBlank()) "" else context.getString(
                        R.string.smart_test_recent_node,
                        value.profileName,
                    )
                }

                SmartGroupManager.TestStage.THROUGHPUT -> {
                    progress.isIndeterminate = false
                    progress.max = value.total.coerceAtLeast(1)
                    progress.setProgressCompat(value.completed, true)
                    status.text = context.getString(
                        R.string.smart_test_progress_throughput,
                        value.completed,
                        value.total,
                    )
                    detail.text = if (value.profileName.isBlank()) "" else context.getString(
                        R.string.smart_test_recent_node,
                        value.profileName,
                    )
                }

                SmartGroupManager.TestStage.SELECTING -> {
                    progress.isIndeterminate = true
                    status.setText(R.string.smart_test_selecting)
                    detail.text = ""
                }

                SmartGroupManager.TestStage.COMPLETE -> Unit
            }
        }
    }

    suspend fun complete(result: SmartGroupManager.GroupTestResult) {
        withContext(Dispatchers.Main.immediate) {
            progress.isIndeterminate = false
            progress.max = 1
            progress.setProgressCompat(1, true)
            val seconds = result.elapsedMs / 1000.0
            status.text = if (result.selectedProfileName.isNotBlank()) {
                context.getString(R.string.smart_test_done_node, result.selectedProfileName, seconds)
            } else {
                context.getString(R.string.smart_test_done, seconds)
            }
            detail.text = context.getString(
                R.string.smart_test_done_detail,
                result.testedNodeCount,
                result.throughputNodeCount,
            )
            delay(900)
            dialog.dismiss()
        }
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }
}

fun Fragment.launchSmartGroupTest(
    groupId: Long,
    fullTest: Boolean,
    onFinished: (Result<SmartGroupManager.GroupTestResult>) -> Unit,
): Job {
    val progressDialog = SmartTestProgressDialog(requireContext(), fullTest)
    progressDialog.show()

    val job = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
        try {
            val result = SmartGroupManager.testGroup(
                groupId = groupId,
                includeThroughput = true,
                fullThroughput = fullTest,
                forceSwitch = true,
                onProgress = progressDialog::update,
            )
            progressDialog.complete(result)
            withContext(Dispatchers.Main.immediate) {
                onFinished(Result.success(result))
            }
        } catch (_: CancellationException) {
            withContext(Dispatchers.Main.immediate) {
                progressDialog.dismiss()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main.immediate) {
                progressDialog.dismiss()
                onFinished(Result.failure(Exception(e.readableMessage, e)))
            }
        }
    }
    return job
}
