package com.caremate.assistant.wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * On-device wake-word detection with openWakeWord models (https://github.com/dscripka/openWakeWord).
 *
 * Pipeline, identical to the reference Python implementation:
 *   16 kHz audio --melspectrogram.onnx--> 32-bin mel frames (x/10 + 2)
 *   last 76 mel frames --embedding_model.onnx--> one 96-d embedding per 80 ms chunk
 *   last 16 embeddings --<wake word>.onnx--> score 0..1
 *
 * Pure Kotlin + ONNX Runtime (no Android APIs), so it is unit-tested on the JVM.
 */
class WakeWordDetector(
    melModel: ByteArray,
    embeddingModel: ByteArray,
    wakeModel: ByteArray,
) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1) // tiny models; one thread keeps battery use low
        setInterOpNumThreads(1)
    }
    private val mel = env.createSession(melModel, opts)
    private val emb = env.createSession(embeddingModel, opts)
    private val wake = env.createSession(wakeModel, opts)
    private val wakeFrames: Int = (wake.inputInfo.values.first().info as ai.onnxruntime.TensorInfo).shape[1].toInt()
        .let { if (it > 0) it else 16 }

    private var rawTail = ShortArray(0)
    private val melBuf = ArrayDeque<FloatArray>().apply { repeat(EMB_WINDOW) { addLast(FloatArray(MEL_BINS) { 1f }) } }
    private val feats = ArrayDeque<FloatArray>()
    private var chunks = 0

    /** Feed exactly [CHUNK] samples (80 ms of 16 kHz mono PCM). Returns the wake score 0..1. */
    fun process(chunk: ShortArray): Float {
        require(chunk.size == CHUNK) { "chunk must be $CHUNK samples" }
        // Mel spectrogram over this chunk plus 3 hops of history, as the reference does.
        val keep = CHUNK + 160 * 3
        rawTail = (rawTail + chunk).let { if (it.size > keep) it.copyOfRange(it.size - keep, it.size) else it }
        val spec = run(mel, FloatArray(rawTail.size) { rawTail[it].toFloat() }, longArrayOf(1, rawTail.size.toLong()))
        for (f in 0 until spec.size / MEL_BINS) {
            melBuf.addLast(FloatArray(MEL_BINS) { spec[f * MEL_BINS + it] / 10f + 2f })
        }
        while (melBuf.size > MEL_MAX) melBuf.removeFirst()

        // One embedding from the latest 76 mel frames.
        val window = FloatArray(EMB_WINDOW * MEL_BINS)
        val start = melBuf.size - EMB_WINDOW
        for (r in 0 until EMB_WINDOW) melBuf[start + r].copyInto(window, r * MEL_BINS)
        feats.addLast(run(emb, window, longArrayOf(1, EMB_WINDOW.toLong(), MEL_BINS.toLong(), 1)))
        while (feats.size > FEAT_MAX) feats.removeFirst()

        chunks++
        if (feats.size < wakeFrames || chunks < WARMUP) return 0f
        val dim = feats.first().size
        val input = FloatArray(wakeFrames * dim)
        val fStart = feats.size - wakeFrames
        for (r in 0 until wakeFrames) feats[fStart + r].copyInto(input, r * dim)
        return run(wake, input, longArrayOf(1, wakeFrames.toLong(), dim.toLong())).first()
    }

    /** Forget recent audio, e.g. after a detection so one phrase doesn't trigger twice. */
    fun reset() {
        rawTail = ShortArray(0)
        feats.clear()
        chunks = 0
    }

    private fun run(s: OrtSession, data: FloatArray, shape: LongArray): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { r ->
                val fb = (r.get(0) as OnnxTensor).floatBuffer
                return FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
    }

    override fun close() {
        mel.close(); emb.close(); wake.close(); opts.close()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK = 1280
        private const val MEL_BINS = 32
        private const val EMB_WINDOW = 76
        private const val MEL_MAX = 970
        private const val FEAT_MAX = 120
        private const val WARMUP = 5
    }
}
