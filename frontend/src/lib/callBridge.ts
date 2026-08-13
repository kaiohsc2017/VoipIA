/**
 * callBridge.ts — pub/sub em memória entre Softphone.tsx e CallCenterPage.tsx (Fase 13, D10-A).
 * Os dois vivem como componentes-irmãos dentro do mesmo App.tsx (shell, mesma janela) — não
 * precisam de postMessage entre si; só o CallCenterPage precisa de postMessage para o outro lado
 * da fronteira, que é o IFRAME da SPA do Call Center (outra janela).
 *
 * publishCallState: Softphone → CallCenterPage (que repassa ao iframe via postMessage).
 * dispatchCallAction: CallCenterPage (recebido do iframe via postMessage) → Softphone.
 */
export type CallStatus = 'idle' | 'registering' | 'ringing' | 'active' | 'held';

export interface CallStateSnapshot {
  status: CallStatus;
  remote: string;
  durationSeconds: number;
  muted: boolean;
}

export type CallAction =
  | { action: 'answer' }
  | { action: 'hangup' }
  | { action: 'reject' }
  | { action: 'mute' }
  | { action: 'unmute' }
  | { action: 'dtmf'; payload: string }
  | { action: 'dial'; payload: string };

type CallStateListener = (state: CallStateSnapshot) => void;
type CallActionListener = (action: CallAction) => void;

const stateListeners = new Set<CallStateListener>();
const actionListeners = new Set<CallActionListener>();
let lastState: CallStateSnapshot = { status: 'idle', remote: '', durationSeconds: 0, muted: false };

export function publishCallState(state: CallStateSnapshot): void {
  lastState = state;
  stateListeners.forEach(cb => cb(state));
}

export function subscribeCallState(cb: CallStateListener): () => void {
  stateListeners.add(cb);
  cb(lastState);
  return () => stateListeners.delete(cb);
}

export function dispatchCallAction(action: CallAction): void {
  actionListeners.forEach(cb => cb(action));
}

export function subscribeCallAction(cb: CallActionListener): () => void {
  actionListeners.add(cb);
  return () => actionListeners.delete(cb);
}
