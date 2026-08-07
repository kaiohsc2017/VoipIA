import { useEffect, useRef } from 'react';

interface CallCenterPageProps {
  tab: string;
  onTabChange: (tab: string) => void;
}

// Embute a SPA do Call Center (callcenter-platform/frontend — build Vite próprio,
// servida em /callcenter/ pelo mesmo Nginx do Telecom) dentro do shell, mesmo
// padrão de InsightsPage.tsx/AgentesPage.tsx. Mesma origem — o iframe compartilha
// localStorage/sessão sem ponte de autenticação própria.
export default function CallCenterPage({ tab, onTabChange }: CallCenterPageProps) {
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const isReady = useRef(false);
  const pendingTab = useRef(tab);
  pendingTab.current = tab;
  const onTabChangeRef = useRef(onTabChange);
  onTabChangeRef.current = onTabChange;

  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      if (event.origin !== window.location.origin) return;
      if (event.source !== iframeRef.current?.contentWindow) return;
      const data = event.data;
      if (!data || data.source !== 'asteriskia-callcenter') return;
      if (data.type === 'ready') {
        isReady.current = true;
        iframeRef.current?.contentWindow?.postMessage(
          { source: 'asteriskia-shell', type: 'navigate', tab: pendingTab.current },
          window.location.origin,
        );
      } else if (data.type === 'tabChanged' && typeof data.tab === 'string') {
        onTabChangeRef.current(data.tab);
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

  return (
    <div style={{ display: 'flex', flex: 1, height: '100vh', margin: 0 }}>
      <iframe
        ref={iframeRef}
        src="/callcenter/"
        title="Call Center"
        style={{ flex: 1, width: '100%', height: '100%', border: 'none', display: 'block' }}
      />
    </div>
  );
}
