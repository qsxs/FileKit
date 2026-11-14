@file:OptIn(io.github.vinceglb.filekit.FileKitOhosInternalApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.vinceglb.filekit.dialogs

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.cacheDir
import io.github.vinceglb.filekit.copyOhosMediaFile
import io.github.vinceglb.filekit.exceptions.FileKitException
import io.github.vinceglb.filekit.isDirectory
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.parent
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.size
import io.github.vinceglb.filekit.toKotlinxIoPath
import io.github.vinceglb.filekit.withOhosMediaTemporaryFile
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import platform.posix.errno
import platform.posix.rename
import platform.posix.strerror

public actual class FileKitDialogSettings {
    public actual companion object {
        public actual fun createDefault(): FileKitDialogSettings = FileKitDialogSettings()
    }
}

public actual class FileKitOpenFileSettings(
    public val onError: (Throwable) -> Unit = { throw it }
) {
    public actual companion object {
        public actual fun createDefault(): FileKitOpenFileSettings = FileKitOpenFileSettings()
    }
}

public actual class FileKitOpenCameraSettings {
    public actual companion object {
        public actual fun createDefault(): FileKitOpenCameraSettings = FileKitOpenCameraSettings()
    }
}

public actual class FileKitShareSettings {
    public actual companion object {
        public actual fun createDefault(): FileKitShareSettings = FileKitShareSettings()
    }
}

internal actual suspend fun FileKit.platformOpenFilePicker(
    type: FileKitType,
    mode: PickerMode,
    directory: PlatformFile?,
    dialogSettings: FileKitDialogSettings,
): Flow<FileKitPickerState<List<PlatformFile>>> {
    val capturedCall = OhosSystemDialogs.captureOwnerCall()
    return object : Flow<FileKitPickerState<List<PlatformFile>>> {
        override suspend fun collect(collector: FlowCollector<FileKitPickerState<List<PlatformFile>>>) {
            var untransferredFiles: List<PlatformFile>? = null
            try {
                flow<FileKitPickerState<List<PlatformFile>>> {
                    val maximum = if (mode is PickerMode.Multiple) mode.maxItems ?: 50 else 1
                    require(maximum in 1..50) { "maxItems must be in 1..50" }
                    val request = buildJsonObject {
                        put("operation", "pick")
                        put(
                            "type",
                            when (type) {
                                FileKitType.Image -> "image"
                                FileKitType.Video -> "video"
                                FileKitType.ImageAndVideo -> "media"
                                is FileKitType.File -> "file"
                            }
                        )
                        put("maxItems", maximum)
                        if (type is FileKitType.File) {
                            putJsonArray("extensions") {
                                type.extensions?.forEach { add(normalizedExtension(it)) }
                            }
                        }
                        directory?.let { put("directory", it.path) }
                    }
                    // 只包装平台调用失败；下游收集器的异常和协程取消仍按原样传播。
                    val files = try {
                        capturedCall(request)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (invalid: IllegalArgumentException) {
                        throw invalid
                    } catch (error: Throwable) {
                        emit(
                            FileKitPickerState.Failed(
                                FileKitPickerException("The OHOS file picker could not complete the operation.", error)
                            )
                        )
                        return@flow
                    }
                    if (files.isNullOrEmpty()) {
                        emit(FileKitPickerState.Cancelled)
                    } else {
                        untransferredFiles = files
                        emit(FileKitPickerState.Started(files.size))
                        emit(FileKitPickerState.Completed(files))
                    }
                }.collect { state ->
                    // 上游 flow 的取消/context 检查已经通过；进入下游即移交 Completed 的 owner。
                    // first/take 的正常短路和其它下游异常都原样传播，不再撤销已移交的句柄。
                    if (state is FileKitPickerState.Completed) untransferredFiles = null
                    collector.emit(state)
                }
            } finally {
                // Started 后短路或 Completed 尚未进入下游时取消，句柄仍归本层负责。
                untransferredFiles?.forEach { it.close() }
            }
        }
    }
}

public actual suspend fun FileKit.openDirectoryPicker(
    directory: PlatformFile?,
    dialogSettings: FileKitDialogSettings,
): PlatformFile? = try {
    OhosSystemDialogs.call(
        buildJsonObject {
            put("operation", "directory")
            directory?.let { put("directory", it.path) }
        },
    )?.single()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    throw FileKitDialogException("The OHOS directory picker could not complete the operation.", error)
}

internal actual suspend fun FileKit.platformOpenFileSaver(
    suggestedName: String,
    defaultExtension: String?,
    allowedExtensions: Set<String>?,
    directory: PlatformFile?,
    dialogSettings: FileKitDialogSettings,
): PlatformFile? {
    require(suggestedName.isNotBlank() && '/' !in suggestedName && '\u0000' !in suggestedName) { "Invalid suggested filename" }
    val suffix = normalizeFileSaverExtension(defaultExtension)?.let(::normalizedExtension)
    val extensions = buildFileSaverAllowedFileTypes(defaultExtension, allowedExtensions)?.map(::normalizedExtension)
    val name = if (suffix == null || suggestedName.endsWith(".$suffix", ignoreCase = true)) suggestedName else "$suggestedName.$suffix"
    return try {
        OhosSystemDialogs
            .call(
                buildJsonObject {
                    put("operation", "save")
                    put("name", name)
                    extensions?.let { putJsonArray("extensions") { it.forEach { extension -> add(extension) } } }
                    directory?.let { put("directory", it.path) }
                }
            )?.singleOrNull()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (invalid: IllegalArgumentException) {
        throw invalid
    } catch (error: Throwable) {
        throw FileKitDialogException("The OHOS file saver could not complete the operation.", error)
    }
}

private fun normalizedExtension(value: String): String = value.removePrefix(".").also {
    require(it.isNotBlank() && '/' !in it && '\u0000' !in it && ';' !in it && '|' !in it) { "Invalid file extension" }
}

public actual fun FileKit.openFileWithDefaultApplication(file: PlatformFile, openFileSettings: FileKitOpenFileSettings) {
    CoroutineScope(Dispatchers.Main).launch {
        try {
            OhosSystemDialogs.call(
                buildJsonObject {
                    put("operation", "open")
                    put("path", file.path)
                    put("mime", file.mimeType()?.toString() ?: "application/octet-stream")
                }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            openFileSettings.onError(error)
        }
    }
}

public actual suspend fun FileKit.openCameraPicker(
    type: FileKitCameraType,
    cameraFacing: FileKitCameraFacing,
    destinationFile: PlatformFile,
    openCameraSettings: FileKitOpenCameraSettings,
): PlatformFile? = try {
    val runInHost = OhosSystemDialogs.captureMediaOperation<PlatformFile?>()
    val facing = when (cameraFacing) {
        FileKitCameraFacing.System -> "CAMERA_POSITION_UNSPECIFIED"
        FileKitCameraFacing.Front -> "CAMERA_POSITION_FRONT"
        FileKitCameraFacing.Back -> "CAMERA_POSITION_BACK"
    }
    val suffix = when (type) {
        FileKitCameraType.Photo -> ".jpg"
    }
    withOhosMediaTemporaryFile(cacheDir, suffix) { staged ->
        runInHost { awaitDialog ->
            val request = buildJsonObject {
                put("operation", "camera")
                put("path", staged.path)
                put("facing", facing)
            }
            if (awaitDialog(request) == null) return@runInHost null
            withContext(Dispatchers.IO) {
                if (staged.size() <= 0) throw FileKitDialogException("The OHOS camera did not write a photo")
                if (destinationFile.isDirectory()) throw FileKitDialogException("Camera destination must be a file")
                commitCameraPhoto(staged, destinationFile)
            }
            destinationFile
        }
    }
} catch (denied: CameraPermissionDenied) {
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: FileKitDialogException) {
    throw error
} catch (error: Exception) {
    throw FileKitDialogException("The OHOS camera picker could not complete the operation.", error)
}

private suspend fun commitCameraPhoto(staged: PlatformFile, destination: PlatformFile) {
    val localPath = try {
        destination.toKotlinxIoPath()
    } catch (_: IllegalStateException) {
        null
    }
    if (localPath == null) {
        // Provider 没有通用的原子替换接口。打开授权输出是提交点，之后完成写入。
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) { copyOhosMediaFile(staged, destination) }
        return
    }

    val directory = destination.parent() ?: PlatformFile(".")
    withOhosMediaTemporaryFile(directory) { output ->
        copyOhosMediaFile(staged, output)
        currentCoroutineContext().ensureActive()
        if (rename(output.path, localPath.toString()) != 0) {
            throw FileKitDialogException("Cannot replace the OHOS camera destination: ${strerror(errno)?.toKString()}")
        }
    }
}

public actual suspend fun FileKit.shareFile(file: PlatformFile, shareSettings: FileKitShareSettings): Unit =
    shareFile(listOf(file), shareSettings)

public actual suspend fun FileKit.shareFile(files: List<PlatformFile>, shareSettings: FileKitShareSettings) {
    require(files.isNotEmpty()) { "At least one file is required" }
    try {
        OhosSystemDialogs.share(files)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (invalid: IllegalArgumentException) {
        throw invalid
    } catch (error: Throwable) {
        throw FileKitDialogException("The OHOS share operation could not complete.", error)
    }
}
