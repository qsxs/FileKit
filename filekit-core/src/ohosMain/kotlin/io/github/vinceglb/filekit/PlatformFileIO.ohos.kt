@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.vinceglb.filekit

import io.github.vinceglb.filekit.utils.div
import kotlinx.cinterop.*
import platform.posix.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

public actual val PlatformFile.name: String
    get() = directoryEntry?.name ?: descriptor?.name ?: toKotlinxIoPath().name

public actual val PlatformFile.path: String
    get() = directoryEntry?.path ?: descriptor?.uri ?: toKotlinxIoPath().toString()

public actual fun PlatformFile(path: String): PlatformFile =
    PlatformFile(Path(path))

public actual fun PlatformFile(base: PlatformFile, child: String): PlatformFile =
    base.directoryEntry?.child(child)?.let(::PlatformFile) ?: base.withPath(base.toKotlinxIoPath() / child)

public actual fun PlatformFile.isRegularFile(): Boolean = withScopedAccess {
    directoryEntry?.isRegularFile() ?: descriptor?.borrowed { fd -> descriptorMode(fd) == S_IFREG }
        ?: (SystemFileSystem.metadataOrNull(toKotlinxIoPath())?.isRegularFile ?: false)
}

public actual fun PlatformFile.isDirectory(): Boolean = withScopedAccess {
    directoryEntry?.isDirectory() ?: descriptor?.borrowed { fd -> descriptorMode(fd) == S_IFDIR }
        ?: (SystemFileSystem.metadataOrNull(toKotlinxIoPath())?.isDirectory ?: false)
}

public actual fun PlatformFile.isAbsolute(): Boolean =
    directoryEntry != null || descriptor != null || toKotlinxIoPath().isAbsolute

public actual fun PlatformFile.exists(): Boolean = withScopedAccess {
    directoryEntry?.exists() ?: descriptor?.isOpen ?: SystemFileSystem.exists(toKotlinxIoPath())
}

public actual fun PlatformFile.size(): Long = withScopedAccess {
    directoryEntry?.size() ?: descriptor?.borrowed { fd -> memScoped {
        val info = alloc<stat>()
        if (fstat(fd, info.ptr) != 0) throw kotlinx.io.IOException("Cannot stat descriptor")
        info.st_size
    } } ?: (SystemFileSystem.metadataOrNull(toKotlinxIoPath())?.size ?: -1)
}

public actual fun PlatformFile.parent(): PlatformFile? {
    directoryEntry?.let { return it.parent()?.let(::PlatformFile) }
    return if (descriptor != null) null else toKotlinxIoPath().parent?.let(::withPath)
}

public actual fun PlatformFile.absoluteFile(): PlatformFile = withScopedAccess {
    if (directoryEntry != null || descriptor != null) this else withPath(SystemFileSystem.resolve(toKotlinxIoPath()))
}

public actual fun PlatformFile.source(): RawSource {
    directoryEntry?.let { return it.source() }
    descriptor?.let { return it.source() }
    val accessGranted = startAccessingSecurityScopedResource()
    try {
        return ScopedRawSource(
            delegate = SystemFileSystem.source(toKotlinxIoPath()),
            onClose = { if (accessGranted) stopAccessingSecurityScopedResource() },
        )
    } catch (error: Throwable) {
        if (accessGranted) stopAccessingSecurityScopedResource()
        throw error
    }
}

public actual fun PlatformFile.sink(append: Boolean): RawSink {
    directoryEntry?.let { return it.sink(append) }
    descriptor?.let { return it.sink(append) }
    val accessGranted = startAccessingSecurityScopedResource()
    try {
        return ScopedRawSink(
            delegate = SystemFileSystem.sink(toKotlinxIoPath(), append),
            onClose = { if (accessGranted) stopAccessingSecurityScopedResource() },
        )
    } catch (error: Throwable) {
        if (accessGranted) stopAccessingSecurityScopedResource()
        throw error
    }
}

public actual fun PlatformFile.createDirectories(mustCreate: Boolean): Unit =
    withScopedAccess {
        directoryEntry?.createDirectories(mustCreate) ?: SystemFileSystem.createDirectories(toKotlinxIoPath(), mustCreate)
    }

public actual suspend fun PlatformFile.delete(mustExist: Boolean, recursively: Boolean): Unit =
    withScopedAccess {
        withContext(Dispatchers.IO) {
            directoryEntry?.let {
                it.delete(mustExist, recursively)
                return@withContext
            }
            if (!deleteIfSymbolicLink()) {
                if (recursively) deleteChildren()
                SystemFileSystem.delete(path = toKotlinxIoPath(), mustExist = mustExist)
            }
        }
    }

public actual suspend fun PlatformFile.atomicMove(destination: PlatformFile): Unit =
    withContext(Dispatchers.IO) {
        withScopedAccess {
            destination.withScopedAccess { scopedDestination ->
                val resolvedDestination = scopedDestination.resolveAtomicMoveDestination(
                    source = this@atomicMove,
                )
                try {
                    val sourceEntry = directoryEntry
                    val destinationEntry = resolvedDestination.directoryEntry
                    if (sourceEntry != null && destinationEntry != null) {
                        sourceEntry.moveTo(destinationEntry)
                    } else {
                        SystemFileSystem.atomicMove(
                            source = toKotlinxIoPath(),
                            destination = resolvedDestination.toKotlinxIoPath(),
                        )
                    }
                } finally {
                    if (resolvedDestination !== scopedDestination) resolvedDestination.close()
                }
            }
        }
    }

private fun PlatformFile.resolveAtomicMoveDestination(source: PlatformFile): PlatformFile {
    if (isDirectory()) {
        return this / source.name
    }
    return this
}

internal actual suspend fun PlatformFile.prepareDestinationForWrite(
    source: PlatformFile,
): PlatformFile = withScopedAccess {
    if (isDirectory()) {
        this / source.name
    } else {
        this
    }
}

private fun descriptorMode(fd: Int): Int = memScoped {
    val info = alloc<stat>()
    if (fstat(fd, info.ptr) != 0) throw kotlinx.io.IOException("Cannot stat descriptor")
    info.st_mode.toInt() and S_IFMT
}

private class ScopedRawSource(
    private val delegate: RawSource,
    private val onClose: () -> Unit,
) : RawSource {
    private var closed = false

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = delegate.readAtMostTo(sink, byteCount)

    override fun close() {
        if (closed) return
        closed = true
        try {
            delegate.close()
        } finally {
            onClose()
        }
    }
}

private class ScopedRawSink(
    private val delegate: RawSink,
    private val onClose: () -> Unit,
) : RawSink {
    private var closed = false

    override fun write(source: Buffer, byteCount: Long) {
        delegate.write(source, byteCount)
    }

    override fun flush() {
        delegate.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            delegate.close()
        } finally {
            onClose()
        }
    }
}
