@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.vinceglb.filekit

import cnames.structs.*
import io.github.vinceglb.filekit.exceptions.FileKitException
import io.github.vinceglb.filekit.utils.calculateNewDimensions
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.ImageKit.Image_NativeModule.*
import platform.posix.*

public actual suspend fun FileKit.compressImage(
    bytes: ByteArray,
    imageFormat: ImageFormat,
    quality: Int,
    maxWidth: Int?,
    maxHeight: Int?,
): ByteArray = withContext(Dispatchers.IO) {
    require(bytes.isNotEmpty()) { "Image bytes must not be empty" }
    require(quality in 0..100) { "Quality must be between 0 and 100" }
    require(maxWidth == null || maxWidth > 0) { "Maximum width must be positive" }
    require(maxHeight == null || maxHeight > 0) { "Maximum height must be positive" }
    bytes.usePinned { input ->
        memScoped {
            val source = alloc<CPointerVar<OH_ImageSourceNative>> { value = null }
            val info = alloc<CPointerVar<OH_ImageSource_Info>> { value = null }
            val decoding = alloc<CPointerVar<OH_DecodingOptions>> { value = null }
            val pixelmap = alloc<CPointerVar<OH_PixelmapNative>> { value = null }
            val packing = alloc<CPointerVar<OH_PackingOptions>> { value = null }
            val packer = alloc<CPointerVar<OH_ImagePackerNative>> { value = null }
            try {
                checkImage(OH_ImageSourceNative_CreateFromData(input.addressOf(0).reinterpret(), bytes.size.convert(), source.ptr), "decode source")
                checkImage(OH_ImageSourceInfo_Create(info.ptr), "create image info")
                checkImage(OH_ImageSourceNative_GetImageInfo(source.value, 0, info.value), "read image info")
                val width = alloc<UIntVar>()
                val height = alloc<UIntVar>()
                checkImage(OH_ImageSourceInfo_GetWidth(info.value, width.ptr), "read width")
                checkImage(OH_ImageSourceInfo_GetHeight(info.value, height.ptr), "read height")
                require(width.value in 1u..Int.MAX_VALUE.toUInt() && height.value in 1u..Int.MAX_VALUE.toUInt()) { "Invalid image dimensions" }
                val (newWidth, newHeight) = calculateNewDimensions(width.value.toInt(), height.value.toInt(), maxWidth, maxHeight)
                checkImage(OH_DecodingOptions_Create(decoding.ptr), "create decoding options")
                val dimensions = alloc<Image_Size> {
                    this.width = newWidth.coerceAtLeast(1).toUInt()
                    this.height = newHeight.coerceAtLeast(1).toUInt()
                }
                checkImage(OH_DecodingOptions_SetDesiredSize(decoding.value, dimensions.ptr), "set dimensions")
                checkImage(OH_ImageSourceNative_CreatePixelmap(source.value, decoding.value, pixelmap.ptr), "decode pixelmap")
                checkImage(OH_PackingOptions_Create(packing.ptr), "create packing options")
                val mime = if (imageFormat == ImageFormat.PNG) "image/png" else "image/jpeg"
                val mimeType = alloc<Image_MimeType> {
                    data = mime.cstr.ptr
                    size = mime.length.convert()
                }
                checkImage(OH_PackingOptions_SetMimeType(packing.value, mimeType.ptr), "set image format")
                checkImage(OH_PackingOptions_SetQuality(packing.value, quality.toUInt()), "set quality")
                checkImage(OH_ImagePackerNative_Create(packer.ptr), "create image packer")
                // Encode to a private temporary file: compressed output can exceed input size.
                val template = (FileKit.cacheDir.path + "/filekit-image-XXXXXX").cstr.getPointer(this)
                val fd = mkstemp(template)
                if (fd < 0) throw FileKitException("Cannot create image temporary file: ${strerror(errno)?.toKString()}")
                try {
                    // The open descriptor remains valid after unlink; failures cannot leave cache files behind.
                    if (unlink(template.toKString()) != 0) throw FileKitException("Cannot unlink image temporary file")
                    checkImage(OH_ImagePackerNative_PackToFileFromPixelmap(packer.value, packing.value, pixelmap.value, fd), "encode image")
                    val length = lseek(fd, 0, SEEK_END)
                    if (length <= 0 || length > Int.MAX_VALUE.toLong()) throw FileKitException("Invalid encoded image size: $length")
                    if (lseek(fd, 0, SEEK_SET) < 0) throw FileKitException("Cannot rewind encoded image")
                    val result = ByteArray(length.toInt())
                    result.usePinned { output ->
                        var offset = 0
                        while (offset < result.size) {
                            val count = read(fd, output.addressOf(offset), (result.size - offset).convert())
                            if (count < 0 && errno == EINTR) continue
                            if (count <= 0) throw FileKitException("Cannot read encoded image")
                            offset += count.toInt()
                        }
                    }
                    result
                } finally {
                    close(fd)
                    unlink(template.toKString())
                }
            } finally {
                packer.value?.let { OH_ImagePackerNative_Release(it) }
                packing.value?.let { OH_PackingOptions_Release(it) }
                pixelmap.value?.let { OH_PixelmapNative_Release(it) }
                decoding.value?.let { OH_DecodingOptions_Release(it) }
                info.value?.let { OH_ImageSourceInfo_Release(it) }
                source.value?.let { OH_ImageSourceNative_Release(it) }
            }
        }
    }
}

private fun checkImage(status: Image_ErrorCode, operation: String) {
    if (status != IMAGE_SUCCESS) throw FileKitException("OHOS ImageKit failed to $operation: $status")
}
