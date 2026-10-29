@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.vinceglb.filekit.dialogs

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.exceptions.FileKitException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import platform.ArkTS.ArkTS_Napi_NativeModule.*
import platform.posix.*

internal expect fun initializeFileKitMainHandler(env: napi_env)

/** Initialize on the ArkTS UI thread with the owning UIAbilityContext, before opening dialogs. */
public fun FileKit.init(env: napi_env, context: napi_value) {
    initializeFileKitMainHandler(env)
    OhosSystemDialogs.init(env, context)
}

/** Call from the owning UIAbility's destruction callback, on the same ArkTS thread. */
public fun FileKit.dispose(): Unit = OhosSystemDialogs.dispose()

internal object OhosSystemDialogs {
    private var implementation: Implementation? = null

    fun init(env: napi_env, context: napi_value) {
        implementation?.dispose()
        implementation = Implementation(env, context)
    }

    fun dispose() {
        implementation?.dispose()
        implementation = null
    }

    suspend fun call(request: JsonObject): List<PlatformFile>? =
        (implementation ?: throw FileKitException("Call FileKit.init(env, context) first")).call(request)
}

private class Implementation(env: napi_env, context: napi_value) {
    private val js = OhosNapi(env)
    private val contextRef = js.reference(context)
    private var disposed = false
    private var active: CancellableContinuation<List<PlatformFile>?>? = null

    fun dispose() {
        if (disposed) return
        disposed = true
        active?.cancel()
        active = null
        napi_delete_reference(js.env, contextRef)
    }

    suspend fun call(request: JsonObject): List<PlatformFile>? = suspendCancellableCoroutine { continuation ->
        if (disposed || active != null) {
            continuation.resumeWith(Result.failure(FileKitException(
                if (disposed) "FileKit UIAbility has been destroyed" else "Another FileKit dialog is active"
            )))
            return@suspendCancellableCoroutine
        }
        active = continuation
        var settled = false
        var cleanup: (() -> Unit)? = null
        fun finish(value: napi_value?, failure: Throwable?) {
            if (settled) return
            settled = true
            if (active === continuation) active = null
            var files: List<PlatformFile>? = null
            try {
                if (failure != null) throw failure
                if (!disposed && continuation.isActive) {
                    files = js.scoped { decode(request, requireNotNull(value)) }
                    // Keep successful camera output; cancelled requests still remove newly created output.
                    if (files != null) cleanup = null
                    continuation.resume(files) { _, abandoned, _ -> abandoned?.forEach { it.close() } }
                }
            } catch (error: Throwable) {
                files?.forEach { it.close() }
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            } finally {
                cleanup?.invoke()
                cleanup = null
            }
        }
        try {
            js.scoped {
                val promise = start(request) { cleanup = it }
                val success = js.callback { finish(it, null) }
                val failure = js.callback { value ->
                    finish(null, FileKitException(js.describeError(value)))
                }
                js.call(promise, "then", success, failure)
            }
        } catch (error: Throwable) {
            finish(null, error)
        }
        // Cancellation cannot dismiss system UI. Keep active until its Promise settles,
        // so a second picker cannot overlap the first and its eventual result is discarded.
    }

