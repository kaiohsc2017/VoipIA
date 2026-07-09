import { ExternalLink } from 'lucide-react';

// Embute a Plataforma de Agentes (agents-platform/frontend — React 18 UMD,
// app separado servido pelo mesmo Nginx em /agents/) dentro do shell do
// Telecom, no mesmo padrão de página interna usado por Documentação — em vez
// de abrir em nova aba. Mesma origem (app.voiphash.com.br): o iframe
// compartilha o localStorage e por consequência a sessão (chave
// asteriskia_token), sem precisar de nenhuma ponte entre as duas aplicações.
export default function AgentesPage() {
  return (
    <>
      <div className="page-header" style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 16 }}>
        <div>
          <h1>Agentes</h1>
          <p>Plataforma de automação e monitoramento autônomo</p>
        </div>
        <a
          href="/agents/"
          target="_blank"
          rel="noopener noreferrer"
          className="btn btn-ghost btn-sm"
          style={{ flexShrink: 0, marginTop: 4 }}
        >
          <ExternalLink size={14} />
          Abrir em nova aba
        </a>
      </div>

      <div className="page-body" style={{ padding: 0, display: 'flex', flex: 1 }}>
        <iframe
          src="/agents/?embedded=1"
          title="Plataforma de Agentes"
          style={{ flex: 1, width: '100%', height: 'calc(100vh - 90px)', border: 'none' }}
        />
      </div>
    </>
  );
}
