import { systemShare } from '@kit.ShareKit';

/** 随同版 dialogs 制品发布；每个 UIAbility 创建自己的普通实例。 */
export class FileKitShareFactory {
    createSharedData(record: systemShare.SharedRecord): systemShare.SharedData {
        return new systemShare.SharedData(record);
    }

    createShareController(data: systemShare.SharedData): systemShare.ShareController {
        return new systemShare.ShareController(data);
    }
}
