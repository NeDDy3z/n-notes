package com.xnotes.gl

import android.opengl.GLES30
import android.util.Log

/** Thrown when a shader will not compile or link, so the caller can fall back rather than crash. */
class GlShaderException(message: String) : Exception(message)

/**
 * A compiled and linked GLES program plus its uniform locations. Every GL object dies with its EGL
 * context, so a program records the [contextGen] it was built under and [GlRenderer] rebuilds
 * anything stale rather than binding a dangling name.
 */
class GlProgram private constructor(val id: Int, val contextGen: Int) {

    private val uniforms = HashMap<String, Int>()
    private val attribs = HashMap<String, Int>()

    fun use() = GLES30.glUseProgram(id)

    /** Cached `glGetUniformLocation`; -1 for a uniform the compiler optimized away. */
    fun uniform(name: String): Int = uniforms.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }

    fun attrib(name: String): Int = attribs.getOrPut(name) { GLES30.glGetAttribLocation(id, name) }

    fun set(name: String, v: Float) = GLES30.glUniform1f(uniform(name), v)
    fun set(name: String, v: Int) = GLES30.glUniform1i(uniform(name), v)
    fun set(name: String, x: Float, y: Float) = GLES30.glUniform2f(uniform(name), x, y)
    fun set(name: String, x: Float, y: Float, z: Float, w: Float) =
        GLES30.glUniform4f(uniform(name), x, y, z, w)

    fun release() {
        if (id != 0) GLES30.glDeleteProgram(id)
    }

    companion object {
        private const val TAG = "xnotes.gl"

        /** Compile and link, or throw [GlShaderException] with the driver's log attached. */
        fun build(vertexSrc: String, fragmentSrc: String, contextGen: Int): GlProgram {
            val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSrc)
            val fs = try {
                compile(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
            } catch (e: GlShaderException) {
                GLES30.glDeleteShader(vs)
                throw e
            }
            val program = GLES30.glCreateProgram()
            if (program == 0) {
                GLES30.glDeleteShader(vs)
                GLES30.glDeleteShader(fs)
                throw GlShaderException("glCreateProgram failed")
            }
            GLES30.glAttachShader(program, vs)
            GLES30.glAttachShader(program, fs)
            GLES30.glLinkProgram(program)
            // The shaders are reference-counted by the program, so they can go once attached.
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(program)
                GLES30.glDeleteProgram(program)
                throw GlShaderException("link failed: $log")
            }
            return GlProgram(program, contextGen)
        }

        private fun compile(type: Int, src: String): Int {
            val shader = GLES30.glCreateShader(type)
            if (shader == 0) throw GlShaderException("glCreateShader failed")
            GLES30.glShaderSource(shader, src)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                throw GlShaderException("compile failed: $log")
            }
            return shader
        }

        /** Log any pending GL error under [where]; cheap enough to leave on debug paths only. */
        fun checkError(where: String) {
            var err = GLES30.glGetError()
            while (err != GLES30.GL_NO_ERROR) {
                Log.w(TAG, "GL error 0x${Integer.toHexString(err)} at $where")
                err = GLES30.glGetError()
            }
        }
    }
}
