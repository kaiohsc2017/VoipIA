import { useEffect, useState } from 'react';
import api from '../api/client';
import type { CcScorecardDto } from '../api/types';

/** Aba "Fichas de Qualidade" do Call Center (Fase 8) — somente leitura. A configuração
 * da ficha de avaliação é global (mesma usada pelo Insights Verint) e deliberadamente não
 * duplicada aqui: o Call Center reusa GET /insights/scorecards com a permissão
 * callcenter.insights.scorecards como autoridade alternativa de leitura (ver
 * SecurityConfig.java). Editar a ficha continua exclusivo da tela de Insights. */
export function ScorecardsViewTab() {
  const [scorecards, setScorecards] = useState<CcScorecardDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    api.get<CcScorecardDto[]>('/insights/scorecards')
      .then(r => setScorecards(r.data))
      .catch(err => {
        console.error('Erro ao carregar fichas de avaliação:', err);
        setError('Falha ao carregar fichas de avaliação.');
      })
      .finally(() => setLoading(false));
  }, []);

  return (
    <>
      <div className="toolbar">
        <div className="toolbar-left">
          <h2 style={{ margin: 0 }}>Fichas de Qualidade</h2>
        </div>
      </div>
      <p className="td-muted" style={{ marginBottom: 16 }}>
        Configuração global de ficha de avaliação (compartilhada com o Insights) — somente leitura aqui.
      </p>

      {loading ? (
        <div className="loading-state"><div className="spinner" />Carregando…</div>
      ) : error ? (
        <div className="alert alert-error">{error}</div>
      ) : scorecards.length === 0 ? (
        <div style={{ textAlign: 'center', padding: 40, color: 'var(--text-muted)' }}>Nenhuma ficha de avaliação cadastrada ainda</div>
      ) : (
        scorecards.map(sc => (
          <div key={sc.id} className="stat-card" style={{ padding: 20, marginBottom: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
              <h3 style={{ margin: 0, fontSize: '0.95rem' }}>{sc.name}</h3>
              {sc.isActive && <span className="badge badge-info">Ativa</span>}
              <span className="td-muted" style={{ fontSize: '.8rem' }}>v{sc.version}</span>
            </div>
            {sc.description && <p className="td-muted" style={{ marginBottom: 12 }}>{sc.description}</p>}
            <div className="table-wrapper">
              <table>
                <thead>
                  <tr><th>Ordem</th><th>Pergunta</th><th>Peso</th><th>Nota máxima</th><th>Crítico</th></tr>
                </thead>
                <tbody>
                  {sc.items.map(item => (
                    <tr key={item.id ?? item.ordem}>
                      <td className="td-muted">{item.ordem}</td>
                      <td>{item.pergunta}</td>
                      <td className="td-muted">{item.peso}</td>
                      <td className="td-muted">{item.notaMaxima}</td>
                      <td>{item.isCritical ? 'Sim' : 'Não'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        ))
      )}
    </>
  );
}
