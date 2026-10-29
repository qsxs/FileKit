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
附带两份 MIT 许可、来源清单与 provenance，根 sources JAR 附完整源和许可。

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
和对应 ABI KLIB 的大小与哈希、KLIB 目标与非空 IR/linkdata、KGP 编译输出、两份完整 sources JAR、
许可、provenance 和 ETS ZIP。dialogs 根 POM 对 core 的 scope 为 runtime，ABI leaf 对对应 core
的 scope 为 compile。遇到未知或缺失字段直接失败并保留证据；
publication-verification.json 仅在全部检查通过时生成。

## 验证边界

本配方仅准备云端 producer 和后续消费，不代表这些构建或运行已经通过。原 P4 的独立 GAV consumer、
HAP/文件 UI、ARM 真机、文件原字节与大文件、picker 取消及迟到回调、owner 重建交错、EXIF 1–8、
真实接收方分享读回、资源多轮收敛、受影响 Android/iOS 回归等仍需分别验证。目录/相机选择、
相册写入及导入 FD 的 append 等实际源码中的明确限制保持原样，不因发布而扩大支持声明。
