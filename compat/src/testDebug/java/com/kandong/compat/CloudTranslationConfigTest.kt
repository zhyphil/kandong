package com.kandong.compat

import org.junit.Assert.*
import org.junit.Test

class CloudTranslationConfigTest {
    private val origin="https://kandong-translation-pilot.example-account.workers.dev"
    private val token="a".repeat(64) // Synthetic, never issued.
    @Test fun onlyDedicatedHttpsOriginIsAccepted() {
        assertEquals(origin,CloudTranslationConfig.create(origin+":443",token,2000,1000).origin)
        listOf("http://kandong-translation-pilot.example-account.workers.dev","https://127.0.0.1","https://localhost",
            "https://api-free.deepl.com",origin+"/",origin+"/translate",origin+"?x=1",origin+"#x",origin+":8443",
            origin.replace("https://","https://user@"),origin+".evil.test",origin.replace("example-account","a.b"),
            origin.replace("example-account","-bad"),origin+"\n").forEach {
            assertThrows(IllegalArgumentException::class.java) { CloudTranslationConfig.create(it,token,2000,1000) }
        }
    }
    @Test fun credentialsMustBeRandomSizedAndUnexpired() {
        listOf("",token.drop(1),"g".repeat(64),token+"\n").forEach {
            assertThrows(IllegalArgumentException::class.java) { CloudTranslationConfig.create(origin,it,2000,1000) }
        }
        assertEquals("CREDENTIAL_EXPIRED",assertThrows(IllegalArgumentException::class.java) {
            CloudTranslationConfig.create(origin,token,1000,1000)
        }.message)
        assertThrows(IllegalArgumentException::class.java) { CloudTranslationConfig.create(origin,token,91L*86400000,1000) }
        assertFalse(CloudTranslationConfig.create(origin,token,2000,1000).toString().contains(token))
    }
}
