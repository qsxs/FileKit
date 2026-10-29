# FileKit OHOS source provenance

This directory preserves the inputs and review artifacts from the FileKit port at
`harmony-build/ports/filekit/` in project commit
`f9a23a22ab1839754802535ea172c2c0891f1eb1`.

- Official upstream: `vinceglb/FileKit`, commit `e8876bcefec8e502ac61a0af45f00de02c253fbf`.
  Its original MIT license and copyright notice are in the fork root `LICENSE`.
- Community source: `HachimiWorld/FileKit-ohos`, commit `f44915a297d555a614b965f2245c09ade5183c8a`.
  Its original MIT license and copyright notice are in `vendor/community/LICENSE` under this directory.
- `source-inputs.json` and `source-patch.json` are the unchanged original manifests.
  `source-map.json` records the original-to-fork paths, source commits, SHA-256 hashes and byte
  lengths, including the selected official inputs already present in this fork. The official
  inputs are not copied again into this directory.

## Verifying upstream inputs and preparing build inputs

The 35 `upstream-input` entries in `source-map.json` describe Git blob bytes. Verify
each entry's size and SHA-256 against the blob at
`e8876bcefec8e502ac61a0af45f00de02c253fbf:<forkPath>`, using a byte-preserving Git
blob read such as `git show <commit>:<forkPath>` captured as binary bytes. Do not hash
the checked-out worktree: Git may have converted its line endings via `core.autocrlf`.

A separate OHOS build must pin an exact FileKit fork commit containing the OHOS
source sets and prepare its actual FileKit inputs from that commit's Git blobs,
including both official and OHOS sources. The upstream reference commit above predates
the copied OHOS source sets and is not itself that later fork commit. Neither
verification nor build-input preparation changes attributes of upstream source files.

## Path mapping

Paths in the original manifests are relative to the original port root. Paths below are
relative to the fork root:

- `vendor/upstream/<path>` maps to `<path>` in this fork at the official commit.
- `vendor/community/<path>` maps to `ohos/provenance/vendor/community/<path>`.
- `patches/<path>` maps to `ohos/provenance/patches/<path>`.
- `overlay/<module>/ohosMain/<path>` maps to `<module>/src/ohosMain/kotlin/<path>`.
- The two `unmodifiedAbiHandlers` map from the community input paths to the corresponding
  `filekit-dialogs/src/ohosX64Main/kotlin/` and `ohosArm64Main/kotlin/` files; the source
  copies are retained in `ohos/provenance/vendor/community/`.
- `arkts/FileKitShareFactory.ets` maps to `filekit-dialogs/src/ohosMain/arkts/FileKitShareFactory.ets`.

The eight zero-context patches are retained only for review. With the path mapping above,
each patch compares the retained community source against its copied overlay; the overlay
is the actual source-set file, not a generated patch result. In particular,
`PlatformFileIO.ohos.kt` is based on both the retained community source and the official
`filekit-core/src/jvmAndNativeMain/kotlin/io/github/vinceglb/filekit/PlatformFile.jvmAndNative.kt`
listed in `source-patch.json`.

## OHOS source-set topology

The arrows below run from the parent source set to the source set that inherits it:

- Core: `commonMain -> nonWebMain -> ohosMain`, with
  `ohosX64Main.dependsOn(ohosMain)` and `ohosArm64Main.dependsOn(ohosMain)`.
- Dialogs: `commonMain -> nonWebMain -> mobileMain -> ohosMain`, with
  `ohosX64Main.dependsOn(ohosMain)` and `ohosArm64Main.dependsOn(ohosMain)`.

These OHOS chains must be configured independently of the upstream default Native
hierarchy. Upstream `nativeMain` inherits `jvmAndNativeMain`; using that hierarchy for
OHOS also includes `PlatformFile.jvmAndNative.kt`, whose actual declarations overlap
those in `PlatformFileIO.ohos.kt`. The copied source placement alone therefore does
not make it safe to enable OHOS through the existing upstream Native hierarchy.

A later OHOS build must use the source-set Kotlin and ArkTS files prepared from the
pinned fork commit's blobs and must not apply these patches as a build step. No build
configuration or platform build was changed or validated by this source migration.
