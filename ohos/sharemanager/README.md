# FileKit 0.16.0 鸿蒙 ShareManager 制品

此入口从 fork 的 `filekit-dialogs` 源码生成根 metadata 和 OHOS x64、ARM64 KLIB。两个 Native 变体均使用 CPF Compose `1.12.0-0.1.0-23`。制品使用 `io.github.qsxs.cpf.filekit:filekit-dialogs:0.16.0-cpf-sharemanager-<源码提交前 12 位>`，`filekit-core` 继续解析 `0.16.0-cpf11-preview-22683094fd5d-40864370116-1`。

宿主在 UIAbility 主线程调用 `FileKit.init(env, context)` 并持有返回的 `FileKitDialogSession`，在 Compose 控制器根组合内调用 `session.BindSystemShare()`，销毁宿主时调用 `session.dispose()`。分享通过 `FileKit.shareFile()` 发起，CMP 宿主通过已配套的 HAR 提供 `SystemShareProxy`。

使用 JDK 17、Gradle 8.11.1 与 CPF Kotlin/Native `2.4.21-0.1.0-13`。从 fork 根目录执行下面的 PowerShell 命令。请先将 `$stagingRepository` 和 `$nativeHome` 设为对应的绝对路径，发布目标应为新的本地暂存目录。

```powershell
$sourceCommit = git rev-parse HEAD
& .\ohos\gradlew.bat -p ohos/sharemanager publishAllPublicationsToStagingRepository --max-workers=1 --console=plain "-PsourceCommit=$sourceCommit" "-PstagingRepository=$stagingRepository" "-Pkotlin.native.home=$nativeHome"
```

Gradle Module Metadata 负责选择目标变体，根制品及两个 Native 变体均附带上游和社区 MIT 许可证与源码 SHA-256。应核对每个变体的依赖、KLIB 和源码包后，再上传完整新版本。
