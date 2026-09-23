package com.kandong.qualitylab

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GpuLabTasksTest {
    @Test fun cancelledOldOwnerClosesBeforeReplacementCanPublish() {
        val entered = CountDownLatch(1)
        val releaseDriver = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val replacementStarted = CountDownLatch(1)
        val old = GpuLabTasks.submit {
            try {
                entered.countDown()
                // Model an in-flight driver operation which Java interruption cannot terminate.
                var waiting = true
                while (waiting) {
                    try { releaseDriver.await(); waiting = false }
                    catch (_: InterruptedException) { }
                }
            } finally { closed.set(true) }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        old.cancel(true)
        val replacement = GpuLabTasks.submit {
            replacementStarted.countDown()
            assertTrue("Old cleanup/report must precede the new owner", closed.get())
            assertFalse("New owner must not inherit cancellation", Thread.currentThread().isInterrupted)
        }
        try {
            assertFalse(replacementStarted.await(50, TimeUnit.MILLISECONDS))
        } finally { releaseDriver.countDown() }
        replacement.get(2, TimeUnit.SECONDS)
    }
}
