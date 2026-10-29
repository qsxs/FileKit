@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.vinceglb.filekit.dialogs

import kotlinx.coroutines.initMainHandler
import platform.ArkTS.ArkTS_Napi_NativeModule.napi_env

internal actual fun initializeFileKitMainHandler(env: napi_env) {
    initMainHandler(env)
}
