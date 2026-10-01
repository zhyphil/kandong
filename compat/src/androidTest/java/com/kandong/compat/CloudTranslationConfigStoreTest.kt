package com.kandong.compat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.security.KeyStore

/** Synthetic config only; no network, permissions, or production credential access. */
@RunWith(AndroidJUnit4::class)
class CloudTranslationConfigStoreTest {
    @Test fun importsOnceEncryptsAndSurvivesStoreRecreation() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.noBackupFilesDir,"cloud-config-test").apply { mkdirs() }
        val alias="kandong.translation.cloud.test"
        val token="b".repeat(64)
        try {
            val staging=File(directory,"cloud-config.pending")
            staging.writeText(JSONObject().put("origin","https://kandong-translation-pilot.test.workers.dev")
                .put("token",token).put("expiresAt",System.currentTimeMillis()+60000).toString())
            val first=CloudTranslationConfigStore(context,directory,alias).load()
            assertEquals(token,first.token); assertFalse(staging.exists())
            assertFalse(String(File(directory,"cloud-config.enc").readBytes(),Charsets.ISO_8859_1).contains(token))
            assertEquals(token,CloudTranslationConfigStore(context,directory,alias).load().token)
            File(directory,"cloud-config.enc").writeBytes(byteArrayOf(1,2,3))
            assertEquals("CLOUD_NOT_CONFIGURED",assertThrows(IllegalStateException::class.java) {
                CloudTranslationConfigStore(context,directory,alias).load()
            }.message)
        } finally {
            directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
