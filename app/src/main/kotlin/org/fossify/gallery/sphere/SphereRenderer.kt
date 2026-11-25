package org.fossify.gallery.sphere

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Vykreslenie panorámy zvnútra UV gule (OpenGL ES 2.0).
 * Pohľad: vektory doprava `r`, hore `u`, dopredu `f` v súradniciach panorámy (x doprava, y hore, z dopredu;
 * stred obrázka = +z, doprava v obrázku = +x). Očné súradnice: ex = r·p, ey = u·p, ez = −f·p, takže „doprava“
 * ostáva doprava a obraz nie je zrkadlený.
 */
class SphereRenderer(private val listener: Listener) : GLSurfaceView.Renderer {

    interface Listener {
        /** Z vlákna GL: kontext je pripravený (aj po strate kontextu); textúra v ňom ešte nie je. */
        fun onGlReady(maxTextureSize: Int)

        fun onTextureUploaded()

        fun onTextureFailed()
    }

    private val lock = Any()
    private var pendingBitmap: Bitmap? = null
    private var region = SphereRegion.FULL
    private var regionDirty = true

    // pohľad (r, u, f) a zorné pole pozdĺž kratšej strany
    private val view = FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f }
    private var fovShort = 75f

    private var program = 0
    private var aPos = 0
    private var aUv = 0
    private var uMvp = 0
    private var uTex = 0
    private var texId = 0
    private var width = 1
    private var height = 1

    private var vertices: FloatBuffer? = null
    private var indices: ShortBuffer? = null
    private var indexCount = 0

    private val proj = FloatArray(16)
    private val viewM = FloatArray(16)
    private val mvp = FloatArray(16)

    fun setRegion(r: SphereRegion) = synchronized(lock) {
        region = r
        regionDirty = true
    }

    fun setBitmap(b: Bitmap) = synchronized(lock) {
        pendingBitmap?.recycle()
        pendingBitmap = b
    }

    fun setView(r: FloatArray, u: FloatArray, f: FloatArray, fovShortDeg: Float) = synchronized(lock) {
        for (i in 0..2) {
            view[i] = r[i]; view[3 + i] = u[i]; view[6 + i] = f[i]
        }
        fovShort = fovShortDeg
    }

    /** Uvoľní čakajúci obraz (volá sa z hlavného vlákna pri zničení). */
    fun releasePending() = synchronized(lock) {
        pendingBitmap?.recycle()
        pendingBitmap = null
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        texId = 0
        program = buildProgram(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        regionDirty = true
        val max = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, max, 0)
        listener.onGlReady(if (max[0] > 0) max[0] else 2048)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val bmp: Bitmap?
        val reg: SphereRegion
        val rebuild: Boolean
        val fov: Float
        synchronized(lock) {
            bmp = pendingBitmap
            pendingBitmap = null
            reg = region
            rebuild = regionDirty
            regionDirty = false
            fov = fovShort
            Matrix.setIdentityM(viewM, 0)
            viewM[0] = view[0]; viewM[4] = view[1]; viewM[8] = view[2]
            viewM[1] = view[3]; viewM[5] = view[4]; viewM[9] = view[5]
            viewM[2] = -view[6]; viewM[6] = -view[7]; viewM[10] = -view[8]
        }
        if (rebuild || vertices == null) buildMesh(reg)
        if (bmp != null) uploadTexture(bmp)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (texId == 0 || indexCount == 0) return

        val near = 0.1f
        val half = near * tan(Math.toRadians(fov / 2.0)).toFloat()
        val halfW: Float
        val halfH: Float
        if (width <= height) {
            halfW = half; halfH = half * height / width
        } else {
            halfH = half; halfW = half * width / height
        }
        Matrix.frustumM(proj, 0, -halfW, halfW, -halfH, halfH, near, 10f)
        Matrix.multiplyMM(mvp, 0, proj, 0, viewM, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(uTex, 0)
        val vb = vertices!!
        vb.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, vb)
        GLES20.glEnableVertexAttribArray(aPos)
        vb.position(3)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 20, vb)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indices)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
    }

    private fun uploadTexture(bmp: Bitmap) {
        try {
            if (texId != 0) GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texId = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            val pot = isPot(bmp.width) && isPot(bmp.height)
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                if (pot) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR
            )
            GLES20.glGetError() // zahodí staré chyby
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            if (pot) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            val err = GLES20.glGetError()
            if (err != GLES20.GL_NO_ERROR) {
                android.util.Log.e(TAG, "texImage2D zlyhalo, GL chyba 0x" + Integer.toHexString(err))
                GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
                texId = 0
                listener.onTextureFailed()
            } else {
                listener.onTextureUploaded()
            }
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "nahratie textúry", e)
            texId = 0
            listener.onTextureFailed()
        } finally {
            bmp.recycle()
        }
    }

    private fun isPot(v: Int) = v > 0 && (v and (v - 1)) == 0

    /** Guľa s dvojitým stĺpcom vrcholov na šve (u = 0 aj u = 1), aby šev nemal čiaru. */
    private fun buildMesh(r: SphereRegion) {
        val lonRange = (r.lonEnd - r.lonStart).coerceAtLeast(1.0)
        val latRange = (r.latTop - r.latBottom).coerceAtLeast(1.0)
        val cols = ceil(lonRange / 3.0).toInt().coerceIn(8, 180)
        val rows = ceil(latRange / 3.0).toInt().coerceIn(8, 90)
        val vArr = FloatArray((cols + 1) * (rows + 1) * 5)
        var k = 0
        for (j in 0..rows) {
            val v = j.toDouble() / rows
            val lat = Math.toRadians(r.latTop - v * latRange)
            val cl = cos(lat).toFloat()
            val sl = sin(lat).toFloat()
            for (i in 0..cols) {
                val u = i.toDouble() / cols
                val lon = Math.toRadians(r.lonStart + u * lonRange)
                vArr[k++] = cl * sin(lon).toFloat()
                vArr[k++] = sl
                vArr[k++] = cl * cos(lon).toFloat()
                vArr[k++] = u.toFloat()
                vArr[k++] = v.toFloat()
            }
        }
        val iArr = ShortArray(cols * rows * 6)
        k = 0
        for (j in 0 until rows) for (i in 0 until cols) {
            val a = (j * (cols + 1) + i).toShort()
            val b = (a + 1).toShort()
            val c = ((j + 1) * (cols + 1) + i).toShort()
            val d = (c + 1).toShort()
            iArr[k++] = a; iArr[k++] = c; iArr[k++] = b
            iArr[k++] = b; iArr[k++] = c; iArr[k++] = d
        }
        vertices = ByteBuffer.allocateDirect(vArr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .also { it.put(vArr); it.position(0) }
        indices = ByteBuffer.allocateDirect(iArr.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            .also { it.put(iArr); it.position(0) }
        indexCount = iArr.size
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) android.util.Log.e(TAG, "linkovanie: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) android.util.Log.e(TAG, "shader: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    private companion object {
        const val TAG = "SphereRenderer"
        const val VERTEX = "attribute vec3 aPos;attribute vec2 aUv;uniform mat4 uMvp;varying vec2 vUv;" +
            "void main(){vUv=aUv;gl_Position=uMvp*vec4(aPos,1.0);}"
        const val FRAGMENT = "precision mediump float;uniform sampler2D uTex;varying vec2 vUv;" +
            "void main(){gl_FragColor=texture2D(uTex,vUv);}"
    }
}
