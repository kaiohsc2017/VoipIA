import { RELEASES } from '../data/releases';

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatDate(s: string) {
  // Evita fuso horário deslocar o dia (new Date('YYYY-MM-DD') interpreta como UTC).
  const [year, month, day] = s.split('-');
  return `${day}/${month}/${year}`;
}

// ─── Componente Principal ─────────────────────────────────────────────────────
// Tela 100% estática — sem chamada de API — lista o changelog do sistema a
// partir de RELEASES (frontend/src/data/releases.ts), do mais recente pro mais
// antigo.

export default function Release() {
  const releasesDesc = [...RELEASES].reverse();

  return (
    <>
      <div className="page-header">
        <h1>📋 Release Notes</h1>
        <p>Histórico de versões do sistema — todas as alterações registradas por lote de entrega</p>
      </div>

      <div className="page-body">
        {releasesDesc.map(release => (
          <div key={release.version} className="stat-card" style={{ padding: '18px 20px', marginBottom: 16 }}>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, marginBottom: 10 }}>
              <span className="badge badge-info" style={{ fontSize: '0.85rem' }}>{release.version}</span>
              <span style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>{formatDate(release.date)}</span>
            </div>
            <ul style={{ margin: 0, paddingLeft: 20, display: 'flex', flexDirection: 'column', gap: 6 }}>
              {release.changes.map((change, idx) => (
                <li key={idx} style={{ fontSize: '0.88rem', lineHeight: 1.5 }}>{change}</li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    </>
  );
}
