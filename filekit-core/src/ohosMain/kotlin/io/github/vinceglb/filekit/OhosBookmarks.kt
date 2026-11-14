@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.vinceglb.filekit

import io.github.vinceglb.filekit.exceptions.FileKitException
import kotlinx.cinterop.*
import kotlinx.io.IOException
import platform.CoreFileKit.FileShare.*
import platform.CoreFileKit.FileUri.OH_FileUri_GetFileName
import platform.CoreFileKit.FileUri.OH_FileUri_GetPathFromUri
import platform.posix.*

private const val BOOKMARK_HEADER = "\u0000FileKit-OHOS-1\n"
private const val LOCAL_DOCUMENT_PREFIX = "file://docs/storage/Users/currentUser/"
private val readWriteMode = READ_MODE or WRITE_MODE

internal fun isLocalDocumentUri(uri: String): Boolean =
    uri.startsWith(LOCAL_DOCUMENT_PREFIX) && uri.none { it.isISOControl() || it == '?' || it == '#' }

internal fun localDocumentPath(uri: String): String {
    require(isLocalDocumentUri(uri)) { "Expected a local OHOS document URI" }
    val path = fileUriString(uri, "resolve document path", ::OH_FileUri_GetPathFromUri)
    if (!path.startsWith('/') || path.split('/').any { it == "." || it == ".." }) {
        throw IOException("The OHOS document URI has no usable sandbox mapping")
    }
    return path
}

private fun fileUriString(
    uri: String,
    action: String,
    get: (String, UInt, CPointer<CPointerVar<ByteVar>>) -> UInt,
): String = memScoped {
    val result = alloc<CPointerVar<ByteVar>> { value = null }
    val status = get(uri, uri.encodeToByteArray().size.toUInt(), result.ptr)
    try {
        val value = result.value
        if (status != 0u || value == null) throw IOException("Cannot $action on OHOS: $status")
        value.toKString()
    } finally {
        free(result.value)
    }
}

internal fun persistOhosBookmark(
    uri: String,
    directory: Boolean,
    writeAllowed: Boolean,
    relativePath: String = "",
): BookmarkData {
    require(isLocalDocumentUri(uri)) { "Only local document picker URIs can be persisted on OHOS" }
    var mode = if (writeAllowed) readWriteMode else READ_MODE
    try {
        policyOperation(uri, mode, "persist", ::OH_FileShare_PersistPermission)
    } catch (failure: PolicyFailure) {
        if (!writeAllowed || !failure.cannotPersist) throw failure
        mode = READ_MODE
        policyOperation(uri, mode, "persist read access", ::OH_FileShare_PersistPermission)
    }
    if (!hasPermission(uri, mode)) {
        if (mode != readWriteMode || !hasPermission(uri, READ_MODE)) {
            throw FileKitException("OHOS did not retain the bookmark permission. Select the file again.")
        }
        mode = READ_MODE
    }
    // 旧书签没有头部，仅含普通沙箱路径。NUL 必定不出现在合法路径里。
    val encoded = listOf(mode.toString(), if (directory) "directory" else "file", uri, relativePath).joinToString("\n")
    return BookmarkData((BOOKMARK_HEADER + encoded).encodeToByteArray())
}

internal fun revokeOhosBookmark(uri: String) {
    if (!isLocalDocumentUri(uri) || !hasPermission(uri, READ_MODE)) return
    val mode = if (hasPermission(uri, readWriteMode)) readWriteMode else READ_MODE
    policyOperation(uri, mode, "revoke", ::OH_FileShare_RevokePermission)
}

