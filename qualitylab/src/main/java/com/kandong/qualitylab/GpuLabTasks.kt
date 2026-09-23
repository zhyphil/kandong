package com.kandong.qualitylab

import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Process-wide serialization includes cancelled tasks' finally blocks across Activity instances. */
internal object GpuLabTasks {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "qualitylab-gpu-owner").apply { isDaemon = true }
    }
    fun submit(task: () -> Unit): Future<*> = worker.submit(task)
}
