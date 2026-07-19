// Embute a SPA de Insights (insights-platform/frontend — build Vite próprio,
// app separado servido pelo mesmo Nginx em /insights/) dentro do shell do
// Telecom, no mesmo padrão usado por AgentesPage — em vez de abrir em nova
// aba. Mesma origem (app.voiphash.com.br): o iframe compartilha o
// localStorage e por consequência a sessão (chave asteriskia_token), sem
// precisar de nenhuma ponte entre as duas aplicações.
export default function InsightsPage() {
  return (
    // iframe em tela cheia — sem page-header do Telecom para não duplicar
    // a navegação. A SPA de Insights tem seu próprio topbar/abas.
    <div style={{ display: 'flex', flex: 1, height: '100vh', margin: 0 }}>
      <iframe
        src="/insights/"
        title="Insights"
        style={{ flex: 1, width: '100%', height: '100%', border: 'none', display: 'block' }}
      />
    </div>
  );
}
