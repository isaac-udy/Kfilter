package com.isaacudy.kfilter.processor

import android.media.*
import android.os.Build
import com.isaacudy.kfilter.rendering.InputSurface
import com.isaacudy.kfilter.rendering.OutputSurface
import android.util.Log
import com.isaacudy.kfilter.Kfilter
import com.isaacudy.kfilter.KfilterMediaFile
import java.io.File

private const val TAG = "KfilterVideoProcessor"
private const val VERBOSE = true

internal class KfilterVideoProcessor(val shader: Kfilter, val mediaFile: KfilterMediaFile, val pathOut: String) : KfilterProcessor.Delegate() {

    private val path = mediaFile.path
    private val tempPathOut = pathOut+".tmp"
    private val extractor: Extractor

    init {
        extractor = Extractor(path)
        if (!extractor.hasVideoTrack) {
            throw IllegalStateException("File at '$path' has no video track, cannot apply KfilterVideoProcessor")
        }
    }

    override fun execute() {
        Executor().execute()
    }

    private inner class Executor {
        val outputFormat: MediaFormat
        var encoder: MediaCodec
        val inputSurface: InputSurface

        val decoder: MediaCodec
        val outputSurface: OutputSurface

        val videoDuration: Long

        var videoProcessedTimestamp: Long = 0
        var audioProcessedTimestamp: Long = 0

        val muxer: MediaMuxer
        var muxerVideoTrackIndex: Int = -1
        var muxerAudioTrackIndex: Int = -1

        var videoOutputDone = false
        var audioOutputDone = false

        var muxerStarted = false

        val audioEncoder: MediaCodec?
        val audioDecoder: MediaCodec?

        var timeout = 10_000L

        val syncFrames = HashSet<Long>()

        init {
            outputFormat = getOutputVideoFormat()

            try {
                encoder = MediaCodec.createEncoderByType(outputFormat.getString(MediaFormat.KEY_MIME))
                encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            catch (e: MediaCodec.CodecException) {
                // By default the output format has an IFrame Interval of 0, as this prevents encoding failures on some hardware.
                // On some other devices, having an IFrame Interval of 0 will cause the encoder.configure to fail.
                // In the case that we fail to configure the encoder above, we will set the IFrame Interval to 1 and try again.
                // On the devices which IFrame value of 0 causes issues, it appears the video is clipped at the beginning and end,
                // and I haven't found a way to detect if this will occur before encoding begins. Worth exploration in the future.
                Log.d(TAG, "MediaCodec default config is not valid", e)
                outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                encoder = MediaCodec.createEncoderByType(outputFormat.getString(MediaFormat.KEY_MIME))
                encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }

            inputSurface = InputSurface(encoder.createInputSurface())
            inputSurface.makeCurrent()
            encoder.start()

            decoder = MediaCodec.createDecoderByType(extractor.videoMimeType)
            shader.resize(outputFormat.getInteger(MediaFormat.KEY_WIDTH), outputFormat.getInteger(MediaFormat.KEY_HEIGHT))
            outputSurface = OutputSurface(shader)
            decoder.configure(extractor.videoFormat, outputSurface.surface, null, 0)
            decoder.start()

            if (extractor.audioMimeType != null) {
                val outputAudioFormat = getOutputAudioFormat()
                audioEncoder = MediaCodec.createEncoderByType(outputAudioFormat.getString(MediaFormat.KEY_MIME))
                audioEncoder.configure(outputAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                audioEncoder.start()

                audioDecoder = MediaCodec.createDecoderByType(extractor.audioMimeType)
                audioDecoder.configure(extractor.audioFormat, null, null, 0)
                audioDecoder.start()
            }
            else {
                audioEncoder = null
                audioDecoder = null
            }

            File(tempPathOut).parentFile.mkdirs()
            muxer = MediaMuxer(tempPathOut, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            videoDuration = time.toLong()

            retriever.release()
        }

        fun execute() {
            var videoInputDone = false
            var audioInputDone = false
            timeout = 10_000L

            if (extractor.audioExtractor == null) {
                audioInputDone = true
                audioOutputDone = true
            }

            try {
                while (!videoOutputDone || !audioOutputDone) {
                    if (!videoOutputDone) {
                        if (!videoInputDone) {
                            videoInputDone = processDecoderInput()
                        }

                        var decoderOutputAvailable = true
                        var encoderOutputAvailable = true
                        while (decoderOutputAvailable || encoderOutputAvailable) {
                            encoderOutputAvailable = processEncoderOutput()
                            decoderOutputAvailable = processDecoderOutput()
                        }
                    }

                    val audioExtractor = extractor.audioExtractor
                    if (!audioOutputDone
                            && (!muxerStarted || videoOutputDone)
                            && audioExtractor != null
                            && audioDecoder != null
                            && audioEncoder != null) {
                        if (!audioInputDone) {
                            audioInputDone = processAudioDecoderInput(audioDecoder, audioExtractor)
                        }

                        var audioDecoderOutputAvailable = true
                        var audioEncoderOutputAvailable = true
                        while (audioDecoderOutputAvailable || audioEncoderOutputAvailable) {
                            audioEncoderOutputAvailable = processAudioEncoderOutput(audioEncoder, muxer)
                            audioDecoderOutputAvailable = processAudioDecoderOutput(audioDecoder, audioEncoder)
                        }
                    }
                    /**
                     *  !Shout out to Zoe for finding this bug!
                     *  When dequeuing buffers, a short timeout will greatly improve the speed of the
                     *  encode/decode process. However, a short timeout can cause misses grabbing
                     *  the buffer. These misses seem to prevent the INFO_OUTPUT_FORMAT_CHANGED from
                     *  appearing quickly. If the INFO_OUTPUT_FORMAT_CHANGED takes too long to appear
                     *  on both the audio and video encode streams, it seems that the muxer will not
                     *  onInitialise correctly, which causes the muxer to crash when we try to stop it.
                     *
                     *  Failing to stop the muxer correctly results in an application crash and prevents
                     *  the output video file from being played.
                     *
                     *  What this means is that we want a long timeout while we're waiting for the
                     *  INFO_OUTPUT_FORMAT_CHANGED event to occur, but we want to reduce the timeout
                     *  when we're actually starting to process the data.
                     *
                     *  Therefore, when the muxer starts (i.e. both the video and audio encoders have
                     *  received their INFO_OUTPUT_FORMAT_CHANGED events), we reduce the timeout value
                     *  from 10_000 (the default, 10ms) to 100 (0.1ms)
                     */
                    if (muxerStarted) {
                        timeout = 100
                    }
                }
            }
            catch (e: Exception) {
                onError(e)
            }
            finally {
                shader.release()

                outputSurface.release()
                inputSurface.release()

                try {
                    muxer.release()
                }
                catch (e: Exception) {
                    e.printStackTrace()
                }

                encoder.stop()
                encoder.release()
                decoder.stop()
                decoder.release()

                audioDecoder?.stop()
                audioDecoder?.release()
                audioEncoder?.stop()
                audioEncoder?.release()
            }
            onProgress(1.0f)
            try {
                File(tempPathOut).renameTo(File(pathOut))
            }
            catch (e: Exception){
                onError(e)
            }
            onSuccess()
        }

        fun processDecoderInput(): Boolean {
            val inputBufferIndex = decoder.dequeueInputBuffer(timeout)
            if (inputBufferIndex >= 0) {
                val buffer = decoder.getInputBuffer(inputBufferIndex)
                val sampleSize = extractor.videoExtractor.readSampleData(buffer, 0)

                if (sampleSize >= 0) {
                    val presentationTimeUs = extractor.videoExtractor.sampleTime
                    val flags = extractor.videoExtractor.sampleFlags
                    decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, flags)
                    extractor.videoExtractor.advance()

                    if (VERBOSE) Log.d(TAG, "Decoder read sample @$presentationTimeUs for size $sampleSize")
                }
                else {
                    if (VERBOSE) Log.d(TAG, "Decoder EOS Reached")
                    decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    return true
                }
            }
            else {
                if (VERBOSE) Log.d(TAG, "input buffer not available")
            }
            return false
        }

        fun processDecoderOutput(): Boolean {
            val bufferInfo = MediaCodec.BufferInfo()
            val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, timeout)
            if (outputBufferIndex >= 0) {
                if (VERBOSE) Log.d(TAG, "Got decoder output")
                val doRender = bufferInfo.size != 0
                decoder.releaseOutputBuffer(outputBufferIndex, doRender)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
                    syncFrames.add(bufferInfo.presentationTimeUs)
                }

                if (doRender) {
                    // This waits for the image and renders it after it arrives.
                    if (VERBOSE) Log.d(TAG, "awaiting frame")
                    outputSurface.awaitNewImage()
                    outputSurface.drawImage()
                    // Send it to the encoder.
                    inputSurface.setPresentationTime(bufferInfo.presentationTimeUs * 1000)
                    if (VERBOSE) Log.d(TAG, "swapBuffers")
                    inputSurface.swapBuffers()
                }
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    // forward decoder EOS to encoder
                    if (VERBOSE) Log.d(TAG, "signaling input EOS")
                    encoder.signalEndOfInputStream()
                }
            }
            else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = decoder.getOutputFormat()
                if (VERBOSE) Log.d(TAG, "Decoder output format has changed: " + newFormat)
            }
            else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (VERBOSE) Log.d(TAG, "Decoder says try again later: " + outputBufferIndex)
                return false
            }
            else {
                if (VERBOSE) Log.d(TAG, "Decoder unknown: " + outputBufferIndex)
            }
            return true
        }

        fun processEncoderOutput(): Boolean {
            val bufferInfo = MediaCodec.BufferInfo()
            val encoderOutputIndex = encoder.dequeueOutputBuffer(bufferInfo, timeout)
            if (encoderOutputIndex >= 0 && muxerStarted) {
                if (VERBOSE) Log.d(TAG, "Got encoder output")
                if (bufferInfo.size != 0) {
                    val encodedData = encoder.getOutputBuffer(encoderOutputIndex)

                    if (muxerVideoTrackIndex < 0) {
                        throw RuntimeException("muxer hasn't started")
                    }

//                    if(syncFrames.contains(bufferInfo.presentationTimeUs)){
//                        bufferInfo.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
//                    }

                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)

                    muxer.writeSampleData(muxerVideoTrackIndex, encodedData, bufferInfo)
                    if (VERBOSE) Log.d(TAG, "sent " + bufferInfo.size + " bytes to muxer")
                    videoProcessedTimestamp = (bufferInfo.presentationTimeUs / 1000)
                    val progress = (videoProcessedTimestamp + audioProcessedTimestamp / 2).toFloat() / (videoDuration.toFloat() * 1.5f)
                    onProgress(progress)
                }
                videoOutputDone = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                encoder.releaseOutputBuffer(encoderOutputIndex, false)
            }
            else if (encoderOutputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                if (muxerVideoTrackIndex >= 0) {
                    throw RuntimeException("Encoder format changed twice")
                }
                val newFormat = encoder.getOutputFormat()
                if (VERBOSE) Log.d(TAG, "Encoder output format changed: " + newFormat)

                // now that we have the Magic Goodies, start the muxer
                muxerVideoTrackIndex = muxer.addTrack(newFormat)
                if (muxerVideoTrackIndex >= 0 && (muxerAudioTrackIndex >= 0 || extractor.audioExtractor == null)) {
                    muxer.start()
                    muxerStarted = true
                }
            }
            else if (encoderOutputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (VERBOSE) Log.d(TAG, "Encoder says try again later: " + encoderOutputIndex)
                return false
            }
            else {
                if (VERBOSE) Log.d(TAG, "Failed to get encoder output: " + encoderOutputIndex)
            }
            return true
        }

        fun processAudioDecoderInput(decoder: MediaCodec, extractor: MediaExtractor): Boolean {
            val inputBufferIndex = decoder.dequeueInputBuffer(timeout)
            if (inputBufferIndex >= 0) {
                val buffer = decoder.getInputBuffer(inputBufferIndex)
                val sampleSize = extractor.readSampleData(buffer, 0)

                if (sampleSize >= 0) {
                    val presentationTimeUs = extractor.sampleTime
                    val flags = extractor.sampleFlags
                    decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, flags)
                    extractor.advance()

                    if (VERBOSE) Log.d(TAG, "Audio decoder read sample @$presentationTimeUs for size $sampleSize")
                }
                else {
                    if (VERBOSE) Log.d(TAG, "Audio decoder EOS Reached")
                    decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    return true
                }
            }
            else {
                if (VERBOSE) Log.d(TAG, "Audio input buffer not available")
            }
            return false
        }

        fun processAudioDecoderOutput(decoder: MediaCodec, encoder: MediaCodec): Boolean {
            val bufferInfo = MediaCodec.BufferInfo()
            val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, timeout)
            if (outputBufferIndex >= 0) {
                if (VERBOSE) Log.d(TAG, "Audio got decoder output")
                val doOutput = bufferInfo.size != 0

                if (doOutput) {
                    val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                    val inputBufferIndex = encoder.dequeueInputBuffer(timeout)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputBufferIndex)
                        inputBuffer.position(0)
                        inputBuffer.put(outputBuffer)
                        encoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                bufferInfo.size,
                                bufferInfo.presentationTimeUs,
                                bufferInfo.flags)
                    }
                    else {
                        Log.d(TAG, "Audio failed to get audio input buffer " + inputBufferIndex)
                    }
                }
                decoder.releaseOutputBuffer(outputBufferIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    // forward decoder EOS to encoder
                    if (VERBOSE) Log.d(TAG, "Audio signaling input EOS")
                    audioOutputDone = true
                }
            }
            else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = decoder.outputFormat
                if (VERBOSE) Log.d(TAG, "Audio decoder output format has changed: " + newFormat)
            }
            else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (VERBOSE) Log.d(TAG, "Audio decoder says try again later: " + outputBufferIndex)
                return false
            }
            else {
                if (VERBOSE) Log.d(TAG, "Audio decoder unknown: " + outputBufferIndex)
            }
            return true
        }

        fun processAudioEncoderOutput(encoder: MediaCodec, muxer: MediaMuxer): Boolean {
            val bufferInfo = MediaCodec.BufferInfo()
            val encoderOutputIndex = encoder.dequeueOutputBuffer(bufferInfo, timeout)
            if (encoderOutputIndex >= 0 && muxerStarted) {
                if (VERBOSE) Log.d(TAG, "Audio got encoder output")
                if (bufferInfo.size != 0) {
                    val encodedData = encoder.getOutputBuffer(encoderOutputIndex)

                    if (muxerAudioTrackIndex < 0) {
                        throw RuntimeException("Audio muxer hasn't started")
                    }

                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)

                    muxer.writeSampleData(muxerAudioTrackIndex, encodedData, bufferInfo)
                    if (VERBOSE) Log.d(TAG, "Audio sent " + bufferInfo.size + " bytes to muxer")

                    audioProcessedTimestamp = (bufferInfo.presentationTimeUs / 1000)
                    val progress = (videoProcessedTimestamp + audioProcessedTimestamp / 2).toFloat() / (videoDuration.toFloat() * 1.5f)
                    onProgress(progress)
                }
                encoder.releaseOutputBuffer(encoderOutputIndex, false)
            }
            else if (encoderOutputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                if (muxerAudioTrackIndex >= 0) {
                    throw RuntimeException("Audio encoder format changed twice")
                }
                val newFormat = encoder.outputFormat
                if (VERBOSE) Log.d(TAG, "Audio encoder output format changed: " + newFormat)

                // now that we have the Magic Goodies, start the muxer
                muxerAudioTrackIndex = muxer.addTrack(newFormat)
                if (muxerVideoTrackIndex >= 0 && muxerAudioTrackIndex >= 0) {
                    muxer.start()
                    muxerStarted = true
                }
            }
            else if (encoderOutputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (VERBOSE) Log.d(TAG, "Audio encoder says try again later: " + encoderOutputIndex)
                return false
            }
            else {
                if (VERBOSE) Log.d(TAG, "Audio failed to get encoder output: " + encoderOutputIndex)
            }
            return true
        }

    }

    fun getOutputVideoFormat(): MediaFormat {
        if (extractor.videoFormat == null) {
            throw IllegalStateException("File at '$path' has no video track, cannot create output format")
        }

        val mimeType = extractor.videoMimeType
        var width = -1
        var height = -1
        var frameRate = 30
        var bitrate = 10_000_000
        val colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface

        if (extractor.videoFormat.containsKey(MediaFormat.KEY_WIDTH)) {
            width = extractor.videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        }

        if (extractor.videoFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
            height = extractor.videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        }

        if(extractor.videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)){
            frameRate = extractor.videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
        }

        if(extractor.videoFormat.containsKey(MediaFormat.KEY_BIT_RATE)){
            bitrate = extractor.videoFormat.getInteger(MediaFormat.KEY_BIT_RATE)
        }

        val format = MediaFormat.createVideoFormat(mimeType, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)

        // prevent crash on some Samsung devices
        // http://stackoverflow.com/questions/21284874/illegal-state-exception-when-calling-mediacodec-configure?answertab=votes#tab-top
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height)
        format.setInteger(MediaFormat.KEY_MAX_WIDTH, width)
        format.setInteger(MediaFormat.KEY_MAX_HEIGHT, height)
        return format
    }

    fun getOutputAudioFormat(): MediaFormat {
        if (extractor.audioFormat == null) {
            throw IllegalStateException("File at '$path' has no audio track, cannot create output format")
        }
        val mimeTyoe = "audio/mp4a-latm"
        val aacProfile = 2
        val channelCount = extractor.audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val bitrate = 256_000
        val sampleRate = extractor.audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)

        val format = MediaFormat.createAudioFormat(mimeTyoe, sampleRate, channelCount)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bitrate)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, aacProfile)

        return format
    }

    internal inner class Extractor(val path: String) {
        val VIDEO_TRACK_TYPE = "video/"
        val AUDIO_TRACK_TYPE = "audio/"

        val videoExtractor: MediaExtractor
        var audioExtractor: MediaExtractor?
            private set

        val videoTrack: Int
        val audioTrack: Int

        val videoFormat: MediaFormat?
        val audioFormat: MediaFormat?

        val hasVideoTrack get() = videoTrack >= 0
        val hasAudioTrack get() = audioTrack >= 0

        val videoMimeType: String
            get() {
                if (videoFormat == null)
                    throw IllegalStateException("File at '$path' has no video track, cannot get mime type")
                return videoFormat.getString(MediaFormat.KEY_MIME)
            }

        val audioMimeType: String?
            get() {
                return audioFormat?.getString(MediaFormat.KEY_MIME)
            }

        init {
            videoExtractor = MediaExtractor().apply {
                setDataSource(path)
            }

            audioExtractor = MediaExtractor().apply {
                setDataSource(path)
            }

            videoTrack = getTrack(VIDEO_TRACK_TYPE)
            audioTrack = getTrack(AUDIO_TRACK_TYPE)
            if (hasVideoTrack) {
                videoFormat = videoExtractor.getTrackFormat(videoTrack).apply {
                    setInteger(MediaFormat.KEY_WIDTH, mediaFile.mediaWidth)
                    setInteger(MediaFormat.KEY_HEIGHT, mediaFile.mediaHeight)
                }
            }
            else {
                videoFormat = null
            }

            audioFormat = if (hasAudioTrack) {
                videoExtractor.getTrackFormat(audioTrack)
            }
            else {
                null
            }

            videoExtractor.selectTrack(videoTrack)
            videoExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            if (audioTrack >= 0) {
                audioExtractor?.apply {
                    selectTrack(audioTrack)
                    seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                }
            }
            else {
                audioExtractor = null
            }
        }

        fun getTrack(type: String): Int {
            val numTracks = videoExtractor.trackCount
            for (i in 0..numTracks) {
                try {
                    val trackFormat = videoExtractor.getTrackFormat(i)
                    val mimeType = trackFormat.getString(MediaFormat.KEY_MIME)

                    if (mimeType.startsWith(type)) {
                        return i
                    }
                }
                catch (ex: Exception) {
                    // Ignore errors related to grabbing track format
                }
            }
            return -1
        }
    }
}