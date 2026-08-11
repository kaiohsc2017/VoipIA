import { useEffect, useRef } from 'react';

/**
 * useShellBridge — ponte postMessage com o shell do Telecom quando esta SPA
 * é embutida no submenu "Insights" (Sidebar.tsx do Telecom), em vez de
 * acessada diretamente em /insights. Login direto continua sem sidebar
 * própria escondida e sem nenhuma mensagem trocada (isEmbedded = false).
 *
 * Protocolo (mesma origem, validado nos dois lados):
 *   shell → iframe: { source: 'asteriskia-shell',     type: 'navigate',   tab }
 *   iframe → shell: { source: 'asteriskia-insights',  type: 'ready' }
 *                   { source: 'asteriskia-insights',  type: 'tabChanged', tab }
 */
export function useShellBridge(currentTab: string, onNavigate: (tab: string) => void): { isEmbedded: boolean } {
  const isEmbedded = window.self !== window.top;
  // Semeado com a aba do mount (não `null`): sem isso o efeito de baixo posta um
  // 'tabChanged' espúrio no boot com a aba *default* da SPA, sobrescrevendo no
  // shell a aba que ele acabou de pedir (ex: clicar em "Relatórios" voltava
  // o menu pra "Chamadas"). O shell informa a aba real no handshake 'ready'.
  const lastSentTab = useRef<string | null>(currentTab);
  const lastReceivedTab = useRef<string | null>(null);
  const onNavigateRef = useRef(onNavigate);
  onNavigateRef.current = onNavigate;

  useEffect(() => {
    if (!isEmbedded) return;

    const handleMessage = (event: MessageEvent) => {
      if (event.origin !== window.location.origin) return;
      if (event.source !== window.parent) return;
      const data = event.data;
      if (!data || data.source !== 'asteriskia-shell') return;
      if (data.type === 'navigate' && typeof data.tab === 'string') {
        lastReceivedTab.current = data.tab;
        // Sem comparar com `currentTab`: este listener monta uma única vez, então
        // capturaria a aba congelada no boot e bloquearia pra sempre a volta pra
        // ela. Chamar com a aba já ativa é inofensivo (o setState de mesmo valor
        // não re-renderiza), e o eco é suprimido por lastReceivedTab abaixo.
        onNavigateRef.current(data.tab);
      }
    };

    window.addEventListener('message', handleMessage);
    window.parent.postMessage({ source: 'asteriskia-insights', type: 'ready' }, window.location.origin);

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
    window.parent.postMessage({ source: 'asteriskia-insights', type: 'tabChanged', tab: currentTab }, window.location.origin);
  }, [isEmbedded, currentTab]);

  return { isEmbedded };
}
