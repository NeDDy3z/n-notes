// The JNI surface over the vendored PDFium (see pdfium.cmake and THIRD_PARTY).
#include <jni.h>

#include <cstdio>

#include "public/fpdfview.h"

namespace {

// One 200x100 pt page and no xref table, so opening it runs the repair path too.
constexpr char kTinyPdf[] =
    "%PDF-1.4\n"
    "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
    "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
    "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 100]>>endobj\n"
    "trailer<</Root 1 0 R>>\n"
    "%%EOF\n";

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    FPDF_LIBRARY_CONFIG config = {};
    config.version = 2;
    FPDF_InitLibraryWithConfig(&config);
    return JNI_VERSION_1_6;
}

// The pinned revision and whether a document opens, for the debug overlay.
extern "C" JNIEXPORT jstring JNICALL
Java_com_xnotes_platform_PdfiumNative_nativeSelfTest(JNIEnv* env, jclass) {
    char out[96];
    FPDF_DOCUMENT doc = FPDF_LoadMemDocument64(kTinyPdf, sizeof(kTinyPdf) - 1, nullptr);
    if (!doc) {
        snprintf(out, sizeof(out), "%.10s open failed (%lu)", XNOTES_PDFIUM_REVISION,
                 FPDF_GetLastError());
    } else {
        FS_SIZEF size = {};
        FPDF_GetPageSizeByIndexF(doc, 0, &size);
        snprintf(out, sizeof(out), "%.10s ok, %d pg %.0fx%.0f", XNOTES_PDFIUM_REVISION,
                 FPDF_GetPageCount(doc), size.width, size.height);
        FPDF_CloseDocument(doc);
    }
    return env->NewStringUTF(out);
}
