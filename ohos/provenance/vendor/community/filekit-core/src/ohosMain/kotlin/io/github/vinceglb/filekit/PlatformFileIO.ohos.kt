@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.vinceglb.filekit

import kotlinx.cinterop.*
import platform.posix.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import io.github.vinceglb.filekit.utils.div

public actual val PlatformFile.name: String
    get() = descriptor?.name ?: toKotlinxIoPath().name

public actual val PlatformFile.path: String
    get() = descriptor?.uri ?: toKotlinxIoPath().toString()

public actual fun PlatformFile(path: String): PlatformFile =
    PlatformFile(Path(path))

public actual fun PlatformFile.isRegularFile(): Boolean = withScopedAccess {
    if (descriptor != null) true else SystemFileSystem.metadataOrNull(toKotlinxIoPath())?.isRegularFile ?: false
}

public actual fun PlatformFile.isDirectory(): Boolean = withScopedAccess {
    if (descriptor != null) false else SystemFileSystem.metadataOrNull(toKotlinxIoPath())?.isDirectory ?: false
}

public actual fun PlatformFile.isAbsolute(): Boolean =
    descriptor != null || toKotlinxIoPath().isAbsolute

public actual fun PlatformFile.exists(): Boolean =
    descriptor?.isOpen ?: SystemFileSystem.exists(toKotlinxIoPath())

public actual fun PlatformFile.size(): Long = withScopedAccess {
    descriptor?.let { handle ->
        memScoped {
            val info = alloc<stat>()
            if (fstat(handle.fd, info.ptr) != 0) -1L else info.st_size
        }
    } ?: (SystemFileSystem.metadataOrNull(toKotlinxIoPath())?.size ?: -1)
}

public actual fun PlatformFile.parent(): PlatformFile? =
    if (descriptor != null) null else toKotlinxIoPath().parent?.let(::PlatformFile)

public actual fun PlatformFile.absoluteFile(): PlatformFile = withScopedAccess {
    if (descriptor != null) this else PlatformFile(SystemFileSystem.resolve(toKotlinxIoPath()))
}

public actual fun PlatformFile.source(): RawSource = withScopedAccess {
    descriptor?.source() ?: SystemFileSystem.source(toKotlinxIoPath())
}

public actual fun PlatformFile.sink(append: Boolean): RawSink = withScopedAccess {
    descriptor?.sink(append) ?: SystemFileSystem.sink(toKotlinxIoPath(), append)
}

public actual suspend fun PlatformFile.delete(mustExist: Boolean): Unit =
    withContext(Dispatchers.IO) {
        SystemFileSystem.delete(path = toKotlinxIoPath(), mustExist = mustExist)
    }

public actual suspend fun PlatformFile.atomicMove(destination: PlatformFile): Unit =
    withContext(Dispatchers.IO) {
        withScopedAccess {
            SystemFileSystem.atomicMove(
                source = toKotlinxIoPath(),
                destination = destination.toKotlinxIoPath(),
            )
        }
    }

internal actual suspend fun PlatformFile.prepareDestinationForWrite(
    source: PlatformFile
): PlatformFile = withScopedAccess {
    if (isDirectory()) {
        PlatformFile(toKotlinxIoPath() / source.name)
    } else {
        this
    }
}
