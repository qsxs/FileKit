package io.github.vinceglb.filekit.dialogs

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.mimeType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    title: String?,
    directory: PlatformFile?,
    dialogSettings: FileKitDialogSettings,
): Flow<FileKitPickerState<List<PlatformFile>>> = flow {
    val request = buildJsonObject {
        put("operation", "pick")
        put("type", when (type) {
            FileKitType.Image -> "image"
            FileKitType.Video -> "video"
            FileKitType.ImageAndVideo -> "media"
            is FileKitType.File -> "file"
        })
        put("maxItems", if (mode is PickerMode.Multiple) mode.maxItems ?: 50 else 1)
        if (type is FileKitType.File) putJsonArray("extensions") {
            type.extensions?.forEach { add(it.removePrefix(".")) }
        }
        directory?.let { put("directory", it.path) }
    }
    val files = OhosSystemDialogs.call(request)
    if (files.isNullOrEmpty()) {
        emit(FileKitPickerState.Cancelled)
    } else {
        // No background import: the native descriptor is ready before completion.
        try {
            emit(FileKitPickerState.Started(files.size))
            emit(FileKitPickerState.Completed(files))
        } catch (error: Throwable) {
            files.forEach { it.close() }
            throw error
        }
    }
}

public actual suspend fun FileKit.openDirectoryPicker(
    title: String?, directory: PlatformFile?, dialogSettings: FileKitDialogSettings,
): PlatformFile? = OhosSystemDialogs.call(buildJsonObject {
    put("operation", "directory")
    directory?.let { put("directory", it.path) }
})?.firstOrNull()

public actual suspend fun FileKit.openFileSaver(
    suggestedName: String, extension: String?, directory: PlatformFile?, dialogSettings: FileKitDialogSettings,
): PlatformFile? {
    require(suggestedName.isNotBlank() && '/' !in suggestedName && '\u0000' !in suggestedName)
    require(extension == null || ('/' !in extension && '\u0000' !in extension))
    val suffix = extension?.removePrefix(".")?.takeIf { it.isNotEmpty() }
    val name = if (suffix == null || suggestedName.endsWith(".$suffix", ignoreCase = true)) suggestedName else "$suggestedName.$suffix"
    return OhosSystemDialogs.call(buildJsonObject {
        put("operation", "save")
        put("name", name)
        directory?.let { put("directory", it.path) }
    })?.firstOrNull()
}

public actual fun FileKit.openFileWithDefaultApplication(file: PlatformFile, openFileSettings: FileKitOpenFileSettings) {
    CoroutineScope(Dispatchers.Main).launch {
        runCatching { OhosSystemDialogs.call(buildJsonObject {
        put("operation", "open")
        put("path", file.path)
        put("mime", file.mimeType()?.toString() ?: "application/octet-stream")
        }) }.onFailure(openFileSettings.onError)
    }
}

public actual suspend fun FileKit.openCameraPicker(
    type: FileKitCameraType,
    cameraFacing: FileKitCameraFacing,
    destinationFile: PlatformFile,
    openCameraSettings: FileKitOpenCameraSettings,
): PlatformFile? = OhosSystemDialogs.call(buildJsonObject {
    put("operation", "camera")
    put("path", destinationFile.path)
    put("front", cameraFacing == FileKitCameraFacing.Front)
})?.firstOrNull()

public actual suspend fun FileKit.shareFile(file: PlatformFile, shareSettings: FileKitShareSettings): Unit =
    shareFile(listOf(file), shareSettings)

public actual suspend fun FileKit.shareFile(files: List<PlatformFile>, shareSettings: FileKitShareSettings) {
    require(files.isNotEmpty()) { "At least one file is required" }
    OhosSystemDialogs.call(buildJsonObject {
        put("operation", "share")
        putJsonArray("paths") { files.forEach { add(it.path) } }
    })
}
