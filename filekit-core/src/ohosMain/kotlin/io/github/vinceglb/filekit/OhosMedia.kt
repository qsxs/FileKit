@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.vinceglb.filekit

import io.github.vinceglb.filekit.exceptions.FileKitException
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import platform.posix.ENOENT
import platform.posix.close
import platform.posix.errno
import platform.posix.mkstemp
import platform.posix.mkstemps
import platform.posix.strerror
import platform.posix.unlink
import kotlin.concurrent.atomics.AtomicReference

/** 鸿蒙 core 与 dialogs 之间的内部连接点。调用方通过 FileKit.init 使用公开接口。 */
@RequiresOptIn(
    message = "This API is internal to FileKit OHOS modules and may change without notice.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
public annotation class FileKitOhosInternalApi

private typealias SaveGalleryAsset = suspend (PlatformFile, String, String, Boolean) -> Unit

private val galleryHost = AtomicReference<SaveGalleryAsset?>(null)

/** 注册当前前台 UIAbility。解绑时按注册身份比较，避免销毁较新的宿主。 */
@FileKitOhosInternalApi
public fun FileKit.bindOhosGalleryHost(
    save: suspend (source: PlatformFile, title: String, extension: String, isVideo: Boolean) -> Unit,
): () -> Unit {
    galleryHost.store(save)
    return { galleryHost.compareAndSet(save, null) }
}

internal suspend fun saveOhosGalleryAsset(
    filename: String,
    isVideo: Boolean,
    prepare: suspend (PlatformFile) -> Unit,
) {
    val extension = filename.substringAfterLast('.', "")
    val title = filename.substringBeforeLast('.')
    require(
        title.isNotBlank() && extension.isNotEmpty() && filename.length <= 255 &&
            filename.none { it.isISOControl() || it in invalidMediaFilenameCharacters },
    ) { "A valid gallery filename and extension are required" }
    require(extension.all { it.isLetterOrDigit() }) { "Invalid gallery file extension" }

    val save = galleryHost.load() ?: throw FileKitException("FileKit UIAbility is unavailable for gallery saving")
    withOhosMediaTemporaryFile(FileKit.cacheDir, ".$extension") { staged ->
        prepare(staged)
        withContext(Dispatchers.IO) {
            if (staged.size() <= 0) throw FileKitException("Cannot save an empty file to the OHOS gallery")
        }
        save(staged, title, extension, isVideo)
    }
}

private const val invalidMediaFilenameCharacters = "\\/:*?\"'`<>|{}[]"

/** 创建独占临时文件。系统对话仍使用它时，必须由会话等待回执。 */
@FileKitOhosInternalApi
public suspend fun <T> withOhosMediaTemporaryFile(
    directory: PlatformFile,
    suffix: String = "",
    block: suspend (PlatformFile) -> T,
): T {
    require(suffix.isEmpty() || suffix.startsWith('.') && suffix.drop(1).all { it.isLetterOrDigit() })
    var staged: PlatformFile? = null
    var failure: Throwable? = null
    try {
        withContext(Dispatchers.IO) {
            staged = newTemporaryFile(directory.toKotlinxIoPath().toString(), suffix)
        }
        return block(checkNotNull(staged))
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        staged?.let { file ->
            try {
                withContext(Dispatchers.IO + NonCancellable) {
                    if (unlink(file.path) != 0 && errno != ENOENT) throw mediaIoError("Cannot remove media staging file")
                }
            } catch (cleanupError: Exception) {
                val previousFailure = failure
                if (previousFailure != null) previousFailure.addSuppressed(cleanupError) else throw cleanupError
            }
        }
    }
}

private fun newTemporaryFile(directory: String, suffix: String): PlatformFile = memScoped {
    val name: CPointer<ByteVar> = "$directory/.filekit-media-XXXXXX$suffix".cstr.getPointer(this)
    val fd = if (suffix.isEmpty()) mkstemp(name) else mkstemps(name, suffix.encodeToByteArray().size)
    if (fd < 0) throw mediaIoError("Cannot create media staging file")
    val path = name.toKString()
    if (close(fd) != 0) {
        val error = mediaIoError("Cannot close media staging file")
        unlink(path)
        throw error
    }
    PlatformFile(path)
}

/** 流式复制成功后同步写入结果。调用方在打开目标前定义发布边界。 */
@FileKitOhosInternalApi
public suspend fun copyOhosMediaFile(source: PlatformFile, destination: PlatformFile): Unit =
    copyOhosMediaFile(source) { destination.sink() }

internal suspend fun copyOhosMediaFile(source: PlatformFile, openOutput: () -> RawSink): Unit =
    withContext(Dispatchers.IO) {
        source.source().use { input ->
            currentCoroutineContext().ensureActive()
            openOutput().use { output ->
                val buffer = Buffer()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.readAtMostTo(buffer, 8_192L)
                    if (count == -1L) break
                    output.write(buffer, count)
                }
                output.flush()
            }
        }
    }

/** 将系统刚创建的媒体写入仅有写权限的 FD，成功须等最后一次 close 返回。 */
@FileKitOhosInternalApi
public suspend fun writeOhosGalleryFile(source: PlatformFile, destination: PlatformFile) {
    val descriptor = checkNotNull(destination.descriptor) { "Expected an OHOS gallery descriptor" }
    try {
        // 媒体库已创建空目标。使用 WRITE_ONLY 打开，跳过普通文件 sink 的 ftruncate。
        copyOhosMediaFile(source, descriptor::newMediaSink)
    } catch (error: Throwable) {
        try {
            descriptor.closeChecked()
        } catch (cleanupError: Exception) {
            error.addSuppressed(cleanupError)
        }
        throw error
    }
    descriptor.closeChecked()
}

private fun mediaIoError(message: String): FileKitException = FileKitException("$message: ${strerror(errno)?.toKString()}")
