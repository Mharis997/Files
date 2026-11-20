package com.dualframe.recorder

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

/** Owns the GL thread: draws every camera frame to the preview and, while recording, to both encoder surfaces. */
class GlRenderer(previewSurface: Surface) {
    private val thread = HandlerThread("gl").apply { start() }
    private val handler = Handler(thread.looper)

    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private lateinit var config: EGLConfig
    private lateinit var context: android.opengl.EGLContext
    private lateinit var preview: EGLSurface
    private var program = 0
    private var texId = 0
    lateinit var surfaceTexture: SurfaceTexture
        private set

    private val stM = FloatArray(16)
    private val tmp = FloatArray(16)
    private var squareM = FloatArray(16)
    private var portraitM = FloatArray(16)
    private var landscapeM = FloatArray(16)
    private var encoders: Pair<EGLSurface, EGLSurface>? = null
    private var released = false

    private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // x, y, u, v as a triangle strip
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }

    init {
        runBlocking { setup(previewSurface) }
    }

    private fun runBlocking(block: () -> Unit) {
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        handler.post { try { block() } catch (t: Throwable) { error = t } finally { latch.countDown() } }
        latch.await()
        error?.let { throw it }
    }

    private fun setup(previewSurface: Surface) {
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGLExt.EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
        ), 0, configs, 0, 1, n, 0)
        check(n[0] > 0) { "No recordable EGL config" }
        config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        preview = windowSurface(previewSurface)
        EGL14.eglMakeCurrent(display, preview, preview, context)

        program = buildProgram()
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texId = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(texId).apply { setOnFrameAvailableListener({ drawFrame() }, handler) }
    }

    private fun windowSurface(s: Surface) =
        EGL14.eglCreateWindowSurface(display, config, s, intArrayOf(EGL14.EGL_NONE), 0)

    fun setCrops(square: Crop, portrait: Crop, landscape: Crop) = handler.post {
        squareM = texMatrix(square)
        portraitM = texMatrix(portrait)
        landscapeM = texMatrix(landscape)
    }

    fun startEncoding(portrait: Surface, landscape: Surface) = handler.post {
        encoders = windowSurface(portrait) to windowSurface(landscape)
    }

    /** Stops drawing to the encoders; [done] runs on the GL thread after the last encoder frame. */
    fun stopEncoding(done: () -> Unit) = handler.post {
        encoders?.let { (p, l) ->
            EGL14.eglMakeCurrent(display, preview, preview, context)
            EGL14.eglDestroySurface(display, p)
            EGL14.eglDestroySurface(display, l)
        }
        encoders = null
        done()
    }

    /** Blocks until GL resources are gone, so the preview Surface can be destroyed safely. */
    fun release() {
        runBlocking {
            released = true
            surfaceTexture.release()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, preview)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        thread.quitSafely()
    }

    private fun drawFrame() {
        if (released) return
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(stM)
        val size = IntArray(2)
        EGL14.eglQuerySurface(display, preview, EGL14.EGL_WIDTH, size, 0)
        EGL14.eglQuerySurface(display, preview, EGL14.EGL_HEIGHT, size, 1)
        draw(preview, squareM, size[0], size[1], null)
        encoders?.let { (p, l) ->
            val ts = surfaceTexture.timestamp
            draw(p, portraitM, 1080, 1920, ts)
            draw(l, landscapeM, 1920, 1080, ts)
        }
    }

    private fun draw(target: EGLSurface, cropM: FloatArray, w: Int, h: Int, ptsNs: Long?) {
        EGL14.eglMakeCurrent(display, target, target, context)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)
        Matrix.multiplyMM(tmp, 0, stM, 0, cropM, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, tmp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTex = GLES20.glGetAttribLocation(program, "aTex")
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (ptsNs != null) EGLExt.eglPresentationTimeANDROID(display, target, ptsNs)
        EGL14.eglSwapBuffers(display, target)
    }

    private fun buildProgram(): Int {
        fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { GLES20.glGetShaderInfoLog(it) }
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, """
            attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uTex; varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = (uTex * aTex).xy; }
        """.trimIndent()))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """.trimIndent()))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { GLES20.glGetProgramInfoLog(p) }
        return p
    }
}
