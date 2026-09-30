package com.xnotes.platform

/** The JNI surface over the vendored PDFium (see cpp/pdf_jni.cpp). */
object PdfiumNative {
    val loaded: Boolean = runCatching { System.loadLibrary("xnotespdf") }.isSuccess

    /** The pinned PDFium revision and whether a small document opens, for the debug HUD. */
    val selfTest: String by lazy { if (loaded) nativeSelfTest() else "not loaded" }

    @JvmStatic external fun nativeSelfTest(): String
}
