@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.vinceglb.filekit

import io.github.vinceglb.filekit.exceptions.FileKitException
import kotlinx.cinterop.*
import platform.AbilityKit.AbilityRuntime.*

public actual object FileKit {
    /**
     * Application integration for PhotoAccessHelper. The callback must finish only after the
     * photo has been saved, and propagate cancellation/permission errors to the caller.
     * Dispatch to the ArkTS/UI thread in the callback when required by the host bridge.
     */
    public var saveImageToGalleryHandler: (suspend (bytes: ByteArray, filename: String) -> Unit)? = null
}

public actual val FileKit.filesDir: PlatformFile
    get() = applicationDirectory("filesDir") { buffer, size, length ->
        OH_AbilityRuntime_ApplicationContextGetFilesDir(buffer, size, length)
    }

public actual val FileKit.cacheDir: PlatformFile
    get() = applicationDirectory("cacheDir") { buffer, size, length ->
        OH_AbilityRuntime_ApplicationContextGetCacheDir(buffer, size, length)
    }

public actual val FileKit.databasesDir: PlatformFile
    get() = applicationDirectory("databasesDir") { buffer, size, length ->
        OH_AbilityRuntime_ApplicationContextGetDatabaseDir(buffer, size, length)
    }

// An installed mobile app has no source project directory, as on Android/iOS.
public actual val FileKit.projectDir: PlatformFile get() = filesDir

private inline fun applicationDirectory(
    name: String,
    getDirectory: (CPointer<ByteVar>, Int, CPointer<IntVar>) -> AbilityRuntime_ErrorCode,
): PlatformFile = memScoped {
    val capacity = 4096
    val buffer = allocArray<ByteVar>(capacity)
    val length = alloc<IntVar>()
    val status = getDirectory(buffer, capacity, length.ptr)
    if (status != ABILITY_RUNTIME_ERROR_CODE_NO_ERROR) {
        throw FileKitException("Cannot obtain $name from the OHOS application context: $status")
    }
    if (length.value <= 0 || length.value >= capacity) {
        throw FileKitException("OHOS returned an invalid $name length: ${length.value}")
    }
    PlatformFile(buffer.readBytes(length.value).decodeToString())
}

public actual suspend fun FileKit.saveImageToGallery(bytes: ByteArray, filename: String) {
    require(bytes.isNotEmpty()) { "Image bytes must not be empty" }
    require(filename.isNotBlank() && '/' !in filename && '\u0000' !in filename) { "Invalid image filename" }
    val handler = saveImageToGalleryHandler
        ?: throw FileKitException("Set FileKit.saveImageToGalleryHandler using the application's PhotoAccessHelper bridge before saving to the gallery")
    handler(bytes, filename)
}
