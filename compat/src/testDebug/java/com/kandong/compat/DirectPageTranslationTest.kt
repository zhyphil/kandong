package com.kandong.compat

import com.kandong.liveocr.OcrBlock
import com.kandong.liveocr.OcrPage
import org.junit.Assert.*
import org.junit.Test

class DirectPageTranslationTest {
    private fun block(id:String,text:String,left:Int=10)=OcrBlock(id,text,left,10,left+100,40,.99)
    private val page=OcrPage(listOf(block("inside","Check-in after 16:30"),block("outside","Breakfast is not included",800)),2)

    @Test fun oneStartAutomaticallyTranslatesTheWholeRecognizedPageForEitherLanguage() {
        for(lang in listOf("EN","FR")) {
            val steps=mutableListOf<String>()
            val result=DirectPageTranslation.run(lang,
                recognize={steps+="ocr"; page},
                translate={blocks,language ->
                    steps+="translate"; assertEquals(lang,language); assertEquals(page.blocks,blocks)
                    assertEquals(listOf("inside","outside"),blocks.map { it.id })
                    blocks.associate { it.id to "结果" }
                },current={true},onRecognized={steps+="recognized"},onSending={steps+="sending"})
            assertEquals(listOf("ocr","recognized","sending","translate"),steps)
            assertEquals(2,result.translations.size)
        }
    }

    @Test fun unreadableBlocksDoNotStopReadableContextOrGetSent() {
        val partial=page.copy(unreadable=listOf(block("cut","")))
        val result=DirectPageTranslation.run("FR",{partial},{blocks,_ ->
            assertEquals(page.blocks,blocks); blocks.associate { it.id to "结果" }
        },{true})
        assertEquals(partial.unreadable,result.page.unreadable)
        assertFalse(result.translations.containsKey("cut"))
    }

    @Test fun emptyPageNeverCallsTranslation() {
        val result=DirectPageTranslation.run("EN",{OcrPage(emptyList(),0)},{_,_ -> fail("No text to send"); emptyMap()},{true},onSending={fail("No send stage")})
        assertTrue(result.translations.isEmpty())
    }

    @Test fun sensitiveTextAnywhereOnPageStopsBeforeNetwork() {
        val privatePage=page.copy(blocks=page.blocks+block("offRegion","Verification code 123456",900))
        val e=assertThrows(IllegalStateException::class.java) {
            DirectPageTranslation.run("EN",{privatePage},{_,_ -> fail("Sensitive page sent"); emptyMap()},{true})
        }
        assertEquals("SENSITIVE_PAGE",e.message)
    }

    @Test fun cancelledOcrAndCancellationAtSendBoundaryCannotUpload() {
        for(atSend in listOf(false,true)) {
            var active=true
            assertThrows(IllegalStateException::class.java) {
                DirectPageTranslation.run("EN",{if(!atSend) active=false; page},
                    {_,_ -> fail("Cancelled page sent"); emptyMap()},{active},onSending={active=false})
            }
        }
    }

    @Test fun expiryOrCancellationDuringTranslationCannotPublish() {
        var active=true
        assertThrows(IllegalStateException::class.java) {
            DirectPageTranslation.run("FR",{page},{_,_ -> active=false; mapOf("inside" to "late")},{active})
        }
    }

    @Test fun providerFailureIsPropagatedOnceWithoutRetryOrLocalFallback() {
        var calls=0
        val e=assertThrows(IllegalStateException::class.java) {
            DirectPageTranslation.run("EN",{page},{_,_ -> calls++; error("RELAY_UNAVAILABLE")},{true})
        }
        assertEquals("RELAY_UNAVAILABLE",e.message); assertEquals(1,calls)
    }

    @Test fun unsupportedLanguagesAndStaleStartNeverReadPage() {
        assertThrows(IllegalArgumentException::class.java) {
            DirectPageTranslation.run("ZH-HANS",{fail("Unexpected OCR"); page},{_,_ -> emptyMap()},{true})
        }
        assertThrows(IllegalStateException::class.java) {
            DirectPageTranslation.run("EN",{fail("Unexpected OCR"); page},{_,_ -> emptyMap()},{false})
        }
    }
}
