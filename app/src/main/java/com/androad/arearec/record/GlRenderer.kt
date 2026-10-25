package com.androad.arearec.record

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 区域裁剪渲染器 —— 整个 App 的技术核心。
 *
 * 为什么必须过 GL：
 *   VirtualDisplay 的 width/height 是「把整屏内容缩放到这个尺寸」，不是裁剪。
 *   想把区域尺寸直接传进去，只会得到一个缩小的全屏画面。
 *   所以只能让 VirtualDisplay 输出全屏，再在 GL 里按 UV 子矩形采样，渲染到编码器的
 *   input surface 上 —— 编码器从一开始拿到的就是裁剪后的画面，没有任何多余像素。
 *
 * 坐标系约定（这里最容易搞错，写清楚）：
 *   SurfaceTexture 的纹理坐标是标准 OpenGL 的（原点在左下），而屏幕坐标原点在左上。
 *   所以  v = 1 - y / screenH。
 *   顶点 (-1,-1) 是画面左下角，对应 tex 的较小 v。
 */
class GlRenderer(
    private val outputSurface: Surface,
    private val outWidth: Int,
    private val outHeight: Int
) {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var aPosLoc = 0
    private var aTexLoc = 0
    private var uTexMatrixLoc = 0

    private var textureId = 0
    lateinit var surfaceTexture: SurfaceTexture
        private set
    private lateinit var inputSurface: Surface

    private val texMatrix = FloatArray(16)
    private val posBuf: FloatBuffer
    private val texBuf: FloatBuffer

    @Volatile
    private var released = false

    private var lastSrcW = -1
    private var lastSrcH = -1

    init {
        setupEgl()
        setupProgram()
        setupTexture()

        posBuf = ByteBuffer.allocateDirect(POS.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(POS); position(0) }

        texBuf = ByteBuffer.allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }
    }

    /** 交给 VirtualDisplay 当输出目标 */
    fun displaySurface(): Surface = inputSurface

    /**
     * 设置裁剪区域。
     * @param x,y,w,h 屏幕物理像素坐标
     * @param screenW,screenH 屏幕物理尺寸（= VirtualDisplay 尺寸）
     */
    fun setArea(x: Int, y: Int, w: Int, h: Int, screenW: Int, screenH: Int) {
        // SurfaceTexture 收的是全屏流；尺寸变了（旋转/换屏）要同步，否则会被裁掉
        if (screenW != lastSrcW || screenH != lastSrcH) {
            surfaceTexture.setDefaultBufferSize(screenW, screenH)
            lastSrcW = screenW
            lastSrcH = screenH
        }
        val u0 = x.toFloat() / screenW
        val u1 = (x + w).toFloat() / screenW
        // 屏幕 y 向下，纹理 v 向上，所以要翻
        val vTop = 1f - y.toFloat() / screenH
        val vBottom = 1f - (y + h).toFloat() / screenH

        texBuf.position(0)
        // 顺序与 POS 对应：左下、右下、左上、右上
        texBuf.put(u0); texBuf.put(vBottom)
        texBuf.put(u1); texBuf.put(vBottom)
        texBuf.put(u0); texBuf.put(vTop)
        texBuf.put(u1); texBuf.put(vTop)
        texBuf.position(0)
    }

    /** 渲染一帧到编码器。ptsNs 是这一帧的展示时间戳（纳秒） */
    fun drawFrame(ptsNs: Long) {
        if (released) return
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)

        GLES20.glViewport(0, 0, outWidth, outHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

        posBuf.position(0)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, posBuf)

        texBuf.position(0)
        GLES20.glEnableVertexAttribArray(aTexLoc)
        GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 0, texBuf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosLoc)
        GLES20.glDisableVertexAttribArray(aTexLoc)

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNs)
        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            Log.w(TAG, "eglSwapBuffers failed: ${EGL14.eglGetError()}")
        }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { inputSurface.release() }
        runCatching { surfaceTexture.release() }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    // ---------------------------------------------------------------- setup

    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0
        ) { "eglChooseConfig failed" }

        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, configs[0], outputSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }

        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent failed"
        }
    }

    private fun setupProgram() {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
            val status = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) {
                "link failed: " + GLES20.glGetProgramInfoLog(it)
            }
        }
        aPosLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexLoc = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "compile failed: " + GLES20.glGetShaderInfoLog(shader)
        }
        return shader
    }

    private fun setupTexture() {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setDefaultBufferSize(outWidth, outHeight)
        inputSurface = Surface(surfaceTexture)
    }

    companion object {
        private const val TAG = "GlRenderer"

        // 左下、右下、左上、右上（TRIANGLE_STRIP 顺序）
        private val POS = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )

        private const val VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uTexMatrix * aTextureCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """
    }
}
