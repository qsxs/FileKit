@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.vinceglb.filekit.dialogs

import io.github.vinceglb.filekit.exceptions.FileKitException
import kotlinx.cinterop.*
import platform.ArkTS.ArkTS_Napi_NativeModule.*

/** Values returned here are borrowed handles: use them only inside the current handle scope. */
internal class OhosNapi(val env: napi_env) {
    fun check(status: napi_status) {
        if (status == napi_ok) return
        val detail = if (status == napi_pending_exception) memScoped {
            val error = alloc<napi_valueVar>()
            napi_get_and_clear_last_exception(env, error.ptr)
            error.value?.let { describeError(it) }
        } else null
        throw FileKitException("OHOS NAPI failed ($status)${detail?.let { ": $it" }.orEmpty()}")
    }

    fun describeError(value: napi_value): String = try {
        val code = get(value, "code")
        val message = get(value, "message")
        "${coerceString(code)}: ${coerceString(message)}"
    } catch (_: Throwable) { "System operation failed" }

    private fun result(block: (CPointer<napi_valueVar>) -> napi_status): napi_value = memScoped {
        val output = alloc<napi_valueVar>()
        check(block(output.ptr))
        requireNotNull(output.value)
    }

    fun <T> scoped(block: () -> T): T = memScoped {
        val handles = alloc<napi_handle_scopeVar>()
        check(napi_open_handle_scope(env, handles.ptr))
        try { block() } finally { napi_close_handle_scope(env, handles.value) }
    }

    fun module(name: String): napi_value = result {
        // System modules do not require the application's bundle/module name.
        napi_load_module_with_info(env, name, null, it)
    }
    fun obj(): napi_value = result { napi_create_object(env, it) }
    fun get(receiver: napi_value, name: String): napi_value = result { napi_get_named_property(env, receiver, name, it) }
    fun set(receiver: napi_value, name: String, value: napi_value) = check(napi_set_named_property(env, receiver, name, value))
    fun text(value: String): napi_value = result { napi_create_string_utf8(env, value, value.encodeToByteArray().size.convert(), it) }
    fun number(value: Int): napi_value = result { napi_create_int32(env, value, it) }
    fun int(value: napi_value): Int = memScoped {
        val output = alloc<IntVar>()
        check(napi_get_value_int32(env, value, output.ptr))
        output.value
    }
    fun bool(value: napi_value): Boolean = memScoped {
        val output = alloc<BooleanVar>()
        check(napi_get_value_bool(env, value, output.ptr))
        output.value
    }
    fun string(value: napi_value): String = memScoped {
        val length = alloc<size_tVar>()
        check(napi_get_value_string_utf8(env, value, null, 0u, length.ptr))
        require(length.value < 16u * 1024u * 1024u) { "System string is too large" }
        val bytes = allocArray<ByteVar>(length.value.toInt() + 1)
        check(napi_get_value_string_utf8(env, value, bytes, length.value + 1u, length.ptr))
        bytes.readBytes(length.value.toInt()).decodeToString()
    }
    private fun coerceString(value: napi_value): String = string(result { napi_coerce_to_string(env, value, it) })

    fun array(values: List<napi_value>): napi_value = result { napi_create_array_with_length(env, values.size.convert(), it) }.also { array ->
        values.forEachIndexed { index, value -> check(napi_set_element(env, array, index.toUInt(), value)) }
    }
    fun elements(array: napi_value): List<napi_value> = memScoped {
        val length = alloc<UIntVar>()
        check(napi_get_array_length(env, array, length.ptr))
        require(length.value <= 10_000u) { "System result contains too many items" }
        List(length.value.toInt()) { index -> result { napi_get_element(env, array, index.toUInt(), it) } }
    }
    fun construct(module: napi_value, name: String, vararg arguments: napi_value): napi_value = memScoped {
        val args = allocArray<napi_valueVar>(arguments.size)
        arguments.forEachIndexed { index, value -> args[index] = value }
        result { napi_new_instance(env, get(module, name), arguments.size.convert(), args, it) }
    }
    fun call(receiver: napi_value, name: String, vararg arguments: napi_value): napi_value = memScoped {
        val args = allocArray<napi_valueVar>(arguments.size)
        arguments.forEachIndexed { index, value -> args[index] = value }
        result { napi_call_function(env, receiver, get(receiver, name), arguments.size.convert(), args, it) }
    }
    fun reference(value: napi_value): napi_ref = memScoped {
        val output = alloc<napi_refVar>()
        check(napi_create_reference(env, value, 1u, output.ptr))
        requireNotNull(output.value)
    }
    fun dereference(reference: napi_ref): napi_value = result { napi_get_reference_value(env, reference, it) }

    /** Each JS function owns its own StableRef; both callbacks may share one Kotlin completion. */
    fun callback(block: (napi_value) -> Unit): napi_value {
        val state = StableRef.create(block)
        var ownedByJs = false
        try {
            val function = result {
                napi_create_function(env, "filekitResult", 13u, staticCFunction(::onNapiResult), state.asCPointer(), it)
            }
            check(napi_add_finalizer(env, function, state.asCPointer(), staticCFunction(::finalizeNapiResult), null, null))
            ownedByJs = true
            return function
        } finally {
            if (!ownedByJs) state.dispose()
        }
    }
}

private fun onNapiResult(env: napi_env?, info: napi_callback_info?): napi_value? = memScoped {
    val count = alloc<size_tVar> { value = 1u }
    val argument = alloc<napi_valueVar>()
    val data = alloc<COpaquePointerVar>()
    if (napi_get_cb_info(env, info, count.ptr, argument.ptr, null, data.ptr) != napi_ok) return@memScoped null
    // Promise<void> still passes undefined; make zero-argument callbacks equivalent.
    if (count.value == 0uL) napi_get_undefined(env, argument.ptr)
    val value = argument.value ?: return@memScoped null
    try {
        data.value?.asStableRef<(napi_value) -> Unit>()?.get()?.invoke(value)
    } catch (_: Throwable) {
        // No Kotlin exception may cross the C callback boundary. Completion handles failures.
    }
    null
}

private fun finalizeNapiResult(env: napi_env?, data: COpaquePointer?, hint: COpaquePointer?) {
    data?.asStableRef<(napi_value) -> Unit>()?.dispose()
}
