package dev.nymph.aireader.playback.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/**
 * Streaming AAC-LC/M4A encoder over the framework MediaCodec + MediaMuxer.
 *
 * Feed fixed-rate mono PCM with [writePcm] in any chunk sizes, then [finish]
 * to flush and close the container. Deliberately thin and Android-bound (no
 * JVM tests, like the service layer): all decisions about *what* PCM to feed
 * — chunk order, rates, resampling — live in the pure, tested export layer.
 *
 * Synchronous-mode codec loop; safe for a single caller thread. Failures
 * surface as [IOException] / [MediaCodec.CodecException]; the caller (the
 * export worker) maps them to its typed failure result. Call [release] on any
 * abandonment path — [finish] releases on success.
 */
class M4aEncoder(
    outFile: File,
    private val sampleRate: Int,
    bitRate: Int = BIT_RATE,
) {

    private companion object {
        const val BIT_RATE = 64_000 // mono speech; transparent for TTS output
        const val TIMEOUT_US = 10_000L
    }

    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false
    private var totalSamplesIn = 0L
    private var released = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: IOException) {
            codec.release()
            throw e
        } catch (e: IllegalStateException) {
            codec.release()
            throw e
        }
    }

    /** Queue [samples] (16-bit mono PCM at the constructor's rate) for encoding. */
    fun writePcm(samples: ShortArray) {
        check(!released) { "encoder already finished or released" }
        var offset = 0
        while (offset < samples.size) {
            val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                val input = requireNotNull(codec.getInputBuffer(inIndex))
                input.clear()
                val count = minOf(input.remaining() / 2, samples.size - offset)
                input.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, offset, count)
                codec.queueInputBuffer(inIndex, 0, count * 2, presentationTimeUs(), 0)
                totalSamplesIn += count
                offset += count
            }
            drain(untilEndOfStream = false)
        }
    }

    /** Flush the codec, close the container, and release everything. */
    fun finish() {
        check(!released) { "encoder already finished or released" }
        var inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        while (inIndex < 0) {
            drain(untilEndOfStream = false)
            inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        }
        codec.queueInputBuffer(inIndex, 0, 0, presentationTimeUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(untilEndOfStream = true)
        released = true
        codec.stop()
        codec.release()
        if (muxerStarted) muxer.stop()
        muxer.release()
    }

    /** Best-effort cleanup for abandonment paths; idempotent. */
    fun release() {
        if (released) return
        released = true
        try {
            codec.release()
        } catch (_: IllegalStateException) {
            // Already dead; nothing to reclaim.
        }
        try {
            muxer.release()
        } catch (_: IllegalStateException) {
            // Muxer never started or already released; the temp file is deleted by the caller.
        }
    }

    private fun presentationTimeUs(): Long = totalSamplesIn * 1_000_000L / sampleRate

    private fun drain(untilEndOfStream: Boolean) {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, if (untilEndOfStream) TIMEOUT_US else 0L)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEndOfStream) return
                    // Waiting for the end-of-stream flag to come back; keep draining.
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "encoder output format changed twice" }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    val encoded = requireNotNull(codec.getOutputBuffer(outIndex))
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && bufferInfo.size > 0) {
                        check(muxerStarted) { "encoded data before output format" }
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
