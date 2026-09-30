// The JNI surface over the vendored PDFium (see pdfium.cmake and THIRD_PARTY).
#include <jni.h>

#include "public/fpdfview.h"

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    FPDF_LIBRARY_CONFIG config = {};
    config.version = 2;
    FPDF_InitLibraryWithConfig(&config);
    return JNI_VERSION_1_6;
}
