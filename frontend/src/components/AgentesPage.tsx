// Embute a Plataforma de Agentes (agents-platform/frontend — React 18 UMD,
// app separado servido pelo mesmo Nginx em /agents/) dentro do shell do
// Telecom, no mesmo padrão de página interna usado por Documentação — em vez
// de abrir em nova aba. Mesma origem (app.voiphash.com.br): o iframe
// compartilha o localStorage e por consequência a sessão (chave
// asteriskia_token), sem precisar de nenhuma ponte entre as duas aplicações.
export default function AgentesPage() {
  return (
    // iframe em tela cheia — sem page-header do Telecom para não duplicar
    // a sidebar. A Plataforma de Agentes tem sua própria navegação lateral.
    <div style={{ display: 'flex', flex: 1, height: '100vh', margin: 0 }}>
      <iframe
        src="/agents/"
        title="Plataforma de Agentes"
        style={{ flex: 1, width: '100%', height: '100%', border: 'none', display: 'block' }}
      />
    </div>
  );
}
