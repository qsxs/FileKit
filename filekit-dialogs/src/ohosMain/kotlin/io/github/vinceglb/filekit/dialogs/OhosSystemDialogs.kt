@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.vinceglb.filekit.dialogs

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.exceptions.FileKitException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import platform.ArkTS.ArkTS_Napi_NativeModule.*

internal expect fun initializeFileKitMainHandler(env: napi_env)

/** 在所属 UIAbility 的 ArkTS UI 线程初始化；重新初始化会结束旧宿主的未交付请求。 */
public fun FileKit.init(env: napi_env, context: napi_value, shareFactory: napi_value): FileKitDialogSession {
    initializeFileKitMainHandler(env)
    return OhosSystemDialogs.init(env, context, shareFactory)
}

/** 每个 UIAbility 保存自己 init 返回的 session，在同一 ArkTS UI 线程销毁。 */
public class FileKitDialogSession internal constructor(private var onDispose: (() -> Unit)?) {
    public fun dispose() {
        val cleanup = onDispose ?: return
        onDispose = null
        cleanup()
    }
}

internal object OhosSystemDialogs {
    private var implementation: Implementation? = null
    private var currentSession: FileKitDialogSession? = null

    fun init(env: napi_env, context: napi_value, shareFactory: napi_value): FileKitDialogSession {
        currentSession?.dispose()
        val owner = Implementation(env, context, shareFactory)
        val session = FileKitDialogSession {
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

    private suspend fun callOnMain(
        request: JsonObject,
        owner: () -> Implementation?,
    ): List<PlatformFile>? {
        var undelivered: List<PlatformFile>? = null
        try {
            val result = withContext(Dispatchers.Main) {
                (owner() ?: throw FileKitException("Call FileKit.init(env, context, shareFactory) first"))
                    .call(request).also { undelivered = it }
            }
            undelivered = null
            return result
        } finally {
            // Main 已取得结果但返回原 dispatcher 时取消，也必须归还未交付 owner。
            undelivered?.forEach { it.close() }
        }
    }
}

// Promise 的 JS 函数持有 Pending，完成或 dispose 后清空其重资源闭包。
// StableRef 只由 JS finalizer 释放，晚回调不访问旧 context、不打开新的 FD。
private class Pending(
    private var continuation: CancellableContinuation<List<PlatformFile>?>?,
    private var decode: ((napi_value) -> List<PlatformFile>?)?,
    private var finished: (() -> Unit)?,
) {
    private var settled = false
    var failed: (() -> Unit)? = null

    fun abandon() {
        val previous = continuation
        continuation = null
        decode = null
        finished = null
        failed = null
        previous?.cancel()
    }

    fun complete(value: napi_value?, error: Throwable?) {
        if (settled) return
        settled = true
        val target = continuation
        val decoder = decode
        val onFinished = finished
        val onFailure = failed
        continuation = null
        decode = null
        finished = null
        failed = null
        var files: List<PlatformFile>? = null
        try {
            onFinished?.invoke()
            if (error != null) throw error
            if (target?.isActive == true && decoder != null) {
                files = decoder(requireNotNull(value))
                target.resume(files) { _, abandoned, _ -> abandoned?.forEach { it.close() } }
                files = null
            }
        } catch (failure: Throwable) {
            files?.forEach { it.close() }
            try { onFailure?.invoke() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            if (target?.isActive == true) target.resumeWith(Result.failure(failure))
        }
    }
}

private class Implementation(env: napi_env, context: napi_value, shareFactory: napi_value) {
    private val js = OhosNapi(env)
    private val contextRef = js.reference(context)
    private val factoryRef = try {
        js.reference(shareFactory)
    } catch (error: Throwable) {
        try { js.check(napi_delete_reference(env, contextRef)) }
        catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
        throw error
    }
    private var disposed = false
    private var active: Pending? = null
    private val shares = mutableSetOf<SharePanel>()

    fun dispose() {
        if (disposed) return
        disposed = true
        var failure: Throwable? = null
        try {
            try { active?.abandon() }
            catch (error: Throwable) { failure = error }
            finally { active = null }
            shares.toList().forEach { panel ->
                try { panel.close() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure?.addSuppressed(error)
                }
            }
        } finally {
            shares.clear()
            try { js.check(napi_delete_reference(js.env, factoryRef)) }
            catch (error: Throwable) { if (failure == null) failure = error else failure?.addSuppressed(error) }
            try { js.check(napi_delete_reference(js.env, contextRef)) }
            catch (error: Throwable) { if (failure == null) failure = error else failure?.addSuppressed(error) }
        }
        failure?.let { throw it }
    }

    suspend fun call(request: JsonObject): List<PlatformFile>? = suspendCancellableCoroutine { continuation ->
        if (disposed || active != null) {
            continuation.resumeWith(Result.failure(FileKitException(
                if (disposed) "FileKit UIAbility has been destroyed" else "Another FileKit dialog is active"
            )))
            return@suspendCancellableCoroutine
        }
        lateinit var pending: Pending
        pending = Pending(continuation, { result -> js.scoped { decode(request, result) } }, {
            if (active === pending) active = null
        })
        active = pending
        try {
            js.scoped {
                val promise = start(request, pending)
                val success = js.callback { pending.complete(it, null) }
                // 回调只捕获轻量 NAPI 环境与 Pending；dispose 后 Pending 不再持有 Implementation。
                val napi = js
                val failure = js.callback { value -> pending.complete(null, FileKitException(napi.describeError(value))) }
                js.call(promise, "then", success, failure)
            }
        } catch (error: Throwable) {
            pending.complete(null, error)
        }
        // 取消不能替代系统 UI 的完成；保留未结 Promise，完成时检查 continuation 再决定是否导入。
    }

    private fun start(request: JsonObject, pending: Pending): napi_value {
        val context = js.dereference(contextRef)
        val options = js.obj()
        return when (val operation = request.text("operation")) {
            "pick", "save" -> {
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
                        js.set(options, "newFileNames", js.array(listOf(js.text(requireNotNull(request.text("name"))))))
                    } else {
                        js.set(options, "maxSelectNumber", js.number(request.count()))
                    }
                    val extensions = request["extensions"]?.jsonArray.orEmpty()
                    if (extensions.isNotEmpty()) js.set(options,
                        if (operation == "save") "fileSuffixChoices" else "fileSuffixFilters",
                        js.array(extensions.map { js.text(".${it.jsonPrimitive.content}") }))
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
            "share" -> {
                val paths = request["paths"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
                require(paths.isNotEmpty()) { "No files to share" }
                val factory = js.dereference(factoryRef)
                fun record(path: String): napi_value = js.obj().also {
                    js.set(it, "utd", js.text("general.file"))
                    js.set(it, "uri", uri(path))
                }
                val data = js.call(factory, "createSharedData", record(paths.first()))
                paths.drop(1).forEach { js.call(data, "addRecord", record(it)) }
                lateinit var panel: SharePanel
                panel = SharePanel(js, js.call(factory, "createShareController", data)) { shares.remove(panel) }
                shares.add(panel)
                pending.failed = { panel.close() }
                panel.show(context, options)
            }
            else -> throw FileKitException("Unknown FileKit operation: $operation")
        }
    }

    private fun decode(request: JsonObject, result: napi_value): List<PlatformFile>? {
        val operation = request.text("operation")
        if (operation == "open" || operation == "share") return emptyList()
        val uris = js.elements(if (operation == "pick" && request.text("type") != "file") js.get(result, "photoUris") else result)
            .map { js.string(it) }
        if (uris.isEmpty()) return null
        val maximum = if (operation == "pick") request.count() else 1
        if (uris.size > maximum) throw FileKitException("Picker exceeded maxItems")
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
            // fs.File.name 为真实打开文件的元数据；媒体 URI 不通过 FileUri.path 转换。
            val name = js.string(js.get(file, "name"))
            if (name.isBlank()) throw FileKitException("The opened file has no display name")
            imported = PlatformFile.fromFileDescriptor(js.int(js.get(file, "fd")), uri, name)
            return imported
        } finally {
            try { js.call(fs, "closeSync", file) }
            catch (error: Throwable) { imported?.close(); throw error }
        }
    }
}

private class SharePanel(
    private val js: OhosNapi,
    controller: napi_value,
    private var onClose: (() -> Unit)?,
) {
    private var controllerRef: napi_ref? = js.reference(controller)
    private var listenerRef: napi_ref? = null

    fun show(context: napi_value, options: napi_value): napi_value {
        val controller = js.dereference(requireNotNull(controllerRef))
        try {
            val listener = js.callback { close() }
            listenerRef = js.reference(listener)
            js.call(controller, "on", js.text("dismiss"), listener)
            // show 仅表示本地提交；dismiss 也不代表接收方已经读取。
            return js.call(controller, "show", context, options)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun close() {
        val controller = controllerRef ?: return
        val listener = listenerRef
        controllerRef = null
        listenerRef = null
        val callback = onClose
        onClose = null
        try {
            if (listener != null) js.scoped {
                js.call(js.dereference(controller), "off", js.text("dismiss"), js.dereference(listener))
            }
        } finally {
            if (listener != null) napi_delete_reference(js.env, listener)
            napi_delete_reference(js.env, controller)
            callback?.invoke()
        }
    }
}

private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
private fun JsonObject.count(): Int = (get("maxItems")?.jsonPrimitive?.intOrNull ?: 1).also {
    require(it > 0) { "maxItems must be positive" }
}
