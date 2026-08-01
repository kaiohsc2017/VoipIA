import { useEffect, useRef } from 'react';

interface AgentesPageProps {
  tab: string;
  onTabChange: (tab: string) => void;
  onAlertCount: (count: number) => void;
}

// Embute a Plataforma de Agentes (agents-platform/frontend — build Vite
// próprio, app separado servido pelo mesmo Nginx em /agents/) dentro do
// shell do Telecom, no mesmo padrão de página interna usado por
// Documentação — em vez de abrir em nova aba. Mesma origem
// (app.voiphash.com.br): o iframe compartilha o localStorage e por
// consequência a sessão (chave asteriskia_token), sem precisar de nenhuma
// ponte de autenticação entre as duas aplicações.
//
// A troca de aba do submenu "Agentes" na Sidebar do Telecom não remonta o
// iframe (recarregaria a SPA inteira) — viaja por postMessage
// (useShellBridge.ts do lado da SPA). O handshake 'ready' evita perder a
// primeira mensagem 'navigate' caso o shell tente postar antes da SPA
// montar o listener.
export default function AgentesPage({ tab, onTabChange, onAlertCount }: AgentesPageProps) {
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const isReady = useRef(false);
  const pendingTab = useRef(tab);
  pendingTab.current = tab;
  const onTabChangeRef = useRef(onTabChange);
  onTabChangeRef.current = onTabChange;
  const onAlertCountRef = useRef(onAlertCount);
  onAlertCountRef.current = onAlertCount;

  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      if (event.origin !== window.location.origin) return;
      if (event.source !== iframeRef.current?.contentWindow) return;
      const data = event.data;
      if (!data || data.source !== 'asteriskia-agents') return;
      if (data.type === 'ready') {
        isReady.current = true;
        iframeRef.current?.contentWindow?.postMessage(
          { source: 'asteriskia-shell', type: 'navigate', tab: pendingTab.current },
          window.location.origin,
        );
      } else if (data.type === 'tabChanged' && typeof data.tab === 'string') {
        onTabChangeRef.current(data.tab);
      } else if (data.type === 'alertCount' && typeof data.count === 'number') {
        onAlertCountRef.current(data.count);
      }
    };

    window.addEventListener('message', handleMessage);
    return () => window.removeEventListener('message', handleMessage);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!isReady.current) return;
    iframeRef.current?.contentWindow?.postMessage(
      { source: 'asteriskia-shell', type: 'navigate', tab },
      window.location.origin,
    );
  }, [tab]);

  // Zera a contagem ao desmontar: sair de Agentes destrói o iframe e nenhuma
  // mensagem 'alertCount' chega mais — o badge no submenu ficaria congelado
  // no último valor visto, possivelmente já resolvido há minutos.
  useEffect(() => {
    return () => onAlertCountRef.current(0);
  }, []);

  return (
    // iframe em tela cheia — sem page-header do Telecom para não duplicar
    // a sidebar. A Plataforma de Agentes, em modo embutido, esconde sua
    // própria navegação lateral (ver useShellBridge.ts).
    <div style={{ display: 'flex', flex: 1, height: '100vh', margin: 0 }}>
      <iframe
        ref={iframeRef}
        src="/agents/"
        title="Plataforma de Agentes"
        style={{ flex: 1, width: '100%', height: '100%', border: 'none', display: 'block' }}
      />
    </div>
  );
}
