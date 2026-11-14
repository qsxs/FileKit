@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.vinceglb.filekit.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.adaptive.share.ShareManager
import androidx.compose.ui.adaptive.share.rememberShareManager
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.FileKitOhosInternalApi
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.bindOhosGalleryHost
import io.github.vinceglb.filekit.exceptions.FileKitException
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.writeOhosGalleryFile
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.ArkTS.ArkTS_Napi_NativeModule.napi_delete_reference
import platform.ArkTS.ArkTS_Napi_NativeModule.napi_env
import platform.ArkTS.ArkTS_Napi_NativeModule.napi_value

internal expect fun initializeFileKitMainHandler(env: napi_env)

/** 在所属 UIAbility 的 ArkTS UI 线程初始化；重新初始化会结束旧宿主的未交付请求。 */
public fun FileKit.init(env: napi_env, context: napi_value): FileKitDialogSession {
    initializeFileKitMainHandler(env)
    return OhosSystemDialogs.init(env, context)
}

/** 每个 UIAbility 保存自己 init 返回的 session，在同一 ArkTS UI 线程销毁。 */
public class FileKitDialogSession internal constructor(
    private val bindShareManager: (ShareManager) -> (() -> Unit),
    private var onDispose: (() -> Unit)?,
) {
    /** 在宿主根组合绑定一次，随当前 Compose 控制器解绑。 */
    @Composable
    public fun BindSystemShare() {
        val manager = rememberShareManager()
        DisposableEffect(this, manager) {
            val unbind = bindShareManager(manager)
            onDispose { unbind() }
        }
    }

    public fun dispose() {
        val cleanup = onDispose ?: return
        onDispose = null
        cleanup()
    }
}

@OptIn(FileKitOhosInternalApi::class)
internal object OhosSystemDialogs {
    private var implementation: Implementation? = null
    private var currentSession: FileKitDialogSession? = null

    fun init(env: napi_env, context: napi_value): FileKitDialogSession {
        currentSession?.dispose()
        val owner = Implementation(env, context)
        val unbindGallery = FileKit.bindOhosGalleryHost { source, title, extension, isVideo ->
            withContext(Dispatchers.Main) {
                owner.mediaOperation(commitOnConfirmation = true) { awaitDialog ->
                    val request = buildJsonObject {
                        put("operation", "gallery")
                        put("path", source.path)
                        put("title", title)
                        put("extension", extension)
                        put("type", if (isVideo) "VIDEO" else "IMAGE")
                    }
                    val destination = awaitDialog(request)?.single()
                        ?: throw FileKitException("The user declined to save the media to the OHOS gallery")
                    // 用户已确认且媒体库已经创建目标，回收写权限前完成或报告写入错误。
                    try {
                        withContext(NonCancellable) { writeOhosGalleryFile(source, destination) }
                    } finally {
                        destination.close()
                    }
                }
            }
        }
        val session = FileKitDialogSession(owner::bindShareManager) {
            unbindGallery()
            // 按实际对象身份撤销；旧 Ability 的晚到销毁不能影响新宿主。
            if (implementation === owner) {
                implementation = null
                currentSession = null
            }
            owner.dispose()
        }
        implementation = owner
        currentSession = session
        return session
    }

    suspend fun captureOwnerCall(): suspend (JsonObject) -> List<PlatformFile>? {
        // 工厂返回冷 Flow 前在 Main 固定 owner；null 也固定，之后不能改查新宿主。
        val captured: Result<Implementation?> = try {
            Result.success(withContext(Dispatchers.Main) { implementation })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // 捕获失败仍在 collect 经既有错误契约交付，不在工厂提前抛普通错误。
            Result.failure(error)
        }
        return { request -> callOnMain(request) { captured.getOrThrow() } }
    }

    suspend fun call(request: JsonObject): List<PlatformFile>? =
        callOnMain(request) { implementation }

    suspend fun <T> captureMediaOperation(): suspend (suspend (AwaitMediaDialog) -> T) -> T {
        val owner = withContext(Dispatchers.Main) { implementation }
            ?: throw FileKitException("Call FileKit.init(env, context) first")
        return { block -> withContext(Dispatchers.Main) { owner.mediaOperation(block = block) } }
    }

    suspend fun share(files: List<PlatformFile>) = withContext(Dispatchers.Main) {
        (implementation ?: throw FileKitDialogException("FileKit UIAbility is unavailable"))
            .share(files)
    }

