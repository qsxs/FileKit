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
                val orientation = readOrientation(requireNotNull(source.value))
                val swapsAxes = orientation in 5..8
                val orientedWidth = if (swapsAxes) height.value.toInt() else width.value.toInt()
                val orientedHeight = if (swapsAxes) width.value.toInt() else height.value.toInt()
                val (newWidth, newHeight) = calculateNewDimensions(orientedWidth, orientedHeight, maxWidth, maxHeight)
                checkImage(OH_DecodingOptions_Create(decoding.ptr), "create decoding options")
                val dimensions = alloc<Image_Size> {
                    // 先请求较小解码；纠正后的尺寸反映射到原始解码坐标，避免横竖图上限颠倒。
                    this.width = (if (swapsAxes) newHeight else newWidth).coerceAtLeast(1).toUInt()
                    this.height = (if (swapsAxes) newWidth else newHeight).coerceAtLeast(1).toUInt()
                }
                checkImage(OH_DecodingOptions_SetDesiredSize(decoding.value, dimensions.ptr), "set dimensions")
                checkImage(OH_ImageSourceNative_CreatePixelmap(source.value, decoding.value, pixelmap.ptr), "decode pixelmap")
                applyOrientation(requireNotNull(pixelmap.value), orientation)
                checkImage(OH_PackingOptions_Create(packing.ptr), "create packing options")
                val mime = if (imageFormat == ImageFormat.PNG) "image/png" else "image/jpeg"
                val mimeType = alloc<Image_MimeType> {
                    data = mime.cstr.ptr
                    size = mime.length.convert()
                }
                checkImage(OH_PackingOptions_SetMimeType(packing.value, mimeType.ptr), "set image format")
                checkImage(OH_PackingOptions_SetQuality(packing.value, quality.toUInt()), "set quality")
                // 方向已作用于像素；不再写入旧 EXIF Orientation，避免接收方再次旋转。
                checkImage(OH_PackingOptions_SetNeedsPackProperties(packing.value, false), "disable original image properties")
                checkImage(OH_ImagePackerNative_Create(packer.ptr), "create image packer")
                // 编码结果可能大于输入，使用本次私有临时文件，不能按输入长度截断输出。
                val template = (FileKit.cacheDir.path + "/filekit-image-XXXXXX").cstr.getPointer(this)
                val fd = mkstemp(template)
                if (fd < 0) throw FileKitException("Cannot create image temporary file: ${strerror(errno)?.toKString()}")
                try {
                    // 打开后立即 unlink，仍可通过 FD 编码和回读，失败不会遗留缓存文件。
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

private fun readOrientation(source: CPointer<OH_ImageSourceNative>): Int = memScoped {
    val keyText = "Orientation"
    val key = alloc<Image_String> {
        data = keyText.cstr.ptr
        size = keyText.length.convert()
    }
    val output = alloc<Image_String> { data = null; size = 0u }
    try {
        val status = OH_ImageSourceNative_GetImageProperty(source, key.ptr, output.ptr)
        // 仅此 getter 的已证状态表示方向不可用，不能据此区分缺 tag 与局部 EXIF 损坏。
        if (status == IMAGE_BAD_PARAMETER) return@memScoped 1
        checkImage(status, "read Orientation")
        val data = output.data ?: throw FileKitException("ImageKit returned no Orientation value")
        if (output.size == 0uL || output.size > Int.MAX_VALUE.toULong()) {
            throw FileKitException("ImageKit returned an invalid Orientation length")
        }
        // Native 分配不含 NUL；按 size 读取，并在所有退出路径释放独立 malloc 输出。
        when (data.readBytes(output.size.toInt()).decodeToString()) {
            "Top-left" -> 1
            "Top-right" -> 2
            "Bottom-right" -> 3
            "Bottom-left" -> 4
            "Left-top" -> 5
            "Right-top" -> 6
            "Right-bottom" -> 7
            "Left-bottom" -> 8
            else -> 1
        }
    } finally {
        output.data?.let { free(it) }
    }
}

private fun applyOrientation(pixelmap: CPointer<OH_PixelmapNative>, orientation: Int) {
    // 以像素几何定义处理镜像转置；5/7 先水平翻转再旋转，不能简化为角度投影。
    when (orientation) {
        2 -> checkImage(OH_PixelmapNative_Flip(pixelmap, true, false), "flip image horizontally")
        3 -> checkImage(OH_PixelmapNative_Rotate(pixelmap, 180f), "rotate image 180 degrees")
        4 -> checkImage(OH_PixelmapNative_Flip(pixelmap, false, true), "flip image vertically")
        5 -> {
            checkImage(OH_PixelmapNative_Flip(pixelmap, true, false), "transpose image horizontally")
            checkImage(OH_PixelmapNative_Rotate(pixelmap, 270f), "transpose image 270 degrees")
        }
        6 -> checkImage(OH_PixelmapNative_Rotate(pixelmap, 90f), "rotate image 90 degrees")
        7 -> {
            checkImage(OH_PixelmapNative_Flip(pixelmap, true, false), "transpose image horizontally")
            checkImage(OH_PixelmapNative_Rotate(pixelmap, 90f), "transpose image 90 degrees")
        }
        8 -> checkImage(OH_PixelmapNative_Rotate(pixelmap, 270f), "rotate image 270 degrees")
    }
}

private fun checkImage(status: Image_ErrorCode, operation: String) {
    if (status != IMAGE_SUCCESS) throw FileKitException("OHOS ImageKit failed to $operation: $status")
}
