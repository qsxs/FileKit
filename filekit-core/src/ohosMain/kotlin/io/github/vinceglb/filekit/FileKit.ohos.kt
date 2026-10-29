@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.vinceglb.filekit

import io.github.vinceglb.filekit.exceptions.FileKitException
import kotlinx.cinterop.*
import platform.AbilityKit.AbilityRuntime.*

public actual object FileKit

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

// 移动应用安装后没有源项目目录，与 Android/iOS 一致。
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

public actual suspend fun FileKit.saveImageToGallery(bytes: ByteArray, filename: String): Result<Unit> =
    Result.failure(FileKitException("Saving images to the gallery is not supported on OHOS"))

public actual suspend fun FileKit.saveVideoToGallery(file: PlatformFile, filename: String): Result<Unit> =
    Result.failure(FileKitException("Saving videos to the gallery is not supported on OHOS"))
