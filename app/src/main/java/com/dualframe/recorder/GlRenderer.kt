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

/**
 * Owns a GL thread fed by [surfaceTexture]. Every frame is drawn to the preview (if attached) and,
 * while encoding, to each encoder surface with its own crop. Works with no preview (app in background).
 */
class GlRenderer {
    private class Target(val surface: EGLSurface, val w: Int, val h: Int)

    private val thread = HandlerThread("gl").apply { start() }
    private val handler = Handler(thread.looper)

    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private lateinit var config: EGLConfig
    private lateinit var context: android.opengl.EGLContext
    private lateinit var pbuffer: EGLSurface // keeps the context current when there is no preview
    private var preview: EGLSurface? = null
    private var program = 0
    private var texId = 0
    private var uTex = 0; private var uMode = 0; private var uBox = 0; private var uDx = 0; private var uDy = 0
    private var aPos = 0; private var aTex = 0
    lateinit var surfaceTexture: SurfaceTexture
        private set

    private val stM = FloatArray(16)
    private val m = FloatArray(16)
    private var previewM = texMatrix(FULL)
    private var outputMs: List<FloatArray> = emptyList()
    private var targets: List<Target> = emptyList()
    private var blurMode = 0f
    private var blurBox = Crop(0f, 0f, 0f, 0f)
    private var blurRadiusPx = 0f
    private var minEncodeIntervalNs = 0L
    private var lastEncodedNs = 0L
    private var released = false

    private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // x, y, u, v as a triangle strip
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }

    init {
        runBlocking { setup() }
    }

    private fun runBlocking(block: () -> Unit) {
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        handler.post { try { block() } catch (t: Throwable) { error = t } finally { latch.countDown() } }
        latch.await()
        error?.let { throw it }
    }

    private fun setup() {
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
        ), 0, configs, 0, 1, n, 0)
        check(n[0] > 0) { "No recordable EGL config" }
        config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)

        program = buildProgram()
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uMode = GLES20.glGetUniformLocation(program, "uMode")
        uBox = GLES20.glGetUniformLocation(program, "uBox")
        uDx = GLES20.glGetUniformLocation(program, "uDx")
        uDy = GLES20.glGetUniformLocation(program, "uDy")
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
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

    /** Blocks so the caller may destroy the previous preview Surface right after. */
    fun attachPreview(s: Surface?) = runBlocking {
        preview?.let { EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context); EGL14.eglDestroySurface(display, it) }
        preview = s?.let { windowSurface(it) }
    }

    fun setCrops(previewCrop: Crop, outputs: List<Crop>, mirrorOutputs: Boolean) = handler.post {
        previewM = texMatrix(previewCrop)
        outputMs = outputs.map { texMatrix(it, mirrorOutputs) }
    }

    /** [mode]: 0 off, 1 inside [box], 2 whole frame, 3 outside [box]. [box] is in output UV, GL convention. */
    fun setBlur(mode: Int, box: Crop, radiusPx: Float) = handler.post {
        blurMode = mode.toFloat(); blurBox = box; blurRadiusPx = radiusPx
    }

    /** Encoder surfaces as (surface, width, height), in the same order as the output crops. */
    fun startEncoding(surfaces: List<Triple<Surface, Int, Int>>, fps: Int) = handler.post {
        minEncodeIntervalNs = 1_000_000_000L / fps * 9 / 10 // screen capture can deliver 120 fps; keep the chosen rate
        lastEncodedNs = 0L
        targets = surfaces.map { (s, w, h) -> Target(windowSurface(s), w, h) }
    }

    /** Stops drawing to the encoders; [done] runs on the GL thread after the last encoder frame. */
    fun stopEncoding(done: () -> Unit) = handler.post {
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        targets.forEach { EGL14.eglDestroySurface(display, it.surface) }
        targets = emptyList()
        done()
    }

    fun release() {
        runBlocking {
            released = true
            surfaceTexture.release()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            preview?.let { EGL14.eglDestroySurface(display, it) }
            targets.forEach { EGL14.eglDestroySurface(display, it.surface) }
            EGL14.eglDestroySurface(display, pbuffer)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        thread.quitSafely()
    }

    private fun drawFrame() {
        if (released) return
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(stM)
        preview?.let {
            val size = IntArray(2)
            EGL14.eglQuerySurface(display, it, EGL14.EGL_WIDTH, size, 0)
            EGL14.eglQuerySurface(display, it, EGL14.EGL_HEIGHT, size, 1)
            draw(it, previewM, size[0], size[1], null, blur = false)
        }
        val ts = surfaceTexture.timestamp
        if (targets.isEmpty() || ts - lastEncodedNs < minEncodeIntervalNs) return
        lastEncodedNs = ts
        targets.forEachIndexed { i, t -> draw(t.surface, outputMs.getOrElse(i) { previewM }, t.w, t.h, ts, blur = true) }
    }

    private fun draw(target: EGLSurface, cropM: FloatArray, w: Int, h: Int, ptsNs: Long?, blur: Boolean) {
        EGL14.eglMakeCurrent(display, target, target, context)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)
        Matrix.multiplyMM(m, 0, stM, 0, cropM, 0)
        GLES20.glUniformMatrix4fv(uTex, 1, false, m, 0)
        GLES20.glUniform1f(uMode, if (blur) blurMode else 0f)
        GLES20.glUniform4f(uBox, blurBox.x, blurBox.y, blurBox.w, blurBox.h)
        // one blur tap step in texture space: a quad-UV step through the same matrix
        val sx = blurRadiusPx / 3f / w; val sy = blurRadiusPx / 3f / h
        GLES20.glUniform2f(uDx, m[0] * sx, m[1] * sx)
        GLES20.glUniform2f(uDy, m[4] * sy, m[5] * sy)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
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
            attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uTex; varying vec2 vTex; varying vec2 vUv;
            void main() { gl_Position = aPos; vTex = (uTex * aTex).xy; vUv = aTex.xy; }
        """.trimIndent()))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vTex; varying vec2 vUv;
            uniform samplerExternalOES sTex;
            uniform float uMode; uniform vec4 uBox; uniform vec2 uDx; uniform vec2 uDy;
            void main() {
                bool inBox = vUv.x >= uBox.x && vUv.x <= uBox.x + uBox.z && vUv.y >= uBox.y && vUv.y <= uBox.y + uBox.w;
                bool blur = (uMode > 0.5 && uMode < 1.5 && inBox) || (uMode > 1.5 && uMode < 2.5) || (uMode > 2.5 && !inBox);
                if (!blur) { gl_FragColor = texture2D(sTex, vTex); return; }
                vec4 sum = vec4(0.0);
                for (int i = -3; i <= 3; i++)
                    for (int j = -3; j <= 3; j++)
                        sum += texture2D(sTex, vTex + float(i) * uDx + float(j) * uDy);
                gl_FragColor = sum / 49.0;
            }
        """.trimIndent()))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { GLES20.glGetProgramInfoLog(p) }
        return p
    }
}
