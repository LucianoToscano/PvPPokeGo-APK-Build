package com.lucianotoscano.pvppokego.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the same frames already captured by PvPPokeGo.
 *
 * This avoids opening a second MediaProjection session, which is what causes many
 * third-party screen recorders to revoke PvPPokeGo's capture permission.
 *
 * Video only (no microphone/game audio). Output is published to Movies/PvPPokeGo.
 */
class ScreenFrameRecorder(private val context: Context) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var codecSurface: Surface? = null
    private var tempFile: File? = null

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var textureId = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var samplerHandle = 0

    private var muxerStarted = false
    private var trackIndex = -1
    private var startedAtNs = 0L
    private var lastFrameAtNs = 0L
    private var outputWidth = 0
    private var outputHeight = 0
    private var encodedSampleCount = 0
    private var firstFrameSubmitted = false
    private var submittedFrameCount = 0
    private var inputWidth = 0
    private var inputHeight = 0
    private var conservativeProfile = false
    private var currentFrameIntervalNs = FRAME_INTERVAL_NS

    var isRecording: Boolean = false
        private set

    var lastSavedUri: Uri? = null
        private set

    var lastErrorMessage: String? = null
        private set

    val hasEncodedSample: Boolean
        get() = encodedSampleCount > 0

    val submittedFrames: Int
        get() = submittedFrameCount

    val activeProfileLabel: String
        get() = if (conservativeProfile) "compatível" else "padrão"

    val encoderStalled: Boolean
        get() = isRecording &&
            conservativeProfile &&
            encodedSampleCount == 0 &&
            submittedFrameCount >= CONSERVATIVE_STALL_FRAMES

    fun start(width: Int, height: Int): Boolean {
        if (isRecording) return true
        inputWidth = width
        inputHeight = height
        conservativeProfile = false
        return startProfile(width, height, conservative = false)
    }

    private fun startProfile(width: Int, height: Int, conservative: Boolean): Boolean {
        lastErrorMessage = null
        if (width < 2 || height < 2) {
            lastErrorMessage = "Dimensões de captura inválidas"
            return false
        }
        conservativeProfile = conservative
        val maxWidth = if (conservative) CONSERVATIVE_MAX_OUTPUT_WIDTH else MAX_OUTPUT_WIDTH
        val maxHeight = if (conservative) CONSERVATIVE_MAX_OUTPUT_HEIGHT else MAX_OUTPUT_HEIGHT
        val targetFps = if (conservative) CONSERVATIVE_FPS else TARGET_FPS
        currentFrameIntervalNs = 1_000_000_000L / targetFps.coerceAtLeast(1)

        val scale = minOf(
            1f,
            maxWidth.toFloat() / width.toFloat(),
            maxHeight.toFloat() / height.toFloat()
        )
        // Samsung/Qualcomm AVC encoders are usually more reliable with macroblock-aligned dimensions.
        fun align16(value: Int): Int = ((value.coerceAtLeast(16)) / 16) * 16
        outputWidth = align16((width * scale).toInt())
        outputHeight = align16((height * scale).toInt())
        tempFile = File(context.cacheDir, "pvppokego-recording-" + System.currentTimeMillis() + ".mp4")

        return runCatching {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                outputWidth,
                outputHeight
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                val bitrate = suggestedBitrate(outputWidth, outputHeight)
                setInteger(MediaFormat.KEY_BIT_RATE, if (conservative) (bitrate * .62f).toInt() else bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { encoder ->
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codecSurface = encoder.createInputSurface()
                encoder.start()
            }
            muxer = MediaMuxer(tempFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            setupEgl(codecSurface ?: error("Encoder surface unavailable"))
            setupGl()
            startedAtNs = System.nanoTime()
            lastFrameAtNs = 0L
            muxerStarted = false
            trackIndex = -1
            encodedSampleCount = 0
            submittedFrameCount = 0
            firstFrameSubmitted = false
            isRecording = true
            true
        }.getOrElse { error ->
            lastErrorMessage = "Falha ao iniciar encoder (" + activeProfileLabel + "): " +
                error.javaClass.simpleName + ": " + (error.message ?: "sem detalhe")
            releaseInternal(deleteTemp = true)
            false
        }
    }

    private fun retryWithConservativeProfile(): Boolean {
        if (conservativeProfile || inputWidth < 2 || inputHeight < 2) return false
        val reason = "Encoder padrão não produziu vídeo; tentando perfil compatível"
        releaseInternal(deleteTemp = true)
        val ok = startProfile(inputWidth, inputHeight, conservative = true)
        if (ok) lastErrorMessage = reason
        return ok
    }

    /**
     * Draws one captured Bitmap into the encoder surface.
     * Throttled so battle analysis retains priority.
     */
    fun recordFrame(bitmap: Bitmap, nowNs: Long = System.nanoTime()) {
        if (!isRecording || bitmap.isRecycled) return
        if (lastFrameAtNs != 0L && nowNs - lastFrameAtNs < currentFrameIntervalNs) return

        if (
            !conservativeProfile &&
            encodedSampleCount == 0 &&
            submittedFrameCount >= PRIMARY_RETRY_SUBMITTED_FRAMES &&
            nowNs - startedAtNs >= PRIMARY_RETRY_AFTER_NS
        ) {
            if (retryWithConservativeProfile()) {
                recordFrame(bitmap, System.nanoTime())
            }
            return
        }

        lastFrameAtNs = nowNs
        runCatching {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return@runCatching
            GLES20.glViewport(0, 0, outputWidth, outputHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

            VERTICES.position(0)
            TEX_COORDS.position(0)
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, VERTICES)
            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, TEX_COORDS)
            GLES20.glUniform1i(samplerHandle, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)

            EGLExt.eglPresentationTimeANDROID(
                eglDisplay,
                eglSurface,
                (nowNs - startedAtNs).coerceAtLeast(0L)
            )
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            submittedFrameCount++
            if (!firstFrameSubmitted) {
                firstFrameSubmitted = true
                runCatching {
                    codec?.setParameters(Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    })
                }
            }
            drainEncoder(endOfStream = false)
        }.onFailure { error ->
            lastErrorMessage = "Falha durante gravação: ${error.javaClass.simpleName}: ${error.message ?: "sem detalhe"}"
            runCatching { stop() }
        }
    }

    /** Stops recording and returns the MediaStore Uri when publishing succeeds. */
    fun stop(): Uri? {
        if (!isRecording) return lastSavedUri
        isRecording = false

        runCatching {
            codec?.signalEndOfInputStream()
            drainEncoder(endOfStream = true)
        }
        releaseEgl()

        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { if (muxerStarted) muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        codecSurface?.release()
        codecSurface = null

        val file = tempFile
        tempFile = null
        val validFile = muxerStarted && encodedSampleCount > 0 &&
            file != null && file.exists() && file.length() >= MIN_VALID_MP4_BYTES &&
            validateMp4(file)
        val published = file?.takeIf { validFile }?.let(::publishToMediaStore)
        if (published != null) {
            lastSavedUri = published
            lastErrorMessage = null
        } else if (lastErrorMessage == null) {
            lastErrorMessage = when {
                !muxerStarted -> "Encoder não produziu formato de vídeo"
                encodedSampleCount <= 0 -> "Nenhum quadro de vídeo foi codificado"
                else -> "MP4 incompleto; arquivo não foi publicado"
            }
        }
        runCatching { file?.delete() }
        muxerStarted = false
        trackIndex = -1
        return published
    }

    fun release() {
        if (isRecording) stop() else releaseInternal(deleteTemp = true)
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val encoder = codec ?: return
        val mediaMuxer = muxer ?: return
        val info = MediaCodec.BufferInfo()
        var idleCount = 0

        while (true) {
            val index = encoder.dequeueOutputBuffer(info, if (endOfStream) 20_000L else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idleCount > 100) break
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) error("Encoder output format changed twice")
                    trackIndex = mediaMuxer.addTrack(encoder.outputFormat)
                    mediaMuxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val encoded = encoder.getOutputBuffer(index)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && encoded != null && muxerStarted) {
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        mediaMuxer.writeSampleData(trackIndex, encoded, info)
                        encodedSampleCount++
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(index, false)
                    if (eos) break
                }
            }
        }
    }

    private fun setupEgl(surface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        require(eglDisplay != EGL14.EGL_NO_DISPLAY)
        val versions = IntArray(2)
        require(EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1))

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = IntArray(1)
        require(EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0))
        val config = configs[0] ?: error("No EGL config")

        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        require(eglContext != EGL14.EGL_NO_CONTEXT)

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            config,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        require(eglSurface != EGL14.EGL_NO_SURFACE)
        require(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext))
    }

    private fun setupGl() {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) error("GL program link failed: " + GLES20.glGetProgramInfoLog(program))

        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        samplerHandle = GLES20.glGetUniformLocation(program, "uTexture")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) error("Shader compile failed: " + GLES20.glGetShaderInfoLog(shader))
        return shader
    }

    private fun releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            if (program != 0) GLES20.glDeleteProgram(program)
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        program = 0
        textureId = 0
    }

    private fun releaseInternal(deleteTemp: Boolean) {
        isRecording = false
        releaseEgl()
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { if (muxerStarted) muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        codecSurface?.release()
        codecSurface = null
        if (deleteTemp) runCatching { tempFile?.delete() }
        tempFile = null
        muxerStarted = false
        trackIndex = -1
        encodedSampleCount = 0
        submittedFrameCount = 0
        firstFrameSubmitted = false
    }

    private fun validateMp4(file: File): Boolean = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?: 0
            durationMs > 0L && width > 0 && height > 0
        } finally {
            retriever.release()
        }
    }.getOrDefault(false)

    private fun publishToMediaStore(file: File): Uri? = runCatching {
        val resolver = context.contentResolver
        val displayName = "PvPPokeGo-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PvPPokeGo")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create MediaStore video")
        resolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: error("Unable to open MediaStore output")
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
    }.getOrNull()

    private fun suggestedBitrate(width: Int, height: Int): Int =
        (width.toLong() * height.toLong() * 2L).coerceIn(3_000_000L, 12_000_000L).toInt()

    companion object {
        private const val TARGET_FPS = 10
        private const val CONSERVATIVE_FPS = 8
        private const val FRAME_INTERVAL_NS = 100_000_000L
        private const val PRIMARY_RETRY_AFTER_NS = 2_200_000_000L
        private const val PRIMARY_RETRY_SUBMITTED_FRAMES = 12
        private const val CONSERVATIVE_STALL_FRAMES = 24
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val MAX_OUTPUT_WIDTH = 720
        private const val MAX_OUTPUT_HEIGHT = 1280
        private const val CONSERVATIVE_MAX_OUTPUT_WIDTH = 576
        private const val CONSERVATIVE_MAX_OUTPUT_HEIGHT = 1024
        private const val MIN_VALID_MP4_BYTES = 16_384L

        private val VERTICES: FloatBuffer = ByteBuffer
            .allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
                position(0)
            }

        private val TEX_COORDS: FloatBuffer = ByteBuffer
            .allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
                position(0)
            }

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
