@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.time.ExperimentalTime::class)

package io.github.vinceglb.filekit

import io.github.vinceglb.filekit.exceptions.FileKitException
import io.github.vinceglb.filekit.mimeType.MimeType
import kotlinx.cinterop.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import platform.posix.*
import kotlin.time.Instant

/** 文件系统路径或由系统选择器显式导入的描述符。 */
@Serializable(with = PlatformFileSerializer::class)
public actual class PlatformFile internal constructor(
    internal val ioPath: Path,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
    internal val descriptor: OhosDescriptor? = null,
) {
    init {
        require('\u0000' !in ioPath.toString()) { "File paths must not contain NUL" }
        require(!ioPath.toString().contains("://")) { "PlatformFile requires a filesystem path, not a URI" }
    }

    /** 释放导入的 owner 描述符；已打开的流继续有效，普通路径无需释放。 */
    public fun close(): Unit { descriptor?.close() }

    /** 获取独立所有权；关闭原对象不影响此借用或其已打开的流。 */
    public fun acquireLease(): PlatformFile = descriptor?.acquire()?.let { owned ->
        try { PlatformFile(ioPath, Unit, owned) } catch (error: Throwable) { owned.close(); throw error }
    } ?: this

    public actual override fun toString(): String = path
    override fun equals(other: Any?): Boolean = other is PlatformFile && path == other.path
    override fun hashCode(): Int = path.hashCode()
    public actual companion object {
        /** 复制 fd；原描述符仍归调用者所有，使用后关闭此文件对象。 */
        public fun fromFileDescriptor(fd: Int, uri: String, name: String): PlatformFile {
            require(fd >= 0 && uri.isNotBlank() && name.isNotBlank())
            val duplicate = dup(fd)
            if (duplicate < 0) throw FileKitException("Cannot duplicate picker descriptor")
            val owned = OhosDescriptor(duplicate, uri, name)
            return try { PlatformFile(Path("/proc/self/fd/$duplicate"), Unit, owned) }
            catch (error: Throwable) { owned.close(); throw error }
        }
    }
}

public actual fun PlatformFile(path: Path): PlatformFile = PlatformFile(path, Unit)
public actual fun PlatformFile.toKotlinxIoPath(): Path {
    check(descriptor == null) { "Picker URI has no sandbox path; use source()/sink() or copyTo()" }
    return ioPath
}
public actual val PlatformFile.extension: String get() = name.substringAfterLast('.', "")
public actual val PlatformFile.nameWithoutExtension: String get() = name.substringBeforeLast('.', name)

// 不要求目标已存在，尚未创建的目标也可以计算绝对路径。
public actual fun PlatformFile.absolutePath(): String {
    if (descriptor != null || isAbsolute()) return path
    return memScoped {
        val cwd = getcwd(null, 0u) ?: throw FileKitException("getcwd failed: ${strerror(errno)?.toKString()}")
        try {
            Path(cwd.toKString(), path).toString()
        } finally {
            free(cwd)
        }
    }
}

public actual inline fun PlatformFile.list(block: (List<PlatformFile>) -> Unit): Unit = block(list())
public actual fun PlatformFile.list(): List<PlatformFile> = SystemFileSystem.list(toKotlinxIoPath()).map(::PlatformFile)

// POSIX stat 的 ctime 是状态变更时间，不能当成创建时间。
public actual fun PlatformFile.createdAt(): Instant? = null

public actual fun PlatformFile.lastModified(): Instant = memScoped {
    val info = alloc<stat>()
    if ((descriptor?.borrowed { fstat(it, info.ptr) } ?: stat(path, info.ptr)) != 0) {
        throw FileKitException("Cannot stat '$path': ${strerror(errno)?.toKString()}")
    }
    Instant.fromEpochSeconds(info.st_mtim.tv_sec, info.st_mtim.tv_nsec)
}

public actual fun PlatformFile.mimeType(): MimeType? = when (extension.lowercase()) {
    "jpg", "jpeg", "jpe" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "heic" -> "image/heic"
    "heif" -> "image/heif"
    "bmp" -> "image/bmp"
    "svg" -> "image/svg+xml"
    "avif" -> "image/avif"
    "pdf" -> "application/pdf"
    "json" -> "application/json"
    "xml" -> "application/xml"
    "zip" -> "application/zip"
    "txt", "log" -> "text/plain"
    "csv" -> "text/csv"
    "html", "htm" -> "text/html"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "wav" -> "audio/wav"
    "flac" -> "audio/flac"
    "ogg" -> "audio/ogg"
    "mp4", "m4v" -> "video/mp4"
    "mov" -> "video/quicktime"
    "webm" -> "video/webm"
    else -> null
}?.let(MimeType::parse)

public actual fun PlatformFile.startAccessingSecurityScopedResource(): Boolean = true
public actual fun PlatformFile.stopAccessingSecurityScopedResource(): Unit = Unit
public actual suspend fun PlatformFile.bookmarkData(): BookmarkData {
    check(descriptor == null) { "Picker permissions are temporary; copy into the sandbox before bookmarking" }
    return BookmarkData(absolutePath().encodeToByteArray())
}
public actual fun PlatformFile.releaseBookmark(): Unit = Unit
public actual fun PlatformFile.Companion.fromBookmarkData(bookmarkData: BookmarkData): PlatformFile =
    PlatformFile(bookmarkData.bytes.decodeToString(throwOnInvalidSequence = true))

@PublishedApi
internal fun PlatformFile.withPath(path: Path): PlatformFile = PlatformFile(path)

public actual fun PlatformFile.Companion.resolveBookmarkData(bookmarkData: BookmarkData): BookmarkResolution =
    BookmarkResolution(fromBookmarkData(bookmarkData), isStale = false, shouldRefresh = false)

internal actual fun PlatformFile.deleteIfSymbolicLink(): Boolean = memScoped {
    val filePath = toKotlinxIoPath().toString()
    val info = alloc<stat>()
    if (lstat(filePath, info.ptr) != 0) {
        if (errno == ENOENT || errno == ENOTDIR) return@memScoped false
        throw kotlinx.io.IOException("Cannot inspect symbolic link: ${strerror(errno)?.toKString()}")
    }
    if ((info.st_mode.toInt() and S_IFMT) != S_IFLNK) return@memScoped false
    if (unlink(filePath) != 0) throw kotlinx.io.IOException("Cannot unlink symbolic link: ${strerror(errno)?.toKString()}")
    true
}
