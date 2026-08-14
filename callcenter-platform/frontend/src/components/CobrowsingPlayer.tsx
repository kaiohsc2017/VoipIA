import { useEffect, useState } from 'react';
import api from '../api/client';

// Vendorizado em public/vendor/rrweb-player/ (Fase 17c) — servido pelo mesmo nginx da SPA sob
// o base path /callcenter/.
const PLAYER_JS = '/callcenter/vendor/rrweb-player/rrweb-player.umd.min.js';
const PLAYER_CSS = '/callcenter/vendor/rrweb-player/style.min.css';

/**
 * CobrowsingPlayer — reprodução de uma sessão de co-browsing gravado do chat (Fase 17c).
 *
 * O `.jsonl` contém eventos rrweb com HTML/CSS/atributos capturados de uma página real, dado
 * de origem **não confiável** — nunca renderizado na SPA autenticada (evitaria XSS contra a
 * própria sessão de staff). O replay roda dentro de um `<iframe sandbox="allow-scripts">`
 * **sem** `allow-same-origin`: isso garante que o documento do iframe recebe uma origem opaca
 * (`null`), sem acesso a cookies/localStorage/DOM do domínio real, mesmo que o replay do rrweb
 * (que reconstrói HTML arbitrário dentro do próprio `#player`) contenha algo malicioso. A
 * combinação perigosa seria `allow-scripts allow-same-origin` juntos — isso permitiria ao
 * conteúdo do iframe *remover* seu próprio sandbox via JS (escapando da restrição). Sem
 * `allow-same-origin`, mesmo com `allow-scripts`, o documento nunca ganha uma origem "real" —
 * scripts rodam, mas isolados. Não usa `allow-popups`/`allow-forms`/`allow-top-navigation`.
 */
export function CobrowsingPlayer({ sessionId, onClose }: { sessionId: number; onClose: () => void }) {
  const [srcDoc, setSrcDoc] = useState<string | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setSrcDoc(null);
    setError(false);
    api.get(`/callcenter/cobrowsing/${sessionId}/events`)
      .then(({ data }) => {
        if (cancelled) return;
        setSrcDoc(buildPlayerHtml(data));
      })
      .catch(() => { if (!cancelled) setError(true); });
    return () => { cancelled = true; };
  }, [sessionId]);

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" style={{ maxWidth: 960, width: '95%' }} onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <h2>Reprodução de co-browsing — sessão {sessionId}</h2>
          <button className="btn btn-ghost btn-sm" onClick={onClose}>Fechar</button>
        </div>
        <div className="modal-body">
          {error && (
            <p style={{ color: 'var(--danger)' }}>
              Não foi possível carregar a reprodução (sessão indisponível, consentimento não
              concedido ou arquivo ausente).
            </p>
          )}
          {!error && !srcDoc && <span className="spinner" />}
          {!error && srcDoc && (
            <iframe
              title={`co-browsing-${sessionId}`}
              sandbox="allow-scripts"
              srcDoc={srcDoc}
              style={{ width: '100%', height: 600, border: 'none', background: '#1e1e1e' }}
            />
          )}
        </div>
      </div>
    </div>
  );
}

/**
 * Monta o documento do iframe embutindo os eventos como um literal JS — nunca via
 * `dangerouslySetInnerHTML` no DOM real da SPA, só como texto dentro do `srcDoc` de um iframe já
 * isolado por sandbox. `<` evita que um evento capturado contendo a string `</script>`
 * feche a tag de script prematuramente.
 */
function buildPlayerHtml(events: unknown): string {
  const safeEvents = JSON.stringify(events ?? []).replace(/</g, '\\u003c');
  const origin = window.location.origin;
  return `<!DOCTYPE html><html><head>
<meta charset="utf-8" />
<link rel="stylesheet" href="${origin}${PLAYER_CSS}" />
<style>html,body{margin:0;padding:0;background:#1e1e1e;}</style>
</head><body>
<div id="player"></div>
<script src="${origin}${PLAYER_JS}"><\/script>
<script>
(function () {
  try {
    var events = ${safeEvents};
    if (!events.length) {
      document.body.innerHTML = '<p style="color:#ccc;font-family:sans-serif;padding:16px;">Nenhum evento capturado nesta sessão.</p>';
      return;
    }
    new rrwebPlayer({ target: document.getElementById('player'), props: { events: events, width: 900, height: 560, autoPlay: false } });
  } catch (e) {
    document.body.innerHTML = '<p style="color:#f66;font-family:sans-serif;padding:16px;">Erro ao reproduzir a sessão de co-browsing.</p>';
  }
})();
<\/script>
</body></html>`;
}
