@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
)

package io.github.vinceglb.filekit

import kotlinx.cinterop.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import platform.posix.*
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.native.ref.createCleaner
import kotlin.time.Instant

internal class OhosDirectory private constructor(
    private val root: DirectoryRoot,
    internal val segments: List<String>,
) {
    private val lease = DirectoryLease(root)
    @Suppress("unused")
    private val cleaner = createCleaner(lease) { it.close() }

    internal val uri: String get() = root.descriptor.uri
    internal val rootName: String get() = root.descriptor.name
    internal val path: String get() = segments.fold(uri.trimEnd('/')) { parent, name -> "$parent/${encodeSegment(name)}" }
    internal val name: String get() = segments.lastOrNull() ?: rootName
    internal val writeAllowed: Boolean get() = root.writeAllowed

    internal fun close() = lease.close()
    internal fun acquire(): OhosDirectory = derive(segments)

    internal fun child(relativePath: String): OhosDirectory = derive(segments + directorySegments(relativePath))

    internal fun parent(): OhosDirectory? = if (segments.isEmpty()) null else derive(segments.dropLast(1))

    private fun derive(segments: List<String>): OhosDirectory {
        lease.requireOpen()
        root.retain()
        return OhosDirectory(root, segments)
    }

    internal fun ensureAccessible() {
        if (attributes() == null) throw IOException("The OHOS bookmark target no longer exists")
    }

    internal fun isDirectory(): Boolean = attributes()?.mode == S_IFDIR
    internal fun isRegularFile(): Boolean = attributes()?.mode == S_IFREG
    internal fun exists(): Boolean = lease.isOpen && attributes() != null
    internal fun size(): Long = attributes()?.size ?: -1
    internal fun lastModified(): Instant = attributes()?.modified ?: throw IOException("The OHOS entry no longer exists")

    internal fun names(): List<String> = inDirectory(segments) { fd ->
        // 用独立 open file description 重置目录偏移，并把这一个 FD 的关闭权交给 DIR。
        val iteratorFd = openDirectory(fd, ".")
        val directory = fdopendir(iteratorFd)
        if (directory == null) {
            val error = directoryError("open directory stream")
            platform.posix.close(iteratorFd)
            throw error
        }
        try {
            buildList {
                while (true) {
                    set_posix_errno(0)
                    val entry = readdir(directory)
                    if (entry == null) {
                        if (errno != 0) throw directoryError("read directory")
                        break
                    }
                    val name = entry.pointed.d_name.toKString()
                    if (name != "." && name != "..") add(name)
                }
            }
        } finally {
            closedir(directory)
        }
    }

    internal fun source(): RawSource {
        val fd = openFile(O_RDONLY)
        return DescriptorSource(fd)
    }

    internal fun sink(append: Boolean): RawSink {
        requireWrite()
        val fd = openFile(O_WRONLY or O_CREAT or (if (append) O_APPEND else 0))
        if (!append && ftruncate(fd, 0) != 0) {
            val error = directoryError("truncate file")
            platform.posix.close(fd)
            throw error
        }
        return DescriptorSink(fd, checkedClose = true, append = append)
    }

    private fun openFile(flags: Int): Int = inParent { fd, name ->
        val opened = openat(fd, name, flags or O_CLOEXEC or O_NOFOLLOW or O_NONBLOCK, FILE_MODE)
        if (opened < 0) throw directoryError("open file")
        try {
            if (fstatEntry(opened).mode != S_IFREG) throw IOException("The OHOS entry is not a regular file")
            opened
        } catch (error: Throwable) {
            platform.posix.close(opened)
            throw error
        }
    }

    internal fun createDirectories(mustCreate: Boolean) {
        if (segments.isEmpty()) {
            lease.requireOpen()
            if (mustCreate) throw IOException("The selected OHOS directory already exists")
            return
        }
        requireWrite()
        traverse(segments, beforeOpen = { fd, name, isLast ->
            if (mkdirat(fd, name, DIRECTORY_MODE) != 0 && (errno != EEXIST || isLast && mustCreate)) {
                throw directoryError("create directory")
            }
        }) { }
    }

    internal suspend fun delete(mustExist: Boolean, recursively: Boolean) {
        requireWrite()
        if (segments.isEmpty()) throw IOException("The selected directory itself is outside the grant for its children")
        currentCoroutineContext().ensureActive()
        try {
            val type = attributes()?.mode ?: throw DirectoryIOException("inspect entry", ENOENT)
            if (type == S_IFDIR && recursively) {
                names().forEach { name ->
                    val child = child(name)
                    try {
                        child.delete(mustExist = false, recursively = true)
                    } finally {
                        child.close()
                    }
                }
            }
            inParent { fd, name ->
                if (unlinkat(fd, name, if (type == S_IFDIR) AT_REMOVEDIR else 0) != 0) {
                    throw directoryError("remove entry")
                }
            }
        } catch (error: DirectoryIOException) {
            if (mustExist || !error.isMissing) throw error
        }
    }

    internal fun moveTo(destination: OhosDirectory) {
        requireWrite()
        destination.requireWrite()
        if (uri != destination.uri) throw IOException("Moving between different OHOS directory grants is not supported")
        inParent { sourceFd, sourceName ->
            destination.inParent { destinationFd, destinationName ->
                if (renameat(sourceFd, sourceName, destinationFd, destinationName) != 0) throw directoryError("move entry")
            }
        }
    }

    private fun requireWrite() {
        lease.requireOpen()
        if (!writeAllowed) throw IOException("The OHOS directory grant only permits reading")
    }

    private fun attributes(): DirectoryAttributes? = try {
        if (segments.isEmpty()) {
            withRoot(::fstatEntry)
        } else {
            inParent { fd, name ->
                memScoped {
                    val info = alloc<stat>()
                    if (fstatat(fd, name, info.ptr, AT_SYMLINK_NOFOLLOW) != 0) throw directoryError("inspect entry")
                    info.toAttributes()
                }
            }
        }
    } catch (error: DirectoryIOException) {
        if (!error.isMissing) throw error
        null
    }

    private fun <T> inParent(block: (Int, String) -> T): T {
        check(segments.isNotEmpty()) { "The selected OHOS directory has no authorized parent" }
        return inDirectory(segments.dropLast(1)) { fd -> block(fd, segments.last()) }
    }

    private fun <T> inDirectory(path: List<String>, block: (Int) -> T): T =
        traverse(path, beforeOpen = { _, _, _ -> }, block)

    private fun <T> traverse(
        path: List<String>,
        beforeOpen: (Int, String, Boolean) -> Unit,
        block: (Int) -> T,
    ): T = withRoot { rootFd ->
        var fd = rootFd
        try {
            path.forEachIndexed { index, component ->
                beforeOpen(fd, component, index == path.lastIndex)
                val next = openDirectory(fd, component)
                if (fd != rootFd) platform.posix.close(fd)
                fd = next
            }
            block(fd)
        } finally {
            if (fd != rootFd) platform.posix.close(fd)
        }
    }

    private fun <T> withRoot(block: (Int) -> T): T {
        lease.requireOpen()
        return root.descriptor.borrowed(block)
    }

    internal companion object {
        internal fun create(owned: OhosDescriptor, writeAllowed: Boolean): OhosDirectory {
            try {
                if (owned.borrowed(::fstatEntry).mode != S_IFDIR) throw IOException("The selected OHOS entry is not a directory")
                return OhosDirectory(DirectoryRoot(owned, writeAllowed), emptyList())
            } catch (error: Throwable) {
                owned.close()
                throw error
            }
        }
    }
}

