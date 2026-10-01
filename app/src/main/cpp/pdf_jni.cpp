// The JNI surface over the vendored PDFium (see pdfium.cmake and THIRD_PARTY).
// PDFium is not thread-safe, so all but nativeRevision run on PdfiumThread.
#include <android/bitmap.h>
#include <fcntl.h>
#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <climits>
#include <cstring>
#include <utility>
#include <vector>

#include "public/fpdf_formfill.h"
#include "public/fpdf_progressive.h"
#include "public/fpdfview.h"

namespace {

// Pages kept loaded per document, so render, links and text parse a page once.
constexpr size_t kPageCache = 4;

// An open document, the file it reads and its most recently used pages, newest last.
struct Doc {
    int fd = -1;
    FPDF_DOCUMENT pdf = nullptr;
    // Only for documents with a form; PDFium keeps a pointer to form_info.
    FPDF_FORMFILLINFO form_info = {};
    FPDF_FORMHANDLE form = nullptr;
    std::vector<std::pair<int, FPDF_PAGE>> pages;
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
    if (!doc->pdf) return static_cast<jint>(FPDF_GetLastError());
    // Form fields are widget annotations, which only FPDF_FFLDraw paints.
    if (FPDF_GetFormType(doc->pdf) != FORMTYPE_NONE) {
        doc->form_info.version = 1;
        doc->form = FPDFDOC_InitFormFillEnvironment(doc->pdf, &doc->form_info);
    }
    return FPDF_ERR_SUCCESS;
}

void ClosePage(Doc* doc, FPDF_PAGE page) {
    if (doc->form) FORM_OnBeforeClosePage(page, doc->form);
    FPDF_ClosePage(page);
}

// Page index, loaded (its content parsed) at most once while it stays in the cache.
FPDF_PAGE GetPage(Doc* doc, int index) {
    auto& pages = doc->pages;
    for (size_t i = 0; i < pages.size(); ++i) {
        if (pages[i].first != index) continue;
        const auto hit = pages[i];
        pages.erase(pages.begin() + i);
        pages.push_back(hit);
        return hit.second;
    }
    FPDF_PAGE page = FPDF_LoadPage(doc->pdf, index);
    if (!page) return nullptr;
    if (doc->form) FORM_OnAfterLoadPage(page, doc->form);
    if (pages.size() == kPageCache) {
        ClosePage(doc, pages.front().second);
        pages.erase(pages.begin());
    }
    pages.emplace_back(index, page);
    return page;
}

// Pauses a progressive render, for good, once either CancelToken is cancelled.
struct Cancel {
    IFSDK_PAUSE pause = {};
    JNIEnv* env;
    jobject tokens[2];
    jfieldID cancelled;

    Cancel(JNIEnv* e, jobject lifetime, jobject token) : env(e), tokens{lifetime, token} {
        pause.version = 1;
        pause.NeedToPauseNow = [](IFSDK_PAUSE* p) -> FPDF_BOOL {
            return static_cast<Cancel*>(p->user)->IsCancelled();
        };
        pause.user = this;
        cancelled = env->GetFieldID(env->GetObjectClass(lifetime), "isCancelled", "Z");
    }

    bool IsCancelled() const {
        for (jobject t : tokens) {
            if (t && env->GetBooleanField(t, cancelled)) return true;
        }
        return false;
    }
};

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
    for (const auto& cached : doc->pages) ClosePage(doc, cached.second);
    if (doc->form) FPDFDOC_ExitFormFillEnvironment(doc->form);
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

// Renders the part of page index at (left, top) in a full_w x full_h raster of the whole page
// into bitmap, on white. False when cancelled or failed.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_xnotes_platform_PdfiumNative_nativeRender(JNIEnv* env, jclass, jlong handle, jint index,
                                                   jobject jbitmap, jint full_w, jint full_h,
                                                   jint left, jint top, jobject lifetime,
                                                   jobject token) {
    Doc* doc = FromHandle(handle);
    Cancel cancel(env, lifetime, token);
    FPDF_PAGE page = cancel.IsCancelled() ? nullptr : GetPage(doc, index);
    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (!page || AndroidBitmap_getInfo(env, jbitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, jbitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return false;
    }
    memset(pixels, 0xFF, static_cast<size_t>(info.stride) * info.height);
    FPDF_BITMAP bitmap = FPDFBitmap_CreateEx(static_cast<int>(info.width),
                                             static_cast<int>(info.height), FPDFBitmap_BGRA,
                                             pixels, static_cast<int>(info.stride));
    int status = FPDF_RenderPageBitmap_Start(bitmap, page, -left, -top, full_w, full_h, 0,
                                             FPDF_ANNOT | FPDF_REVERSE_BYTE_ORDER,
                                             &cancel.pause);
    // The pause only ever asks to stop, so a render left to be continued was cancelled.
    while (status == FPDF_RENDER_TOBECONTINUED && !cancel.IsCancelled()) {
        status = FPDF_RenderPage_Continue(page, &cancel.pause);
    }
    FPDF_RenderPage_Close(page);
    if (status == FPDF_RENDER_DONE && doc->form) {
        // Without FPDF_ANNOT, FFLDraw paints the form fields alone, not the annotations again.
        FPDF_FFLDraw(doc->form, bitmap, page, -left, -top, full_w, full_h, 0,
                     FPDF_REVERSE_BYTE_ORDER);
    }
    FPDFBitmap_Destroy(bitmap);
    AndroidBitmap_unlockPixels(env, jbitmap);
    return status == FPDF_RENDER_DONE;
}
