import { useEffect, useRef, useState } from 'react';

/** Estado da chamada refletido pelo shell (Softphone.tsx é o único UA SIP — Fase 13, D10-A). */
export interface ShellCallState {
  status: 'idle' | 'registering' | 'ringing' | 'active' | 'held';
  remote: string;
  durationSeconds: number;
  muted: boolean;
}

export type ShellCallAction =
  | { action: 'answer' }
  | { action: 'hangup' }
  | { action: 'reject' }
  | { action: 'mute' }
  | { action: 'unmute' }
  | { action: 'dtmf'; payload: string }
  | { action: 'dial'; payload: string };

/**
 * useShellBridge — ponte postMessage com o shell do Telecom quando esta SPA
 * é embutida no submenu "Call Center" (Sidebar.tsx do Telecom), em vez de
 * acessada diretamente em /callcenter. Login direto continua sem sidebar
 * própria escondida e sem nenhuma mensagem trocada (isEmbedded = false).
 * Mesmo protocolo/padrão de insights-platform/frontend/src/hooks/useShellBridge.ts
 * e agents-platform/frontend, só troca a string `source`.
 *
 * Protocolo (mesma origem, validado nos dois lados):
 *   shell → iframe: { source: 'asteriskia-shell',       type: 'navigate',   tab }
 *                   { source: 'asteriskia-shell',       type: 'callState',  payload }
 *   iframe → shell: { source: 'asteriskia-callcenter',  type: 'ready' }
 *                   { source: 'asteriskia-callcenter',  type: 'tabChanged', tab }
 *                   { source: 'asteriskia-callcenter',  type: 'callAction', payload }
 *
 * callState/callAction (Fase 13, D10-A): o softphone WebRTC é um único UA, vive só no shell
 * (Softphone.tsx) — o Desktop do Agente aqui dentro NUNCA instancia o próprio quando embutido,
 * só reflete o estado recebido e envia comandos. Quando a SPA roda fora do shell
 * (isEmbedded=false), quem consome este hook decide instanciar o próprio useSipPhone().
 */
export function useShellBridge(currentTab: string, onNavigate: (tab: string) => void): {
  isEmbedded: boolean;
  callState: ShellCallState | null;
  sendCallAction: (action: ShellCallAction) => void;
} {
  const isEmbedded = window.self !== window.top;
  // Semeado com a aba do mount (não `null`): sem isso o efeito de baixo posta um
  // 'tabChanged' espúrio no boot com a aba *default* da SPA, sobrescrevendo no
  // shell a aba que ele acabou de pedir. O shell informa a aba real no handshake 'ready'.
  const lastSentTab = useRef<string | null>(currentTab);
  const lastReceivedTab = useRef<string | null>(null);
  const onNavigateRef = useRef(onNavigate);
  onNavigateRef.current = onNavigate;
  const [callState, setCallState] = useState<ShellCallState | null>(null);

  useEffect(() => {
    if (!isEmbedded) return;

    const handleMessage = (event: MessageEvent) => {
      if (event.origin !== window.location.origin) return;
      if (event.source !== window.parent) return;
      const data = event.data;
      if (!data || data.source !== 'asteriskia-shell') return;
      if (data.type === 'navigate' && typeof data.tab === 'string') {
        lastReceivedTab.current = data.tab;
        onNavigateRef.current(data.tab);
      } else if (data.type === 'callState' && data.payload && typeof data.payload.status === 'string') {
        setCallState(data.payload as ShellCallState);
      }
    };

    window.addEventListener('message', handleMessage);
    window.parent.postMessage({ source: 'asteriskia-callcenter', type: 'ready' }, window.location.origin);

    return () => window.removeEventListener('message', handleMessage);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isEmbedded]);

  useEffect(() => {
    if (!isEmbedded) return;
    if (lastSentTab.current === currentTab || lastReceivedTab.current === currentTab) {
      lastSentTab.current = currentTab;
      return;
    }
    lastSentTab.current = currentTab;
    window.parent.postMessage({ source: 'asteriskia-callcenter', type: 'tabChanged', tab: currentTab }, window.location.origin);
  }, [isEmbedded, currentTab]);

  const sendCallAction = (action: ShellCallAction) => {
    if (!isEmbedded) return;
    window.parent.postMessage({ source: 'asteriskia-callcenter', type: 'callAction', payload: action }, window.location.origin);
  };

  return { isEmbedded, callState, sendCallAction };
}
