package com.kandong.compat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Install-time run-as staging -> Keystore AES-GCM; never backed up, logged or bundled. */
internal class CloudTranslationConfigStore(context: Context,
    private val directory: File=context.noBackupFilesDir,
    private val alias: String="kandong.translation.cloud.v3") {
    private val staging get()=File(directory,"cloud-config.pending")
    private val encrypted get()=AtomicFile(File(directory,"cloud-config.enc"))
    @Synchronized fun load(): CloudTranslationConfig {
        try {
            if(staging.exists()) {
                val bytes=readPrivate(staging,4096)
                try {
                    parse(bytes) // Validate before replacing a working configuration.
                    val cipher=Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE,key())
                    require(cipher.iv.size==12) { "CLOUD_NOT_CONFIGURED" }
                    val sealed=byteArrayOf(1)+cipher.iv+cipher.doFinal(bytes)
                    val out=encrypted.startWrite()
                    try { out.write(sealed); encrypted.finishWrite(out) }
                    catch(e:Exception) { encrypted.failWrite(out); throw e }
                    finally { sealed.fill(0) }
                } finally { bytes.fill(0); check(staging.delete()) { "CLOUD_NOT_CONFIGURED" } }
            }
            val bytes=readPrivate(encrypted.baseFile,8192)
            try {
                require(bytes.size>29 && bytes[0]==1.toByte()) { "CLOUD_NOT_CONFIGURED" }
                val cipher=Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(1,13)))
                val plain=cipher.doFinal(bytes,13,bytes.size-13)
                try { return parse(plain) } finally { plain.fill(0) }
            } finally { bytes.fill(0) }
        } catch(e:Exception) {
            throw IllegalStateException(if(e.message=="CREDENTIAL_EXPIRED") "CREDENTIAL_EXPIRED" else "CLOUD_NOT_CONFIGURED")
        }
    }
    private fun readPrivate(file:File, max:Int):ByteArray {
        require(file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile==directory.canonicalFile && file.length() in 1..max.toLong()) { "CLOUD_NOT_CONFIGURED" }
        return file.inputStream().use { stream ->
            val out=java.io.ByteArrayOutputStream()
            val buffer=ByteArray(1024)
            while(true) {
                val count=stream.read(buffer); if(count<0) break
                require(out.size()+count<=max) { "CLOUD_NOT_CONFIGURED" }
                out.write(buffer,0,count)
            }
            buffer.fill(0); out.toByteArray()
        }
    }
    private fun parse(bytes:ByteArray):CloudTranslationConfig {
        val obj=JSONObject(String(bytes,Charsets.UTF_8))
        require(obj.keys().asSequence().toSet()==setOf("origin","token","expiresAt")) { "CLOUD_NOT_CONFIGURED" }
        return CloudTranslationConfig.create(obj.getString("origin"),obj.getString("token"),obj.getLong("expiresAt"))
    }
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
}
