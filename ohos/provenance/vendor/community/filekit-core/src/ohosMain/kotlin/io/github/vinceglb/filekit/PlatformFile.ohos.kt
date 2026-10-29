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

/** A filesystem path or an explicitly imported system picker descriptor. */
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

    /** Releases an imported picker descriptor. Existing streams remain valid. Local paths are unaffected. */
    public fun close(): Unit { descriptor?.close() }

    public actual override fun toString(): String = path
    override fun equals(other: Any?): Boolean = other is PlatformFile && path == other.path
    override fun hashCode(): Int = path.hashCode()
    public actual companion object {
        /** Duplicates fd; the caller retains ownership of the original. Close this file after use. */
        public fun fromFileDescriptor(fd: Int, uri: String, name: String): PlatformFile {
            require(fd >= 0 && uri.isNotBlank() && name.isNotBlank())
            val duplicate = dup(fd)
            if (duplicate < 0) throw FileKitException("Cannot duplicate picker descriptor")
            return PlatformFile(Path("/proc/self/fd/$duplicate"), Unit, OhosDescriptor(duplicate, uri, name))
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

// Unlike realpath/resolve, this also works for a destination that does not yet exist.
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

// POSIX stat exposes change time, not creation time. Do not misreport ctime as birth time.
public actual fun PlatformFile.createdAt(): Instant? = null

public actual fun PlatformFile.lastModified(): Instant = memScoped {
    val info = alloc<stat>()
    if ((descriptor?.let { fstat(it.fd, info.ptr) } ?: stat(path, info.ptr)) != 0) {
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
