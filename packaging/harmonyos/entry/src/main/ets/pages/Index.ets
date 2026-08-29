import auraLauncher, { LaunchResult, PollResult } from 'libaura_launcher.so';

const STARTUP_WINDOW_MILLIS: number = 15_000;
const DIAGNOSTIC_CHARACTER_LIMIT: number = 16 * 1024;

@Entry
@Component
struct Index {
  @State private statusText: ResourceStr = $r('app.string.status_ready');
  @State private diagnosticText: string = '';
  @State private childRunning: boolean = false;

  private logPath: string = '';
  private childPid: number = 0;
  private startedAt: number = 0;
  private pollTimer: number = -1;

  aboutToAppear(): void {
    this.logPath = getContext(this).filesDir + '/aura-launcher.log';
    setTimeout(() => this.launchAura(), 0);
  }

  aboutToDisappear(): void {
    this.clearPollTimer();
  }

  private launchAura(): void {
    if (this.childRunning) {
      return;
    }

    this.statusText = $r('app.string.status_starting');
    this.diagnosticText = '';
    const result: LaunchResult = auraLauncher.startAura(this.logPath);
    if (result.code !== 0 || result.pid <= 0) {
      this.statusText = $r('app.string.status_early_exit');
      this.diagnosticText = result.message;
      return;
    }

    this.childPid = result.pid;
    this.startedAt = Date.now();
    this.childRunning = true;
    this.clearPollTimer();
    this.pollTimer = setInterval(() => this.pollAura(), 1000);
  }

  private pollAura(): void {
    if (!this.childRunning || this.childPid <= 0) {
      this.clearPollTimer();
      return;
    }

    const result: PollResult = auraLauncher.pollAura(this.childPid);
    if (result.running) {
      if (Date.now() - this.startedAt >= STARTUP_WINDOW_MILLIS) {
        this.statusText = $r('app.string.status_started_unverified');
      } else {
        this.statusText = $r('app.string.status_starting');
      }
      return;
    }

    this.childRunning = false;
    this.clearPollTimer();
    if (result.exited) {
      this.statusText = result.exitCode === 69
        ? $r('app.string.status_prerequisite_missing')
        : $r('app.string.status_early_exit');
      const tail: string = auraLauncher.readDiagnosticTail(this.logPath);
      this.diagnosticText = tail.slice(-DIAGNOSTIC_CHARACTER_LIMIT);
    } else {
      this.statusText = $r('app.string.status_early_exit');
      this.diagnosticText = result.message;
    }
  }

  private clearPollTimer(): void {
    if (this.pollTimer >= 0) {
      clearInterval(this.pollTimer);
      this.pollTimer = -1;
    }
  }

  build(): void {
    Column({ space: 18 }) {
      Image($r('app.media.app_icon'))
        .width(88)
        .height(88)
        .objectFit(ImageFit.Contain)
        .margin({ top: 36 })

      Text($r('app.string.app_name'))
        .fontSize(28)
        .fontWeight(FontWeight.Medium)
        .fontColor('#182028')

      Text(this.statusText)
        .fontSize(16)
        .fontColor('#46515C')
        .textAlign(TextAlign.Center)
        .width('80%')
        .maxLines(3)

      if (this.diagnosticText.length > 0) {
        Text($r('app.string.diagnostics_title'))
          .fontSize(15)
          .fontWeight(FontWeight.Medium)
          .fontColor('#182028')
          .alignSelf(ItemAlign.Start)
          .margin({ left: '10%' })

        Scroll() {
          Text(this.diagnosticText)
            .fontSize(13)
            .fontColor('#303840')
            .fontFamily('monospace')
            .copyOption(CopyOptions.LocalDevice)
            .width('100%')
        }
        .width('80%')
        .height('32%')
        .padding(12)
        .backgroundColor('#FFFFFF')
        .border({ width: 1, color: '#D4D9DE' })
        .borderRadius(6)
      }

      Button($r('app.string.retry'))
        .enabled(!this.childRunning)
        .visibility(this.childRunning ? Visibility.Hidden : Visibility.Visible)
        .backgroundColor('#137A63')
        .fontColor('#FFFFFF')
        .onClick(() => this.launchAura())
    }
    .width('100%')
    .height('100%')
    .padding({ left: 24, right: 24, bottom: 28 })
    .backgroundColor('#F4F6F8')
    .alignItems(HorizontalAlign.Center)
  }
}
