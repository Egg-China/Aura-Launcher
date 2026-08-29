export interface LaunchResult {
  code: number;
  pid: number;
  message: string;
}

export interface PollResult {
  running: boolean;
  exited: boolean;
  exitCode: number;
  message: string;
}

export const startAura: (logPath: string) => LaunchResult;
export const pollAura: (pid: number) => PollResult;
export const readDiagnosticTail: (logPath: string) => string;
