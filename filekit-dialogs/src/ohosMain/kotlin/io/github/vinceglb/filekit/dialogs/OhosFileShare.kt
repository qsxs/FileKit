package io.github.vinceglb.filekit.dialogs

import androidx.compose.ui.adaptive.share.ShareConfig
import androidx.compose.ui.adaptive.share.ShareContentType
import androidx.compose.ui.adaptive.share.ShareItem
import androidx.compose.ui.adaptive.share.ShareManager
import androidx.compose.ui.adaptive.share.ShareResult
import androidx.compose.ui.adaptive.share.ShareSelectionMode
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.createDirectories
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.div
import io.github.vinceglb.filekit.lastModified
import io.github.vinceglb.filekit.list
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.sink
import io.github.vinceglb.filekit.source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/** 随当前 Compose 控制器持有分享请求，所有会话状态都在主线程读写。 */
internal class OhosFileShare(
    private val manager: ShareManager,
    private val cacheRoot: PlatformFile,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var request: Deferred<Unit>? = null

    val isBusy: Boolean
        get() = request?.isCompleted == false

    suspend fun share(files: List<PlatformFile>) {
        if (isBusy) throw FileKitDialogException("Another FileKit share is active")
        if (!manager.isShareAvailable()) throw FileKitDialogException("System share is unavailable")
        require(files.size <= MAX_RECORDS) { "OHOS system share supports up to $MAX_RECORDS files" }

        var dispatched = false
        val operation = scope.async(start = CoroutineStart.LAZY) {
            shareFromCache(files) { dispatched = true }
        }
        request = operation
        try {
            operation.await()
        } finally {
            // 页面退出可以取消准备。系统面板已打开时，由宿主继续等待回执。
            if (!dispatched) operation.cancel()
        }
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun shareFromCache(files: List<PlatformFile>, onDispatch: () -> Unit) {
        val root = cacheRoot / "filekit-system-share"
        val directory = root / Uuid.random().toString()
        var ownsDirectory = false
        var keepForReceiver = false
        var failure: Throwable? = null
        try {
            val items = withContext(Dispatchers.IO) {
                root.createDirectories()
                pruneExpiredCopies(root)
                directory.createDirectories(mustCreate = true)
                ownsDirectory = true
                files.mapIndexed { index, file ->
                    val name = file.name
                    require(
                        name.isNotBlank() && name != "." && name != ".." && name.none {
                            it == '/' || it == '\\' || it.isISOControl()
                        }
                    ) { "Invalid share filename" }
                    val targetDirectory = directory / index.toString()
                    targetDirectory.createDirectories(mustCreate = true)
                    val target = targetDirectory / name
                    copyFile(file, target)
                    ShareItem(type = ShareContentType.FILE, uri = "file://${target.path}")
                }
            }
            currentCoroutineContext().ensureActive()
            onDispatch()
            keepForReceiver = true
            val config = ShareConfig(
                selectionMode = if (items.size == 1) ShareSelectionMode.SINGLE else ShareSelectionMode.BATCH,
            )
            when (val result = manager.shareMultiple(items, config)) {
                is ShareResult.Completed, ShareResult.Cancelled -> {
                    Unit
                }

                is ShareResult.Failed -> {
                    keepForReceiver = false
                    throw FileKitDialogException("OHOS system share failed: ${result.error}")
                }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            // 系统回执与发送方取消都可能先于目标应用打开 URI。
            if (ownsDirectory && !keepForReceiver) {
                try {
                    withContext(NonCancellable) { directory.delete(mustExist = false, recursively = true) }
                } catch (cleanupError: Exception) {
                    failure?.addSuppressed(cleanupError)
                }
            }
        }
    }

    private suspend fun pruneExpiredCopies(root: PlatformFile) {
        val cutoff = Clock.System.now() - 1.days
        val entries = runCatching { root.list() }.getOrDefault(emptyList())
        entries.forEach { entry ->
            currentCoroutineContext().ensureActive()
            if (Uuid.parseOrNull(entry.name) != null &&
                runCatching { entry.lastModified() < cutoff }.getOrDefault(false)
            ) {
                try {
                    entry.delete(mustExist = false, recursively = true)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // 系统也可能回收缓存，清理失败留到下一次分享重试。
                }
            }
        }
    }

    private suspend fun copyFile(source: PlatformFile, destination: PlatformFile) {
        currentCoroutineContext().ensureActive()
        source.source().use { input ->
            destination.sink().use { output ->
                val buffer = Buffer()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.readAtMostTo(buffer, 8_192)
                    if (count == -1L) break
                    output.write(buffer, count)
                }
                output.flush()
            }
        }
    }

    private companion object {
        const val MAX_RECORDS = 500
    }
}
