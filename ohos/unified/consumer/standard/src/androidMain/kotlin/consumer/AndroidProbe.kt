package consumer

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.shareFile

public suspend fun androidShareProbe(file: PlatformFile) {
    FileKit.shareFile(file)
}
