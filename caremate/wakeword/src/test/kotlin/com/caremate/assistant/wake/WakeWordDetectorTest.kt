package com.caremate.assistant.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WakeWordDetectorTest {
    private val dir = File(System.getProperty("models.dir"))
    private fun model(name: String) = File(dir, name).readBytes()

    private fun pcm(name: String): ShortArray {
        val bytes = javaClass.getResourceAsStream("/$name")!!.readBytes()
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(sb.remaining()).also { sb.get(it) }
    }

    /** Streams [clip] through a fresh detector 80 ms at a time and returns the peak score. */
    private fun peak(wake: String, clip: String): Float {
        WakeWordDetector(model("melspectrogram.onnx"), model("embedding_model.onnx"), model(wake)).use { d ->
            val x = pcm(clip)
            var best = 0f
            var i = 0
            while (i + WakeWordDetector.CHUNK <= x.size) {
                best = maxOf(best, d.process(x.copyOfRange(i, i + WakeWordDetector.CHUNK)))
                i += WakeWordDetector.CHUNK
            }
            return best
        }
    }

    @Test fun detectsHeyJarvis() {
        val s = peak("hey_jarvis_v0.1.onnx", "hey_jarvis.pcm")
        println("hey_jarvis on 'Hey Jarvis' = $s")
        assertTrue("expected detection, got $s", s > 0.5f)
    }

    @Test fun detectsAlexa() {
        val s = peak("alexa_v0.1.onnx", "alexa.pcm")
        println("alexa on 'Alexa' = $s")
        assertTrue("expected detection, got $s", s > 0.5f)
    }

    @Test fun ignoresOtherSpeechAndOtherWakeWords() {
        for ((wake, clip) in listOf(
            "hey_jarvis_v0.1.onnx" to "other_speech.pcm", "alexa_v0.1.onnx" to "other_speech.pcm",
            "hey_jarvis_v0.1.onnx" to "alexa.pcm", "alexa_v0.1.onnx" to "hey_jarvis.pcm",
            "hey_mycroft_v0.1.onnx" to "other_speech.pcm",
        )) {
            val s = peak(wake, clip)
            println("$wake on $clip = $s")
            assertTrue("false wake: $wake on $clip = $s", s < 0.2f)
        }
    }

    /** Scores must match the reference Python openWakeWord, chunk by chunk. */
    @Test fun matchesReferenceImplementation() {
        val ref = javaClass.getResourceAsStream("/hey_jarvis.ref_scores.txt")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() }.map { it.trim().toFloat() }
        WakeWordDetector(model("melspectrogram.onnx"), model("embedding_model.onnx"), model("hey_jarvis_v0.1.onnx")).use { d ->
            val x = pcm("hey_jarvis.pcm")
            ref.forEachIndexed { n, expected ->
                val got = d.process(x.copyOfRange(n * WakeWordDetector.CHUNK, (n + 1) * WakeWordDetector.CHUNK))
                // The first 16 chunks differ only in warm-up padding; after that they must agree.
                if (n >= 16) assertEquals("chunk $n", expected, got, 2e-3f)
            }
        }
    }

    @Test fun silenceNeverWakes() {
        WakeWordDetector(model("melspectrogram.onnx"), model("embedding_model.onnx"), model("hey_jarvis_v0.1.onnx")).use { d ->
            val rnd = java.util.Random(1)
            repeat(150) { // 12 s of quiet background hiss
                val s = d.process(ShortArray(WakeWordDetector.CHUNK) { (rnd.nextGaussian() * 30).toInt().toShort() })
                assertTrue(s < 0.2f)
            }
        }
    }
}
