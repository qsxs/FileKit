@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package io.github.vinceglb.filekit

import kotlinx.cinterop.*
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import platform.posix.*
import kotlin.native.ref.createCleaner

internal class OhosDescriptor(fd: Int, val uri: String, val name: String) {
    private val state = DescriptorState(fd)
    @Suppress("unused")
    private val cleaner = createCleaner(state) { it.destroy() }
    val isOpen: Boolean get() = state.isOpen
    fun close() = state.close()
    fun closeChecked() = state.close(checked = true)
    fun acquire(): OhosDescriptor = OhosDescriptor(duplicate(), uri, name)
    fun <T> borrowed(block: (Int) -> T): T {
        val borrowed = duplicate()
        return try { block(borrowed) } finally { platform.posix.close(borrowed) }
    }
    fun source(): RawSource = DescriptorSource(duplicate())
    fun newMediaSink(): RawSink = DescriptorSink(duplicate(), checkedClose = true)
    fun isWritable(): Boolean = borrowed { fd ->
        val flags = fcntl(fd, F_GETFL)
        if (flags < 0) throw ioError("read access mode")
        flags and O_ACCMODE != O_RDONLY
    }

    fun sink(append: Boolean): RawSink {
        if (append) return DescriptorSink(openAppend(), checkedClose = true, append = true)
        val output = duplicate()
        try {
            if (ftruncate(output, 0) != 0) throw ioError("truncate")
            return DescriptorSink(output)
        } catch (error: Throwable) {
            platform.posix.close(output)
            throw error
        }
    }

    private fun openAppend(): Int {
        // 独立打开系统授权的本地文件，避免通过 dup 改变其它流的 O_APPEND。
        if (!isWritable() || !isLocalDocumentUri(uri)) {
            throw IOException("OHOS cannot reopen this picker file with append access")
        }
        val path = localDocumentPath(uri)
        return borrowed { original ->
            val output = open(path, O_WRONLY or O_APPEND or O_NOFOLLOW or O_CLOEXEC or O_NONBLOCK)
            if (output < 0) throw ioError("open for append")
            try {
                memScoped {
                    val previous = alloc<stat>()
                    val reopened = alloc<stat>()
                    if (fstat(original, previous.ptr) != 0 || fstat(output, reopened.ptr) != 0) throw ioError("inspect append file")
                    if (reopened.st_mode.toInt() and S_IFMT != S_IFREG ||
                        previous.st_dev != reopened.st_dev || previous.st_ino != reopened.st_ino
                    ) {
                        throw IOException("OHOS append target no longer identifies the selected file")
                    }
                }
                output
            } catch (error: Throwable) {
                platform.posix.close(output)
                throw error
            }
        }
    }

    private fun duplicate(): Int = state.duplicate()
}

private class DescriptorState(fd: Int) {
    private val mutex = nativeHeap.alloc<pthread_mutex_t>()
    private var value = fd
    init {
        val status = pthread_mutex_init(mutex.ptr, null)
        if (status != 0) {
            nativeHeap.free(mutex.ptr)
            platform.posix.close(fd)
            throw IOException("Cannot initialize descriptor mutex: $status")
        }
    }
    private inline fun <T> locked(block: () -> T): T {
        check(pthread_mutex_lock(mutex.ptr) == 0) { "Cannot lock descriptor" }
        return try { block() } finally { pthread_mutex_unlock(mutex.ptr) }
    }
    val isOpen: Boolean get() = locked { value >= 0 }
    fun <T> using(block: (Int) -> T): T = locked { block(checkedFd()) }
    private fun checkedFd(): Int = value.also { check(it >= 0) { "Picker file is closed" } }
    // 开放检查与 dup 必须在同一短同步区，防止 close 后 FD 编号被其它文件复用。
    fun duplicate(): Int = locked {
        dup(checkedFd()).also { if (it < 0) throw ioError("dup") }
    }
    fun close(checked: Boolean = false): Unit = locked {
        if (value >= 0) {
            val previous = value
            value = -1
            if (platform.posix.close(previous) != 0 && checked) throw ioError("close")
        }
    }
    fun destroy() {
        close()
        pthread_mutex_destroy(mutex.ptr)
        nativeHeap.free(mutex.ptr)
    }
}

internal class DescriptorSource(fd: Int) : RawSource {
    private val state = DescriptorState(fd)
    @Suppress("unused")
    private val cleaner = createCleaner(state) { it.destroy() }
    private var offset = 0L
    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = state.using { fd ->
        require(byteCount >= 0)
        if (byteCount == 0L) return@using 0L
        val bytes = ByteArray(minOf(byteCount, 65536L).toInt())
        val count = bytes.usePinned {
            var count: Long
            do { count = pread(fd, it.addressOf(0), bytes.size.convert(), offset) }
            while (count < 0 && errno == EINTR)
            count
        }
        if (count < 0) throw ioError("read")
        if (count == 0L) return@using -1L
        sink.write(bytes, 0, count.toInt())
        offset += count
        count
    }
    override fun close() = state.close()
}

internal class DescriptorSink(
    fd: Int,
    private val checkedClose: Boolean = false,
    private val append: Boolean = false,
) : RawSink {
    private val state = DescriptorState(fd)
    private var offset = 0L
    @Suppress("unused")
    private val cleaner = createCleaner(state) { it.destroy() }
    override fun write(source: Buffer, byteCount: Long): Unit = state.using { fd ->
        require(byteCount in 0..source.size)
        var remaining = byteCount
        while (remaining > 0) {
            val bytes = source.readByteArray(minOf(remaining, 65536L).toInt())
            bytes.usePinned {
                var written = 0
                while (written < bytes.size) {
                    val address = it.addressOf(written)
                    val length = (bytes.size - written).convert<size_t>()
                    val count = if (append) write(fd, address, length) else pwrite(fd, address, length, offset)
                    if (count < 0 && errno == EINTR) continue
                    if (count <= 0) throw ioError("write")
                    written += count.toInt()
                    if (!append) offset += count
                }
            }
            remaining -= bytes.size
        }
    }
    override fun flush(): Unit = state.using { fd -> if (fsync(fd) != 0) throw ioError("flush") }
    override fun close() = state.close(checkedClose)
}

private fun ioError(operation: String): IOException = IOException("OHOS descriptor $operation failed: ${strerror(errno)?.toKString()}")
