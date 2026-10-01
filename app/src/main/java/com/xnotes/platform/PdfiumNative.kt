package com.xnotes.platform

import android.graphics.Bitmap

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

    /** Width and height in points, as displayed (/Rotate applied), of [count] pages from [from]; 0 when broken. */
    @JvmStatic external fun nativePageSizes(doc: Long, from: Int, count: Int): FloatArray?

    /** The boxes (l, t, r, b), in points as displayed, of the images on page [index], those in forms included. */
    @JvmStatic external fun nativeImageRects(doc: Long, index: Int): FloatArray?

    /** Page [index]'s link boxes (l, t, r, b in points as displayed), their target pages (-1 for none) and URIs. */
    @JvmStatic external fun nativeLinks(doc: Long, index: Int): Array<Any>?

    /** See [PdfiumDocument.render]; it stops early once [lifetime] or [token] is cancelled. */
    @JvmStatic external fun nativeRender(
        doc: Long, index: Int, bitmap: Bitmap, fullW: Int, fullH: Int, left: Int, top: Int,
        lifetime: CancelToken, token: CancelToken?,
    ): Boolean
}