internal fun restoreOhosBookmark(data: BookmarkData): BookmarkResolution {
    val encoded = data.bytes.decodeToString(throwOnInvalidSequence = true)
    if (!encoded.startsWith(BOOKMARK_HEADER)) {
        require('\u0000' !in encoded) { "Unsupported OHOS bookmark format" }
        val file = PlatformFile(encoded)
        if (!file.exists()) throw FileKitException("Bookmark target is no longer accessible")
        return BookmarkResolution(file, isStale = false, shouldRefresh = false)
    }
    val parts = encoded.removePrefix(BOOKMARK_HEADER).split('\n', limit = 4)
    require(parts.size == 4) { "Invalid OHOS bookmark" }
    val (rawMode, type, uri, relativePath) = parts
    val directory = type == "directory"
    val mode = rawMode.toUIntOrNull()
    require((mode == READ_MODE || mode == readWriteMode) && (directory || type == "file") && isLocalDocumentUri(uri)) {
        "Invalid OHOS bookmark policy"
    }
    if (relativePath.isNotEmpty()) {
        require(directory) { "Expected an OHOS directory bookmark for a child" }
        directorySegments(relativePath)
    }
    if (!hasPermission(uri, mode)) throw FileKitException("Bookmark permission is no longer available. Select the file again.")
    policyOperation(uri, mode, "activate", ::OH_FileShare_ActivatePermission)

    // 通过当前进程的系统映射重新打开获授权的本地文档。书签只持久化 URI。
    val path = localDocumentPath(uri)
    val name = fileUriString(uri, "resolve document name", ::OH_FileUri_GetFileName)
    val flags = if (directory) O_RDONLY or O_DIRECTORY else if (mode == READ_MODE) O_RDONLY else O_RDWR
    val fd = open(path, flags or O_CLOEXEC or O_NOFOLLOW or O_NONBLOCK)
    if (fd < 0) throw IOException("Cannot open active OHOS bookmark: ${strerror(errno)?.toKString()}")
    val root = try {
        if (directory) {
            PlatformFile.fromDirectoryDescriptor(fd, uri, name, mode == readWriteMode)
        } else {
            PlatformFile.fromFileDescriptor(fd, uri, name)
        }
    } finally {
        platform.posix.close(fd)
    }
    val file = if (relativePath.isEmpty()) {
        root
    } else {
        try { root / relativePath } finally { root.close() }
    }
    try {
        if (!file.exists()) throw FileKitException("Bookmark target is no longer accessible")
        return BookmarkResolution(file, isStale = false, shouldRefresh = false)
    } catch (error: Throwable) {
        file.close()
        throw error
    }
}

private fun hasPermission(uri: String, mode: UInt): Boolean = memScoped {
    val result = alloc<CPointerVar<BooleanVar>> { value = null }
    val count = alloc<UIntVar> { value = 0u }
    val status = OH_FileShare_CheckPersistentPermission(policy(uri, mode), 1u, result.ptr, count.ptr)
    try {
        if (status != 0u) throw PolicyFailure("check", status, null)
        val permissions = result.value
        if (count.value != 1u || permissions == null) throw IOException("Invalid OHOS persistent permission response")
        permissions[0].value
    } finally {
        free(result.value)
    }
}

private fun policyOperation(
    uri: String,
    mode: UInt,
    action: String,
    execute: (CPointer<FileShare_PolicyInfo>, UInt, CPointer<CPointerVar<FileShare_PolicyErrorResult>>, CPointer<UIntVar>) -> UInt,
): Unit = memScoped {
    val errors = alloc<CPointerVar<FileShare_PolicyErrorResult>> { value = null }
    val count = alloc<UIntVar> { value = 0u }
    val status = execute(policy(uri, mode), 1u, errors.ptr, count.ptr)
    try {
        val first = errors.value?.takeIf { count.value > 0u }?.pointed
        if (status != 0u || first != null) throw PolicyFailure(action, status, first?.code)
    } finally {
        errors.value?.let { OH_FileShare_ReleasePolicyErrorResult(it, count.value) }
    }
}

private fun MemScope.policy(document: String, mode: UInt): CPointer<FileShare_PolicyInfo> =
    alloc<FileShare_PolicyInfo> {
        uri = document.cstr.getPointer(this@policy)
        length = document.encodeToByteArray().size.toUInt()
        operationMode = mode
    }.ptr

private class PolicyFailure(action: String, status: UInt, code: UInt?) :
    IOException("Cannot $action OHOS bookmark permission: $status${code?.let { " (policy $it)" }.orEmpty()}") {
    val cannotPersist: Boolean = code == PERSISTENCE_FORBIDDEN || code == PERMISSION_NOT_PERSISTED
}
