// The JNI surface over the vendored PDFium (see pdfium.cmake and THIRD_PARTY).
// PDFium is not thread-safe, so all but nativeRevision run on PdfiumThread.
#include <fcntl.h>
#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <climits>
#include <vector>

#include "public/fpdfview.h"

namespace {

// An open document and the file it reads.
struct Doc {
    int fd = -1;
    FPDF_DOCUMENT pdf = nullptr;
};

Doc* FromHandle(jlong handle) {
    return reinterpret_cast<Doc*>(handle);
}

// FPDF_FILEACCESS reader. Positioned reads need no shared file offset.
int ReadBlock(void* param, unsigned long pos, unsigned char* buf, unsigned long size) {
    const int fd = static_cast<Doc*>(param)->fd;
    while (size > 0) {
        const ssize_t n = pread64(fd, buf, size, static_cast<off64_t>(pos));
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return 0;
        buf += n;
        pos += n;
        size -= n;
    }
    return 1;
}

// Opens path into doc, returning an FPDF_ERR_* code.
jint Open(Doc* doc, const char* path) {
    doc->fd = open(path, O_RDONLY | O_CLOEXEC);
    struct stat64 st;
    if (doc->fd < 0 || fstat64(doc->fd, &st) != 0) return FPDF_ERR_FILE;
    // FPDF_FILEACCESS addresses the file with an unsigned long, 32 bits on armv7.
    if (static_cast<unsigned long long>(st.st_size) > ULONG_MAX) return FPDF_ERR_FILE;
    FPDF_FILEACCESS access = {};
    access.m_FileLen = static_cast<unsigned long>(st.st_size);
    access.m_GetBlock = ReadBlock;
    access.m_Param = doc;
    doc->pdf = FPDF_LoadCustomDocument(&access, nullptr);
    return doc->pdf ? FPDF_ERR_SUCCESS : static_cast<jint>(FPDF_GetLastError());
}

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xnotes_platform_PdfiumNative_nativeRevision(JNIEnv* env, jclass) {
    return env->NewStringUTF(XNOTES_PDFIUM_REVISION);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xnotes_platform_PdfiumNative_nativeInit(JNIEnv*, jclass) {
    FPDF_LIBRARY_CONFIG config = {};
    config.version = 2;
    FPDF_InitLibraryWithConfig(&config);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_xnotes_platform_PdfiumNative_nativeOpen(JNIEnv* env, jclass, jstring jpath,
                                                 jintArray jout) {
    jint out[2] = {FPDF_ERR_FILE, 0};
    auto* doc = new Doc;
    if (const char* path = env->GetStringUTFChars(jpath, nullptr)) {
        out[0] = Open(doc, path);
        env->ReleaseStringUTFChars(jpath, path);
    }
    if (doc->pdf) {
        out[1] = FPDF_GetPageCount(doc->pdf);
    } else {
        if (doc->fd >= 0) close(doc->fd);
        delete doc;
        doc = nullptr;
    }
    env->SetIntArrayRegion(jout, 0, 2, out);
    return reinterpret_cast<jlong>(doc);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xnotes_platform_PdfiumNative_nativeClose(JNIEnv*, jclass, jlong handle) {
    Doc* doc = FromHandle(handle);
    FPDF_CloseDocument(doc->pdf);
    close(doc->fd);
    delete doc;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_xnotes_platform_PdfiumNative_nativePageSizes(JNIEnv* env, jclass, jlong handle) {
    FPDF_DOCUMENT pdf = FromHandle(handle)->pdf;
    const int count = FPDF_GetPageCount(pdf);
    // FPDF_GetPageSizeByIndexF reads the page dictionary only, never the content stream.
    std::vector<float> sizes(2 * static_cast<size_t>(count));
    for (int i = 0; i < count; ++i) {
        FS_SIZEF size = {};
        if (FPDF_GetPageSizeByIndexF(pdf, i, &size)) {
            sizes[2 * i] = size.width;
            sizes[2 * i + 1] = size.height;
        }
    }
    jfloatArray out = env->NewFloatArray(2 * count);
    if (out) env->SetFloatArrayRegion(out, 0, 2 * count, sizes.data());
    return out;
}
