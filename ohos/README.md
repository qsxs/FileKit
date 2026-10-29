# FileKit 0.16.0 OHOS 独立 producer

本目录为 vinceglb/FileKit 0.16.0 官方提交
e8876bcefec8e502ac61a0af45f00de02c253fbf 的独立 OHOS 构建入口。共享源码、社区源码、八份
OHOS actual、两个 ABI handler 和 ArkTS 工厂的来源与补丁记录见
[provenance/README.md](provenance/README.md)。这些文件不由本构建修改或重新打补丁；上游和社区
MIT 许可原件均进入发布物。

本配方使用 Gradle 8.11.1、JDK 17 和 CPF Kotlin/Native/KGP
2.4.20-0.1.0-08，metadata 首先执行标准 KGP 任务，不使用豁免或替代编译入口。依赖沿用原配方
的 29 条锁定，其中 23 条为外部坐标，6 条为 FileKit 内部坐标；内部坐标随本次 group 和版本变化。
核心模块的 kotlinx-io 为 API，coroutines/serialization/annotation 为 implementation；dialogs
对 core 为 project API，对 coroutines 和 serialization-json 为 implementation。annotation 使用原
OHOS 配方的 org.jetbrains.compose.annotation-internal:annotation:1.9.1-0.1.0-11，普通 AndroidX
annotation 锁定仍保留；这是已知的平台注解依赖差异。

禁用默认层级模板，core 使用 commonMain → nonWebMain → ohosMain → 双 ABI，dialogs 在
nonWebMain 和 ohosMain 之间增加 mobileMain。它们不继承 jvmAndNativeMain。共准备 43 份
Kotlin 源（每个 ABI 分别使用 core 22 份、dialogs 20 份）和一份 ETS 源；Kotlin 编译时核对真实
Native 源路径与哈希。根/双 ABI publication 的 GMM 和 POM 由 KGP 自然生成，不改写其字节。

group 为 io.github.qsxs.cpf.filekit，预览版本为
0.16.0-cpf11-preview-<fork SHA 前 12 位>-<run id>-<attempt>。dialogs 根 publication 额外
提供同版本 arkts-sources@zip，内容恰好为原字节 FileKitShareFactory.ets。每个 publication
附带两份 MIT 许可、来源清单与 provenance。core 根 sources JAR 收录 22 份跨 ABI 共享的
Kotlin 源，dialogs 根收录 19 份；dialogs 的两个 ABI leaf sources JAR 各在 19 份共享源码
之外包含对应 ABI 的 MainHandler，共 20 份 Kotlin 源。两份根 sources JAR 均附两份许可。

## 输入与隔离

使用包含本目录、provenance/、实际 OHOS 源集和 ETS 文件的**完整 fork 提交**，并在该提交的干净
checkout 中执行。官方上游提交本身不包含这些 OHOS 文件。每次构建必须提供完整 --source-sha、
正整数 --run-id 和 --run-attempt，以及显式、互不包含的 --work、--repository、
--native-home、--tooling 绝对路径。work 与 staging 必须在 fork checkout 外，Prepare 要求两者
均不存在；失败现场保留，重新开始请指定新的 work、staging 和运行标识。不会清理已有文件。

Plan 仅读取本次 fork 提交的 Git blob，不创建目录或构建；Prepare 对配方和固定 67 项来源的
size/SHA-256、原 source-inputs/source-patch、源映射和目标冲突逐项检查，然后从该提交的
git show 二进制内容准备源码、许可、ArkTS 以及两个源码清单。工作树换行符、其它项目和本机依赖缓存
不作为源输入。工作树配方须与提交的 blob 完全相同，修改配方后应重新提交并使用新的干净 checkout。

--tooling 指向 cpf-port-tooling 的独立 Git checkout。Prepare 和后续阶段确认该目录是 Git
根目录、HEAD 恰为 d0cff88fd1ea2e4f08bb4409f1c802cca0c7f285，并使用 tooling-lock.json 校验
20 个固定文件的 size/SHA-256，不写入该 checkout。只有核验成功后才在 work 的
tooling-verification.json 中记录实际 commit；Plan 仅报告预期 commit，并未核验工具库。

Linux x86_64 可使用该工具中的固定 bootstrap，必须指定新的隔离输出目录；bundle 的大小和
SHA-256 由其清单固定，脚本再次核验归档与 bootstrap 收据。它没有逐一独立复哈解包后的全部文件，
也未完整独立复哈 runner 上 Native 编译器实际读取的所有物理输入。Windows 使用显式提供的同版本
Native home 并检查原配方固定 compiler JAR。Linux 包内的 AbilityRuntime、ImageKit、NAPI 平台库
是否齐全须由云端实际编译确认；缺失时保留失败证据，不修改 SDK 或推测替代声明。

以下为 Linux x86_64 云端命令示例。$FORK 为干净 fork checkout，$TOOLING 为上述固定工具
checkout，$RUN_ID 和 $RUN_ATTEMPT 为运行标识。先令这三个输出路径均不存在：

