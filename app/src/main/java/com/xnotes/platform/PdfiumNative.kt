package com.xnotes.platform

/** The JNI surface over the vendored PDFium (see cpp/pdf_jni.cpp). All but [revision] run on [PdfiumThread]. */
object PdfiumNative {
    val loaded: Boolean = runCatching { System.loadLibrary("xnotespdf") }.isSuccess

    /** The pinned PDFium revision; it reads no PDFium state, so any thread may ask. */
    val revision: String by lazy { if (loaded) nativeRevision() else "not loaded" }

    @JvmStatic external fun nativeRevision(): String

    @JvmStatic external fun nativeInit()

    /** Opens the PDF at [path]: a handle, or 0. [out] receives the FPDF_ERR_* code, then the page count. */
    @JvmStatic external fun nativeOpen(path: String, out: IntArray): Long

    @JvmStatic external fun nativeClose(doc: Long)

    /** Each page's width and height in points, as displayed (its /Rotate applied); 0 for a broken page. */
    @JvmStatic external fun nativePageSizes(doc: Long): FloatArray?
}
