@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.vinceglb.filekit

import kotlinx.cinterop.*
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import platform.posix.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.native.ref.createCleaner

internal class OhosDescriptor(fd: Int, val uri: String, val name: String) {
    private val state = DescriptorState(fd)
    @Suppress("unused")
    private val cleaner = createCleaner(state) { it.close() }
    val fd: Int get() = state.fd
    val isOpen: Boolean get() = state.isOpen
    fun close() = state.close()
    fun source(): RawSource = DescriptorSource(duplicate())
    fun sink(append: Boolean): RawSink {
        val output = duplicate()
        try {
            if (!append && ftruncate(output, 0) != 0) throw ioError("truncate")
            val offset = if (append) lseek(output, 0, SEEK_END) else 0L
            if (offset < 0) throw ioError("seek")
            return DescriptorSink(output, offset)
        } catch (error: Throwable) {
            platform.posix.close(output)
            throw error
        }
    }
    private fun duplicate(): Int = dup(fd).also { if (it < 0) throw ioError("dup") }
}

private class DescriptorState(fd: Int) {
    private val value = AtomicInt(fd)
    val isOpen: Boolean get() = value.load() >= 0
    val fd: Int get() = value.load().also { check(it >= 0) { "Picker file is closed" } }
    fun close() {
        val previous = value.exchange(-1)
        if (previous >= 0) platform.posix.close(previous)
    }
}

private class DescriptorSource(fd: Int) : RawSource {
    private val state = DescriptorState(fd)
    private var offset = 0L
    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0)
        val fd = state.fd
        if (byteCount == 0L) return 0L
        val bytes = ByteArray(minOf(byteCount, 65536L).toInt())
        val count = bytes.usePinned {
            var count: Long
            do { count = pread(fd, it.addressOf(0), bytes.size.convert(), offset) }
            while (count < 0 && errno == EINTR)
            count
        }
        if (count < 0) throw ioError("read")
        if (count == 0L) return -1
        sink.write(bytes, 0, count.toInt())
        offset += count
        return count
    }
    override fun close() = state.close()
}

private class DescriptorSink(fd: Int, private var offset: Long) : RawSink {
    private val state = DescriptorState(fd)
    override fun write(source: Buffer, byteCount: Long) {
        require(byteCount in 0..source.size)
        val fd = state.fd
        var remaining = byteCount
        while (remaining > 0) {
            val bytes = source.readByteArray(minOf(remaining, 65536L).toInt())
            bytes.usePinned {
                var written = 0
                while (written < bytes.size) {
                    val count = pwrite(fd, it.addressOf(written), (bytes.size - written).convert(), offset)
                    if (count < 0 && errno == EINTR) continue
                    if (count <= 0) throw ioError("write")
                    written += count.toInt()
                    offset += count
                }
            }
            remaining -= bytes.size
        }
    }
    override fun flush() { if (fsync(state.fd) != 0) throw ioError("flush") }
    override fun close() = state.close()
}

private fun ioError(operation: String): IOException = IOException("OHOS descriptor $operation failed: ${strerror(errno)?.toKString()}")