    private fun start(request: JsonObject, onCleanup: (() -> Unit) -> Unit): napi_value {
        val context = js.dereference(contextRef)
        val options = js.obj()
        val operation = request.text("operation")
        return when (operation) {
            "pick", "directory", "save" -> {
                val type = request.text("type")
                if (operation == "pick" && type != "file") {
                    val photos = js.module("@ohos.file.photoAccessHelper")
                    js.set(options, "maxSelectNumber", js.number(request.count()))
                    val mime = when (type) { "image" -> "IMAGE_TYPE"; "video" -> "VIDEO_TYPE"; else -> "IMAGE_VIDEO_TYPE" }
                    js.set(options, "MIMEType", js.get(js.get(photos, "PhotoViewMIMETypes"), mime))
                    js.call(js.construct(photos, "PhotoViewPicker"), "select", options)
                } else {
                    val picker = js.module("@ohos.file.picker")
                    request.text("directory")?.let { js.set(options, "defaultFilePathUri", uri(it)) }
                    if (operation == "save") {
                        js.set(options, "newFileNames", js.array(listOf(js.text(request.text("name") ?: "file"))))
                    } else {
                        js.set(options, "maxSelectNumber", js.number(if (operation == "directory") 1 else request.count()))
                        if (operation == "directory") {
                            js.set(options, "selectMode", js.get(js.get(picker, "DocumentSelectMode"), "FOLDER"))
                        }
                        val extensions = request["extensions"]?.jsonArray.orEmpty()
                        if (extensions.isNotEmpty()) js.set(options, "fileSuffixFilters", js.array(
                            extensions.map { js.text(".${it.jsonPrimitive.content}") }
                        ))
                    }
                    js.call(js.construct(picker, "DocumentViewPicker", context), if (operation == "save") "save" else "select", options)
                }
            }
            "camera" -> {
                val path = requireNotNull(request.text("path"))
                require("://" !in path) { "Camera destination must be a sandbox path" }
                val existed = access(path, F_OK) == 0
                val fs = js.module("@ohos.file.fs")
                val mode = js.get(fs, "OpenMode")
                val destination = js.call(fs, "openSync", js.text(path), js.number(
                    js.int(js.get(mode, "CREATE")) or js.int(js.get(mode, "READ_WRITE"))
                ))
                js.call(fs, "closeSync", destination)
                if (!existed) onCleanup { unlink(path); Unit }
                val picker = js.module("@ohos.multimedia.cameraPicker")
                val camera = js.module("@ohos.multimedia.camera")
                val front = request["front"]?.jsonPrimitive?.booleanOrNull == true
                js.set(options, "cameraPosition", js.get(js.get(camera, "CameraPosition"),
                    if (front) "CAMERA_POSITION_FRONT" else "CAMERA_POSITION_BACK"))
                js.set(options, "saveUri", uri(path))
                js.call(picker, "pick", context, js.array(listOf(js.get(js.get(picker, "PickerMediaType"), "PHOTO"))), options)
            }
            "open" -> {
                js.set(options, "action", js.text("ohos.want.action.viewData"))
                js.set(options, "uri", uri(requireNotNull(request.text("path"))))
                js.set(options, "type", js.text(request.text("mime") ?: "application/octet-stream"))
                val want = js.module("@ohos.app.ability.wantConstant")
                js.set(options, "flags", js.get(js.get(want, "Flags"), "FLAG_AUTH_READ_URI_PERMISSION"))
                js.call(context, "startAbility", options)
            }
            "share" -> {
                val paths = request["paths"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
                require(paths.isNotEmpty()) { "No files to share" }
                // Optional HarmonyOS capability: loaded only when sharing is requested.
                val share = js.module("@hms.collaboration.systemShare")
                fun record(path: String): napi_value = js.obj().also {
                    js.set(it, "utd", js.text("general.file"))
                    js.set(it, "uri", uri(path))
                }
                val data = js.construct(share, "SharedData", record(paths.first()))
                paths.drop(1).forEach { js.call(data, "addRecord", record(it)) }
                js.call(js.construct(share, "ShareController", data), "show", context, options)
            }
            else -> throw FileKitException("Unknown FileKit operation: $operation")
        }
    }

    private fun decode(request: JsonObject, result: napi_value): List<PlatformFile>? {
        val operation = request.text("operation")
        if (operation == "open" || operation == "share") return emptyList()
        if (operation == "camera") {
            val code = js.int(js.get(result, "resultCode"))
            if (code == -1) return null
            if (code != 0) throw FileKitException("Camera failed with result code $code")
            return listOf(PlatformFile(requireNotNull(request.text("path"))))
        }
        val uris = js.elements(if (operation == "pick" && request.text("type") != "file") js.get(result, "photoUris") else result)
            .map { js.string(it) }
        if (uris.isEmpty()) return null
        val maximum = if (operation == "pick") request.count() else 1
        require(uris.size <= maximum) { "Picker exceeded maxItems" }
        if (operation == "directory") {
            val path = js.string(js.get(js.construct(js.module("@ohos.file.fileuri"), "FileUri", js.text(uris.first())), "path"))
            val fs = js.module("@ohos.file.fs")
            require(js.bool(js.call(fs, "accessSync", js.text(path))) &&
                js.bool(js.call(js.call(fs, "statSync", js.text(path)), "isDirectory"))) {
                "Selected directory is not accessible as a filesystem path on this device"
            }
            return listOf(PlatformFile(path))
        }
        val files = mutableListOf<PlatformFile>()
        try {
            uris.forEach { uri -> files += opened(uri, operation == "save") }
            return files
        } catch (error: Throwable) {
            files.forEach { it.close() }
            throw error
        }
    }

    private fun uri(path: String): napi_value = if ("://" in path) js.text(path)
        else js.call(js.module("@ohos.file.fileuri"), "getUriFromPath", js.text(path))

    private fun opened(uri: String, writable: Boolean): PlatformFile {
        val fs = js.module("@ohos.file.fs")
        val mode = js.get(js.get(fs, "OpenMode"), if (writable) "READ_WRITE" else "READ_ONLY")
        val file = js.call(fs, "openSync", js.text(uri), mode)
        var imported: PlatformFile? = null
        try {
            val name = js.string(js.get(js.construct(js.module("@ohos.file.fileuri"), "FileUri", js.text(uri)), "name"))
            imported = PlatformFile.fromFileDescriptor(js.int(js.get(file, "fd")), uri, name)
            return imported
        } finally {
            try { js.call(fs, "closeSync", file) }
            catch (error: Throwable) { imported?.close(); throw error }
        }
    }
}

private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
private fun JsonObject.count(): Int = (get("maxItems")?.jsonPrimitive?.intOrNull ?: 1).also {
    require(it > 0) { "maxItems must be positive" }
}