    private suspend fun callOnMain(
        request: JsonObject,
        owner: () -> Implementation?,
    ): List<PlatformFile>? {
        var undelivered: List<PlatformFile>? = null
        try {
            val result = withContext(Dispatchers.Main) {
                (owner() ?: throw FileKitException("Call FileKit.init(env, context) first"))
                    .call(request)
                    .also { undelivered = it }
            }
            undelivered = null
            return result
        } finally {
            // Main 已取得结果但返回原 dispatcher 时取消，也必须归还未交付 owner。
            undelivered?.forEach { it.close() }
        }
    }
}

internal typealias AwaitMediaDialog = suspend (JsonObject) -> List<PlatformFile>?

// Promise 的 JS 函数持有 Pending，完成或 dispose 后清空其重资源闭包。
// StableRef 只由 JS finalizer 释放，晚回调不访问旧 context、不打开新的 FD。
private class Pending(
    private var continuation: CancellableContinuation<List<PlatformFile>?>?,
    private var decode: ((napi_value) -> List<PlatformFile>?)?,
    private var finished: (() -> Unit)?,
    private var shouldDecode: (() -> Boolean)?,
) {
    private var settled = false

    fun abandon() {
        val previous = continuation
        continuation = null
        decode = null
        finished = null
        shouldDecode = null
        previous?.cancel()
    }

    fun complete(value: napi_value?, error: Throwable?) {
        if (settled) return
        settled = true
        val target = continuation
        val decoder = decode
        val onFinished = finished
        val acceptsResult = shouldDecode
        continuation = null
        decode = null
        finished = null
        shouldDecode = null
        var files: List<PlatformFile>? = null
        try {
            onFinished?.invoke()
            if (error != null) throw error
            if (target?.isActive == true && decoder != null) {
                if (acceptsResult?.invoke() != false) files = decoder(requireNotNull(value))
                target.resume(files) { _, abandoned, _ -> abandoned?.forEach { it.close() } }
                files = null
            }
        } catch (failure: Throwable) {
            files?.forEach { it.close() }
            if (target?.isActive == true) target.resumeWith(Result.failure(failure))
        }
    }
}

