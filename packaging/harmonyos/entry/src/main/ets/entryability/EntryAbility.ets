import hilog from '@ohos.hilog';
import UIAbility from '@ohos.app.ability.UIAbility';
import window from '@ohos.window';

const LOG_DOMAIN: number = 0xA11A;
const LOG_TAG: string = 'AuraLauncher';

export default class EntryAbility extends UIAbility {
  onWindowStageCreate(windowStage: window.WindowStage): void {
    windowStage.loadContent('pages/Index', (error) => {
      if (error.code) {
        hilog.error(LOG_DOMAIN, LOG_TAG, 'Unable to load Aura Launcher UI: %{public}s', error.message);
      }
    });
  }
}