~~~sh
export SOURCE_SHA="$(git -C "$FORK" rev-parse HEAD)"
export WORK_DIR="/tmp/filekit-$RUN_ID-$RUN_ATTEMPT-work"
export STAGING_DIR="/tmp/filekit-$RUN_ID-$RUN_ATTEMPT-staging"
export NATIVE_ROOT="/tmp/filekit-$RUN_ID-$RUN_ATTEMPT-native"
export NATIVE_HOME="$NATIVE_ROOT/kotlin-native-prebuilt-linux-x86_64-2.4.20-0.1.0-08"
cd "$FORK"
python3 -B ohos/FileKitPort.py Plan --source-sha "$SOURCE_SHA" --run-id "$RUN_ID" --run-attempt "$RUN_ATTEMPT" --work "$WORK_DIR" --repository "$STAGING_DIR" --native-home "$NATIVE_HOME" --tooling "$TOOLING"
python3 -B ohos/FileKitPort.py Prepare --source-sha "$SOURCE_SHA" --run-id "$RUN_ID" --run-attempt "$RUN_ATTEMPT" --work "$WORK_DIR" --repository "$STAGING_DIR" --native-home "$NATIVE_HOME" --tooling "$TOOLING"
python3 -B "$TOOLING/toolchains/kotlin-native/2.4.20-0.1.0-08/bootstrap-native.py" --destination "$NATIVE_ROOT" --kotlin-version 2.4.20-0.1.0-08
for stage in Tasks Resolve Metadata NativeX64 NativeArm64 Publish Verify; do
    python3 -B ohos/FileKitPort.py "$stage" --source-sha "$SOURCE_SHA" --run-id "$RUN_ID" --run-attempt "$RUN_ATTEMPT" --work "$WORK_DIR" --repository "$STAGING_DIR" --native-home "$NATIVE_HOME" --tooling "$TOOLING" || exit 1
done
~~~

Windows 可用相同的 Python 参数、绝对路径和阶段顺序；脚本自动调用 gradlew.bat，Linux 调用
gradlew。设置 JDK 17 的 JAVA_HOME。Gradle 用户目录、Kotlin 缓存、临时目录与 build 输出均
隔离在 work；依赖及 wrapper 在实际云构建阶段按固定坐标联网解析。发布仅写入显式 staging，不读取
Git 凭据，也不访问 Android/iOS 工程或设备。

各阶段以真实执行结果写日志、退出码和收据；失败不得复用已有收据。Verify 逐一核对两个模块的根与双
ABI 六份 publication 的坐标、owner/available-at、依赖版本及边、GMM API 变体引用的 metadata JAR
和对应 ABI KLIB 的大小与哈希、KLIB 目标与非空 IR/linkdata、KGP 编译输出、两份根 sources JAR 的预期成员、
许可、provenance 和 ETS ZIP。dialogs 根 POM 对 core 的 scope 为 runtime，ABI leaf 对对应 core
的 scope 为 compile。遇到未知或缺失字段直接失败并保留证据；
publication-verification.json 仅在全部检查通过时生成。

## 当前 producer、Pages 与 consumer 编译

[producer 40864370116/1](https://github.com/qsxs/cpf-port-tooling/actions/runs/40864370116/attempts/1) 使用 FileKit source `22683094fd5d30ef08441d073c797161804b9dc4` 和工作流 `e0116f36d402498b7a7fa2a6a37397ee5ec5b467`，完成 Metadata、OHOS x64/arm64、Publish 和 Verify；这些结果仅针对该次 producer。Metadata 的 LLVM 下载曾出现 EOF，重试后最终成功，不能将全部构建耗时归因于该下载问题。

两根 GAV 使用同一预览版本：

```text
io.github.qsxs.cpf.filekit:filekit-core:0.16.0-cpf11-preview-22683094fd5d-40864370116-1
io.github.qsxs.cpf.filekit:filekit-dialogs:0.16.0-cpf11-preview-22683094fd5d-40864370116-1
```

[Pages 源提交 `60752c680a23`](https://github.com/qsxs/cpf-maven/commit/60752c680a23322f8af23d9370677cb077011d31) 的 285 个文件、1,464,278 字节经匿名 HTTP 核对 285/285 与 staging 一致。dialogs 根同版 `arkts-sources@zip` 仅含 `FileKitShareFactory.ets`；上游与社区两份 MIT 许可均保留。

[consumer 40881058391/1](https://github.com/qsxs/cpf-port-tooling/actions/runs/40881058391/attempts/1) 已成功，使用 workflow `c4433b0c58150fafaa47696a6a72bc334e1d9d9b`、Gradle 8.11.1、JDK 17 和 Kotlin 2.4.20-0.1.0-08；source、version 与 producer fingerprint `9b6d8d85d7cb6b6ce547b55ae699f5a930433a87ce6e15b66bba96fecc6fb80c` 一致。Metadata、OHOS x64 和 arm64 三个任务实际执行成功；两个 ABI 分别选取对应平台的 core/dialogs KLIB，并核实 dialogs → core 传递边。49 次匿名请求均为 HTTP 200，其中归档的 36 份元数据可独立复核哈希；证据 ZIP 未包含编译输出二进制，不能独立重哈这些输出。

旧 P4 的完整 63 项矩阵、D019 内部短写计数、D022 原子回执及分享 drain、K3、其余 HEIC/图像矩阵和物理 ARM 等仍待验；P6 分享仍暂缓且非 PASS，OHOS 文件业务暂为 UTF-8，GBK 与共享 CSV 模板兼容延期。探针只覆盖 picker/readBytes、三参 init/session、saver、source/sink、两种压缩重载和 shareFile 调用的编译，不证明运行行为。consumer 收据中的 link、runtime、device、armDevice 和 ArkTS 编译均为 NOT_RUN。Android/iOS 平台及主工程 Gradle 门禁本次未验证；Android/iOS 和系统 SDK 不变，App 引用尚未切换。

## 验证边界

上述结果仅证明该固定 source 的 producer 和独立 consumer 三目标编译；consumer 链接与运行尚未验证。原 P4 的完整独立 GAV consumer 验收、
HAP/文件 UI、ARM 真机、文件原字节与大文件、picker 取消及迟到回调、owner 重建交错、EXIF 1–8、
真实接收方分享读回、资源多轮收敛、受影响 Android/iOS 回归等仍需分别验证。目录/相机选择、
相册写入及导入 FD 的 append 等实际源码中的明确限制保持原样，不因发布而扩大支持声明。
