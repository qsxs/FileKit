# FileKit 统一 Maven 制品 Consumer

该目录提供两套独立的 KMP Consumer：standard 使用标准 Kotlin 工具链解析 Android 与 Apple 变体，ohos 使用 CPF Kotlin 插件解析两个 OHOS 变体。两边都从同一个 Maven staging 根读取 filekit-core 与 filekit-dialogs 的统一版本。

verify.ps1 通过参数接收两个 Gradle Wrapper、Maven 仓库地址、工具链版本和 FileKit 版本；它不会发布制品，也不会启动设备。

~~~powershell
& .\verify.ps1 -FileKitRepository 'D:\staging\filekit-unified' -Version '0.16.0-cpf-unified-<SHA12>' -SourceCommit '<40-character-commit-sha>' -StandardGradleWrapper 'D:\tools\gradle-9.7.1\bin\gradle.bat' -CpfGradleWrapper 'D:\tools\gradle-8.11.1\bin\gradle.bat' -JavaHome 'D:\tools\jdk-21' -AndroidSdkRoot 'D:\Android\Sdk' -CpfPluginRepository 'https://maven.eazytec-cloud.com/nexus/repository/maven-develop/' -CpfDependencyRepository 'https://maven.eazytec-cloud.com/nexus/repository/maven-public/' -KotlinNativeHome 'D:\toolchains\kotlin-native-2.4.21-0.1.0-13' -KonanDataDir 'D:\build-cache\konan' -MergeReport 'D:\staging\merge-report.json'
~~~

仓库地址也可以指向已发布的 Pages Maven 根目录。标准构建编译 Android、iOS arm64 与 iOS simulator arm64；CPF 构建编译 OHOS arm64 与 x64。共同源码检查 PlatformFile.name，平台源码检查 FileKit.shareFile，确保共同 API 与平台叶都能解析。

正式合并必须同时传 merge_publications.py 的 `--release-ready --source-root <clean-source-checkout>`；这会用 Git commit blob 逐项核对两个 producer 的源码哈希。dry staging 可以省略 source-root，但 merge-report.json 会标记 `sourceBlobsVerified=false`，不能用于公开发布。

父任务主控负责在实际 Gradle/CPF 工具链上执行这两套 Consumer，并保存依赖选择与编译结果喵。
