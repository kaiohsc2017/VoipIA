import { useEffect, useState } from 'react';
import { Settings, Play } from 'lucide-react';
import api from '../api/client';
import { AuthedAudio } from './AuthedAudio';
import { RetencaoAlertaTab } from './RetencaoAlertaTab';
import { CobrowsingTab } from './CobrowsingTab';
import type { CcQueue, CcRecording, Page } from '../api/types';

/**
 * GravacoesTab — lista/streaming das gravações de fila do Call Center (Fase 3). RBAC:
 * `canWrite` só controla o acesso à configuração de retenção/alerta de disco (a listagem em si
 * é só leitura — não há CRUD de gravações, elas são geradas pelo dialplan).
 *
 * Sub-view "Co-browsing" (Fase 17c) tem RBAC próprio (`callcenter.cobrowsing`, não reusa
 * `callcenter.gravacoes` — decisão do plano §6) — `canReadCobrowsing`/`canWriteCobrowsing`
 * controlam a aba/ações por conta própria, distintos de `canWrite` desta prop.
 */
export function GravacoesTab({
  canWrite, canReadCobrowsing, canWriteCobrowsing,
}: {
  canWrite: boolean;
  canReadCobrowsing?: boolean;
  canWriteCobrowsing?: boolean;
}) {
  const [subView, setSubView] = useState<'gravacoes' | 'cobrowsing'>('gravacoes');
  const [recordings, setRecordings] = useState<CcRecording[]>([]);
  const [totalPages, setTotalPages] = useState(0);
  const [page, setPage] = useState(0);
  const [queues, setQueues] = useState<CcQueue[]>([]);
  const [queueId, setQueueId] = useState<number | ''>('');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [playingId, setPlayingId] = useState<number | null>(null);
  const [showConfig, setShowConfig] = useState(false);

  const load = () => {
    const params: Record<string, string | number> = { page, size: 20 };
    if (queueId !== '') params.queueId = queueId;
    if (dateFrom) params.dateFrom = dateFrom;
    if (dateTo) params.dateTo = dateTo;
    api.get<Page<CcRecording>>('/callcenter/recordings', { params })
      .then(({ data }) => { setRecordings(data.content); setTotalPages(data.totalPages); })
      .catch(() => { setRecordings([]); setTotalPages(0); });
  };

  useEffect(load, [page, queueId, dateFrom, dateTo]);
  useEffect(() => {
    api.get<CcQueue[]>('/callcenter/filas').then(({ data }) => setQueues(data)).catch(() => setQueues([]));
  }, []);

  const handleQueueChange = (value: string) => {
    setPage(0);
    setQueueId(value ? Number(value) : '');
  };
  const handleDateFromChange = (value: string) => { setPage(0); setDateFrom(value); };
  const handleDateToChange = (value: string) => { setPage(0); setDateTo(value); };

  const formatDuration = (seconds?: number) => {
    if (seconds == null) return '—';
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${m}:${s.toString().padStart(2, '0')}`;
  };

  return (
    <>
      <div className="page-header">
        <div className="flex items-center justify-between">
          <div><h1>Gravações</h1><p>Gravações das chamadas de fila do Call Center</p></div>
          {canWrite && (
            <button className="btn btn-ghost" onClick={() => setShowConfig(true)}>
              <Settings size={14} /> Configurações
            </button>
          )}
        </div>
      </div>
      <div className="page-body">
        {canReadCobrowsing && (
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 16 }}>
            <button type="button" onClick={() => setSubView('gravacoes')} disabled={subView === 'gravacoes'}>Gravações de voz</button>
            <button type="button" onClick={() => setSubView('cobrowsing')} disabled={subView === 'cobrowsing'}>Co-browsing (chat)</button>
          </div>
        )}
        {subView === 'cobrowsing' && canReadCobrowsing ? (
          <CobrowsingTab canWrite={!!canWriteCobrowsing} />
        ) : (
        <>
        <div className="form-grid" style={{ marginBottom: 16 }}>
          <div className="form-group">
            <label className="form-label">Fila</label>
            <select className="form-input" value={queueId}
              onChange={e => handleQueueChange(e.target.value)}>
              <option value="">— Todas —</option>
              {queues.map(q => <option key={q.id} value={q.id}>{q.displayName} ({q.name})</option>)}
            </select>
          </div>
          <div className="form-group">
            <label className="form-label">De</label>
            <input type="date" className="form-input" value={dateFrom}
              onChange={e => handleDateFromChange(e.target.value)} />
          </div>
          <div className="form-group">
            <label className="form-label">Até</label>
            <input type="date" className="form-input" value={dateTo}
              onChange={e => handleDateToChange(e.target.value)} />
          </div>
        </div>

        {showConfig && <RetencaoAlertaTab canWrite={canWrite} onClose={() => setShowConfig(false)} />}

        <div className="table-wrapper">
          <table>
            <thead>
              <tr>
                <th>Fila</th><th>Início</th><th>Duração</th><th>Aviso tocado</th><th>BU</th><th></th>
              </tr>
            </thead>
            <tbody>
              {recordings.map(rec => (
                <tr key={rec.id}>
                  <td>{rec.queue?.displayName ?? rec.queueExtension} <span style={{ color: 'var(--text-muted)' }}>({rec.queueExtension})</span></td>
                  <td>{new Date(rec.startedAt).toLocaleString('pt-BR')}</td>
                  <td>{formatDuration(rec.durationSeconds)}</td>
                  <td><span className={`badge ${rec.consentPlayed ? 'badge-success' : 'badge-gray'}`}>{rec.consentPlayed ? 'Sim' : 'Não'}</span></td>
                  <td>{rec.businessUnit?.name ?? '—'}</td>
                  <td>
                    {playingId === rec.id
                      ? <AuthedAudio path={`/callcenter/recordings/${rec.id}/audio`} autoPlay />
                      : <button className="btn btn-ghost btn-sm" onClick={() => setPlayingId(rec.id)}><Play size={14} /></button>}
                  </td>
                </tr>
              ))}
              {recordings.length === 0 && <tr><td colSpan={6} className="table-empty">Nenhuma gravação encontrada.</td></tr>}
            </tbody>
          </table>
        </div>

        {totalPages > 1 && (
          <div className="flex items-center justify-between" style={{ marginTop: 12 }}>
            <button className="btn btn-ghost btn-sm" disabled={page === 0} onClick={() => setPage(p => p - 1)}>Anterior</button>
            <span style={{ color: 'var(--text-muted)', fontSize: '.85rem' }}>Página {page + 1} de {totalPages}</span>
            <button className="btn btn-ghost btn-sm" disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)}>Próxima</button>
          </div>
        )}
        </>
        )}
      </div>
    </>
  );
}
