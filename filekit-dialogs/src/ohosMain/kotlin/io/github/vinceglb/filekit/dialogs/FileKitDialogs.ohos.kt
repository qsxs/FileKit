package io.github.vinceglb.filekit.dialogs

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.exceptions.FileKitException
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.mimeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

public actual class FileKitDialogSettings {
    public actual companion object {
        public actual fun createDefault(): FileKitDialogSettings = FileKitDialogSettings()
    }
}

public actual class FileKitOpenFileSettings(public val onError: (Throwable) -> Unit = { throw it }) {
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
                        put("type", when (type) {
                            FileKitType.Image -> "image"
                            FileKitType.Video -> "video"
                            FileKitType.ImageAndVideo -> "media"
                            is FileKitType.File -> "file"
                        })
                        put("maxItems", maximum)
                        if (type is FileKitType.File) putJsonArray("extensions") {
                            type.extensions?.forEach { add(normalizedExtension(it)) }
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
                        emit(FileKitPickerState.Failed(FileKitPickerException("The OHOS file picker could not complete the operation.", error)))
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
): PlatformFile? = throw FileKitDialogException("Directory picking is not supported on OHOS")

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
        OhosSystemDialogs.call(buildJsonObject {
            put("operation", "save")
            put("name", name)
            extensions?.let { putJsonArray("extensions") { it.forEach { extension -> add(extension) } } }
            directory?.let { put("directory", it.path) }
        })?.singleOrNull()
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
            OhosSystemDialogs.call(buildJsonObject {
                put("operation", "open")
                put("path", file.path)
                put("mime", file.mimeType()?.toString() ?: "application/octet-stream")
            })
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
): PlatformFile? = throw FileKitDialogException("Camera picking is not supported on OHOS")

public actual suspend fun FileKit.shareFile(file: PlatformFile, shareSettings: FileKitShareSettings): Unit =
    shareFile(listOf(file), shareSettings)

public actual suspend fun FileKit.shareFile(files: List<PlatformFile>, shareSettings: FileKitShareSettings) {
    require(files.isNotEmpty()) { "At least one file is required" }
    try {
        OhosSystemDialogs.call(buildJsonObject {
            put("operation", "share")
            putJsonArray("paths") { files.forEach { add(it.path) } }
        })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (invalid: IllegalArgumentException) {
        throw invalid
    } catch (error: Throwable) {
        throw FileKitDialogException("The OHOS share operation could not complete.", error)
    }
}
