package com.xnotes.platform

import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.ImageSize

/** Reads image sizes via the Android framework (spec 01 §3).
 *  Inserted-image decoding goes through [ImageDecoder] (file-backed, on demand), not this codec. */
class AndroidImageCodec : ImageCodec {

    override fun probeFile(path: String): ImageSize? = ImageDecoder.probeFile(path)
}