private class DirectoryLease(private val root: DirectoryRoot) {
    private val closed = AtomicBoolean(false)
    val isOpen: Boolean get() = !closed.load()
    fun requireOpen() = check(isOpen) { "The OHOS directory handle is closed" }
    fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true)) root.release()
    }
}

private class DirectoryRoot(val descriptor: OhosDescriptor, val writeAllowed: Boolean) {
    private val owners = AtomicInt(1)

    fun retain() {
        while (true) {
            val current = owners.load()
            check(current > 0) { "The OHOS directory grant is closed" }
            if (owners.compareAndSet(current, current + 1)) return
        }
    }

    fun release() {
        if (owners.fetchAndAdd(-1) == 1) descriptor.close()
    }
}

internal fun directorySegments(relativePath: String): List<String> = relativePath.split('/').also { names ->
    require(names.all { it.isNotEmpty() && it != "." && it != ".." && '\u0000' !in it }) {
        "Expected a relative OHOS directory child path"
    }
}

private fun encodeSegment(name: String): String = buildString {
    for (character in name.encodeToByteArray()) {
        val value = character.toInt() and 255
        if (value in 'A'.code..'Z'.code || value in 'a'.code..'z'.code || value in '0'.code..'9'.code || value.toChar() in "-._~") {
            append(value.toChar())
        } else {
            append('%').append(HEX[value ushr 4]).append(HEX[value and 15])
        }
    }
}

private fun openDirectory(fd: Int, name: String): Int =
    openat(fd, name, O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC).also {
    if (it < 0) throw directoryError("open directory")
    }

private fun fstatEntry(fd: Int): DirectoryAttributes = memScoped {
    val info = alloc<stat>()
    if (fstat(fd, info.ptr) != 0) throw directoryError("inspect descriptor")
    info.toAttributes()
}

private fun stat.toAttributes(): DirectoryAttributes = DirectoryAttributes(
    st_mode.toInt() and S_IFMT,
    st_size,
    Instant.fromEpochSeconds(st_mtim.tv_sec, st_mtim.tv_nsec),
)

private data class DirectoryAttributes(val mode: Int, val size: Long, val modified: Instant)

private class DirectoryIOException(operation: String, val errorCode: Int) :
    IOException("OHOS $operation failed: ${strerror(errorCode)?.toKString()}") {
    val isMissing: Boolean get() = errorCode == ENOENT || errorCode == ENOTDIR
}

private fun directoryError(operation: String): DirectoryIOException = DirectoryIOException(operation, errno)

private const val HEX = "0123456789ABCDEF"
private const val FILE_MODE = 0x180
private const val DIRECTORY_MODE = 0x1C0u