private class Implementation(
    env: napi_env,
    context: napi_value
) {
    private val js = OhosNapi(env)
    private val contextRef = js.reference(context)
    private var disposed = false
    private var active: Pending? = null
    private var shareSession: OhosFileShare? = null
    private var mediaInFlight: Job? = null

    fun bindShareManager(manager: ShareManager): () -> Unit {
        if (disposed) return {}
        shareSession?.close()
        val cacheDir = js.scoped { js.string(js.get(js.dereference(contextRef), "cacheDir")) }
        val binding = OhosFileShare(manager, PlatformFile(cacheDir))
        shareSession = binding
        return {
            if (shareSession === binding) shareSession = null
            binding.close()
        }
    }

    suspend fun share(files: List<PlatformFile>) {
        if (active != null || mediaInFlight != null) throw FileKitDialogException("Another FileKit dialog is active")
        val session = shareSession ?: throw FileKitDialogException("FileKit system share is not bound to the UIAbility")
        session.share(files)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        shareSession?.close()
        shareSession = null
        try {
            mediaInFlight?.cancel(CancellationException("FileKit UIAbility has been destroyed"))
            active?.abandon()
        } finally {
            active = null
            js.check(napi_delete_reference(js.env, contextRef))
        }
    }

    suspend fun <T> mediaOperation(
        commitOnConfirmation: Boolean = false,
        block: suspend (AwaitMediaDialog) -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        checkAvailable()
        val caller = currentCoroutineContext()[Job]
        // 系统使用暂存文件时，取消需等待 Promise 结束或宿主销毁才能清理文件。
        val operation = CoroutineScope(Dispatchers.Main).async(start = CoroutineStart.LAZY) {
            block { request ->
                caller?.ensureActive()
                val files = call(request) { commitOnConfirmation || caller?.isActive != false }
                if (!commitOnConfirmation && caller?.isActive == false) {
                    files?.forEach { it.close() }
                    caller.ensureActive()
                }
                files
            }
        }
        mediaInFlight = operation
        try {
            return operation.await()
        } finally {
            // UI 尚未打开或已返回时可以停止相机提交准备。已确认的相册保存由宿主完成。
            if (active == null && !commitOnConfirmation) {
                operation.cancel()
            }
            withContext(NonCancellable) {
                operation.join()
                if (mediaInFlight === operation) mediaInFlight = null
            }
        }
    }

    suspend fun call(request: JsonObject, mediaCaller: (() -> Boolean)? = null): List<PlatformFile>? =
        suspendCancellableCoroutine { continuation ->
            try {
                checkAvailable(isMedia = mediaCaller != null)
            } catch (error: FileKitException) {
                continuation.resumeWith(Result.failure(error))
                return@suspendCancellableCoroutine
            }
            lateinit var pending: Pending
            pending = Pending(
                continuation = continuation,
                decode = { result -> js.scoped { decode(request, result) } },
                finished = { if (active === pending) active = null },
                shouldDecode = mediaCaller,
            )
            active = pending
            try {
                js.scoped {
                    val promise = start(request)
                    val success = js.callback { pending.complete(it, null) }
                    // 回调只捕获轻量 NAPI 环境与 Pending；dispose 后 Pending 不再持有 Implementation。
                    val napi = js
                    val isCamera = request.text("operation") == "camera"
                    val failure = js.callback { value ->
                        pending.complete(null, if (isCamera && napi.errorCodeOrNull(value) == PERMISSION_DENIED) {
                            CameraPermissionDenied()
                        } else {
                            FileKitException(napi.describeError(value))
                        })
                    }
                    js.call(promise, "then", success, failure)
                }
            } catch (error: Throwable) {
                pending.complete(null, error)
            }
            // 取消不能替代系统 UI 的完成；保留未结 Promise，完成时检查 continuation 再决定是否导入。
        }

    private fun checkAvailable(isMedia: Boolean = false) {
        if (disposed) throw FileKitException("FileKit UIAbility has been destroyed")
        if (active != null || shareSession?.isBusy == true || !isMedia && mediaInFlight != null) {
            throw FileKitException("Another FileKit dialog is active")
        }
    }

    private fun start(request: JsonObject): napi_value {
        val context = js.dereference(contextRef)
        val options = js.obj()
        return when (val operation = request.text("operation")) {
            "pick", "save", "directory" -> {
                val type = request.text("type")
                if (operation == "pick" && type != "file") {
                    val photos = js.module("@ohos.file.photoAccessHelper")
                    js.set(options, "maxSelectNumber", js.number(request.count()))
                    val mime = when (type) {
                        "image" -> "IMAGE_TYPE"
                        "video" -> "VIDEO_TYPE"
                        else -> "IMAGE_VIDEO_TYPE"
                    }
                    js.set(options, "MIMEType", js.get(js.get(photos, "PhotoViewMIMETypes"), mime))
                    js.call(js.construct(photos, "PhotoViewPicker"), "select", options)
                } else {
                    val picker = js.module("@ohos.file.picker")
                    request.text("directory")?.let { js.set(options, "defaultFilePathUri", uri(it)) }
                    when (operation) {
                        "save" -> js.set(options, "newFileNames", js.array(listOf(js.text(requireNotNull(request.text("name"))))))
                        "directory" -> {
                            js.requireCapability("SystemCapability.FileManagement.UserFileService.FolderSelection")
                            js.set(options, "selectMode", js.get(js.get(picker, "DocumentSelectMode"), "FOLDER"))
                            js.set(options, "maxSelectNumber", js.number(1))
                        }
                        else -> js.set(options, "maxSelectNumber", js.number(request.count()))
                    }
                    val extensions = request["extensions"]?.jsonArray.orEmpty()
                    if (extensions.isNotEmpty()) {
                        js.set(
                            options,
                            if (operation == "save") "fileSuffixChoices" else "fileSuffixFilters",
                            js.array(extensions.map { js.text(".${it.jsonPrimitive.content}") })
                        )
                    }
                    js.call(js.construct(picker, "DocumentViewPicker", context), if (operation == "save") "save" else "select", options)
                }
            }

            "open" -> {
                js.set(options, "action", js.text("ohos.want.action.viewData"))
                js.set(options, "uri", uri(requireNotNull(request.text("path"))))
                js.set(options, "type", js.text(request.text("mime") ?: "application/octet-stream"))
                val want = js.module("@ohos.app.ability.wantConstant")
                js.set(options, "flags", js.get(js.get(want, "Flags"), "FLAG_AUTH_READ_URI_PERMISSION"))
                js.call(context, "startAbility", options)
            }

            "camera" -> {
                js.requireCapability("SystemCapability.Multimedia.Camera.Core")
                val cameraPicker = js.module("@ohos.multimedia.cameraPicker")
                val camera = js.module("@ohos.multimedia.camera")
                val facing = requireNotNull(request.text("facing"))
                val position = js.get(js.get(camera, "CameraPosition"), facing)
                js.set(options, "cameraPosition", position)
                js.set(options, "saveUri", uri(requireNotNull(request.text("path"))))
                val photo = js.get(js.get(cameraPicker, "PickerMediaType"), "PHOTO")
                js.call(cameraPicker, "pick", context, js.array(listOf(photo)), options)
            }

            "gallery" -> {
                js.requireCapability("SystemCapability.FileManagement.PhotoAccessHelper.Core")
                val photos = js.module("@ohos.file.photoAccessHelper")
                val helper = js.call(photos, "getPhotoAccessHelper", context)
                js.set(options, "title", js.text(requireNotNull(request.text("title"))))
                js.set(options, "fileNameExtension", js.text(requireNotNull(request.text("extension"))))
                val type = requireNotNull(request.text("type"))
                js.set(options, "photoType", js.get(js.get(photos, "PhotoType"), type))
                val source = uri(requireNotNull(request.text("path")))
                js.call(helper, "showAssetsCreationDialog", js.array(listOf(source)), js.array(listOf(options)))
            }

            else -> {
                throw FileKitException("Unknown FileKit operation: $operation")
            }
        }
    }

    private fun decode(request: JsonObject, result: napi_value): List<PlatformFile>? {
        val operation = request.text("operation")
        if (operation == "open") return emptyList()
        if (operation == "camera") return decodeCamera(request, result)
        val uris = js
            .elements(if (operation == "pick" && request.text("type") != "file") js.get(result, "photoUris") else result)
            .map { js.string(it) }
        if (uris.isEmpty()) return null
        val maximum = if (operation == "pick") request.count() else 1
        if (uris.size > maximum) throw FileKitException("Picker exceeded maxItems")
        val files = mutableListOf<PlatformFile>()
        try {
            for (uri in uris) {
                val file = when (operation) {
                    "gallery" -> opened(uri, "WRITE_ONLY", "${request.text("title")}.${request.text("extension")}")
                    "save" -> opened(uri, "READ_WRITE")
                    "directory" -> opened(uri, "READ_ONLY", directory = true)
                    else -> opened(uri, "READ_ONLY")
                }
                files += file
            }
            return files
        } catch (error: Throwable) {
            files.forEach { it.close() }
            throw error
        }
    }

    private fun decodeCamera(request: JsonObject, result: napi_value): List<PlatformFile>? {
        if (!js.isObject(result)) {
            throw FileKitDialogException("The OHOS camera did not return a capture result")
        }
        val status = js.int(js.get(result, "resultCode"))
        val outputUri = js.string(js.get(result, "resultUri"))
        val staged = PlatformFile(requireNotNull(request.text("path")))
        // 系统相机扩展销毁时，系统会以 0 和空 URI 结束尚未交付的请求。
        if (outputUri.isEmpty() && (status == CAMERA_OK || status == PERMISSION_DENIED)) return null
        if (status != CAMERA_OK || outputUri != js.string(uri(staged.path)) ||
            js.string(js.get(result, "mediaType")) != "photo"
        ) {
            throw FileKitDialogException("The OHOS camera returned an invalid capture result: $status")
        }
        return listOf(staged)
    }

    private fun uri(path: String): napi_value = if ("://" in path) {
        js.text(path)
    } else {
        js.call(js.module("@ohos.file.fileuri"), "getUriFromPath", js.text(path))
    }

    private fun opened(
        uri: String,
        modeName: String,
        displayName: String? = null,
        directory: Boolean = false,
    ): PlatformFile {
        val fs = js.module("@ohos.file.fs")
        val modes = js.get(fs, "OpenMode")
        val baseMode = js.get(modes, modeName)
        val mode = if (directory) js.number(js.int(baseMode) or js.int(js.get(modes, "DIR"))) else baseMode
        val file = js.call(fs, "openSync", js.text(uri), mode)
        var imported: PlatformFile? = null
        try {
            // fs.File.name 为真实打开文件的元数据；媒体 URI 不通过 FileUri.path 转换。
            val name = displayName ?: js.string(js.get(file, "name"))
            if (name.isBlank()) throw FileKitException("The opened file has no display name")
            val fd = js.int(js.get(file, "fd"))
            imported = if (directory) {
                // DocumentViewPicker 选中目录后授予临时读写权限，具体操作由内核检查。
                PlatformFile.fromDirectoryDescriptor(fd, uri, name, writeAllowed = true)
            } else {
                PlatformFile.fromFileDescriptor(fd, uri, name)
            }
            return imported
        } finally {
            try {
                js.call(fs, "closeSync", file)
            } catch (error: Throwable) {
                imported?.close()
                throw error
            }
        }
    }
}

internal class CameraPermissionDenied : FileKitException("OHOS camera permission was denied")

private const val CAMERA_OK = 0
private const val PERMISSION_DENIED = 201

private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull

private fun JsonObject.count(): Int = (get("maxItems")?.jsonPrimitive?.intOrNull ?: 1).also {
    require(it > 0) { "maxItems must be positive" }
}
