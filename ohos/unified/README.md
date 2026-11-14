# FileKit 0.16.0 CPF 三端发布

统一坐标为 `io.github.qsxs.cpf.filekit:filekit-core` 和 `io.github.qsxs.cpf.filekit:filekit-dialogs`，版本为 `0.16.0-cpf-unified-<源码 SHA 前 12 位>`。Gradle 通过 Module Metadata 选择 Android、`iosArm64`、`iosSimulatorArm64`、`ohosArm64` 或 `ohosX64`。

## 鸿蒙宿主

在 ArkTS UIAbility 的主线程调用 `FileKit.init(env, context)`，销毁时调用返回会话的 `dispose()`。使用 ShareManager 的宿主仍在 Compose 根组合调用 `session.BindSystemShare()`。相册确认和相机也使用同一会话。宿主建议为 UIAbility 设置 `label` 和 `icon`，以便系统相册确认框显示应用信息。

目录选择依赖系统的 FolderSelection 能力。目录句柄和由它生成的子句柄都可独立 `close()`，使用 `source()`、`sink()` 及 `PlatformFile` 的目录接口访问。目录句柄的 `path` 用于标识资源，`toKotlinxIoPath()` 仅用于普通路径。外部句柄的持久存储使用 `bookmarkData()`。宿主需声明 `ohos.permission.FILE_ACCESS_PERSIST`，并满足目标设备的能力和权限发布要求。已撤销或未实际保存的授权会失败，应用可引导用户重新选择。文件系统无法可靠提供创建时间时 `createdAt()` 返回 `null`。本地文档 Provider 拒绝追加打开时，`sink(append = true)` 报错。

相册确认提供单次写权限。系统创建相册条目后，如果后续写入或关闭失败，FileKit 会返回失败。应用可在需要时引导用户通过系统相册清理该条目。

## 制品构建

从同一份干净 Git 提交生成两个暂存仓库。标准入口使用根 Gradle 9.7.1 和 Kotlin 2.4.20，启用 `-I ohos/unified/standard.init.gradle`。CPF 入口用 `ohos/gradlew`、`-p ohos/unified`、Gradle 8.11.1 和 Kotlin/Native 2.4.21-0.1.0-13。两者都传 `-PfilekitSourceCommit=<完整 SHA>` 和 `-PfilekitStaging=<全新目录>`。标准入口同时传 `-PGROUP=io.github.qsxs.cpf.filekit`、`-PVERSION_NAME=<本次版本>` 和 `-PRELEASE_SIGNING_ENABLED=false`。

每个模块分别执行 `publish<变体>PublicationToUnifiedRepository`。标准变体为 `KotlinMultiplatform`、`Android`、`IosArm64`、`IosSimulatorArm64`，CPF 变体为 `KotlinMultiplatform`、`OhosArm64`、`OhosX64`。以 `merge_publications.py --release-ready --source-root <源码副本>` 合并并核对实际 Git blob，再用 [独立消费工程](consumer/README.md) 验证同版本制品。
