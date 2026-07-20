import { useEffect, useRef, useState } from 'react';
import api, { getErrorMessage } from '../api/client';
import { AuthedAudio } from './AuthedAudio';
import type { UploadBatchDto, InsightsDetailResponse, PageResponse } from '../api/types';

interface SupervisorPortalTabProps {
  canWrite: boolean;
  isAdmin: boolean;
}

const MAX_FILES = 100;
const STATUS_LABELS: Record<string, string> = {
  pending: 'Na fila', processing: 'Processando', done: 'Concluído', error: 'Erro',
};

/** Aba "Meus Envios" — portal do supervisor: upload em lote de até 100 áudios para
 * transcrição/análise ad-hoc (Fase 3 do Quality Management, V40). Tela única — lista de
 * lotes com status por arquivo (com polling de 10s enquanto houver pending/processing,
 * diferente da aba Processamento original) e, ao clicar num arquivo, os dados da
 * ligação/nota/insights inline. Posse: supervisor só vê os próprios lotes; ADMIN vê
 * todos com coluna de quem enviou. */
export function SupervisorPortalTab({ canWrite, isAdmin }: SupervisorPortalTabProps) {
  const [batches, setBatches] = useState<UploadBatchDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedBatch, setSelectedBatch] = useState<UploadBatchDto | null>(null);

  const [files, setFiles] = useState<File[]>([]);
  const [agentName, setAgentName] = useState('');
  const [direction, setDirection] = useState('');
  const [notes, setNotes] = useState('');
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [detailId, setDetailId] = useState<number | null>(null);
  const [detail, setDetail] = useState<InsightsDetailResponse | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const loadBatches = () => {
    api.get<PageResponse<UploadBatchDto>>('/insights/uploads?page=0&size=20')
      .then(r => setBatches(r.data.content ?? []))
      .catch(err => { console.error('Erro ao carregar lotes de upload:', err); setBatches([]); })
      .finally(() => setLoading(false));
  };

  useEffect(() => { loadBatches(); }, []);

  const loadBatchDetail = (id: string) => {
    api.get<UploadBatchDto>(`/insights/uploads/${id}`)
      .then(r => setSelectedBatch(r.data))
      .catch(err => console.error('Erro ao carregar lote:', err));
  };

  // Polling de 10s enquanto o lote aberto tiver arquivo pending/processing.
  useEffect(() => {
    if (!selectedBatch) return;
    const hasInFlight = selectedBatch.files?.some(f => f.status === 'pending' || f.status === 'processing');
    if (!hasInFlight) return;
    const interval = setInterval(() => loadBatchDetail(selectedBatch.id), 10_000);
    return () => clearInterval(interval);
  }, [selectedBatch]);

  const handleFileSelect = (selected: FileList | null) => {
    if (!selected) return;
    const list = Array.from(selected).slice(0, MAX_FILES);
    setFiles(list);
  };

  const submitUpload = async () => {
    if (files.length === 0) {
      setUploadError('Selecione ao menos um arquivo.');
      return;
    }
    setUploading(true);
    setUploadError('');
    const form = new FormData();
    files.forEach(f => form.append('files', f));
    if (agentName) form.append('agentName', agentName);
    if (direction) form.append('direction', direction);
    if (notes) form.append('notes', notes);
    try {
      await api.post('/insights/uploads', form, { headers: { 'Content-Type': 'multipart/form-data' } });
      setFiles([]);
      setAgentName(''); setDirection(''); setNotes('');
      if (fileInputRef.current) fileInputRef.current.value = '';
      loadBatches();
    } catch (err) {
      setUploadError(getErrorMessage(err, 'Falha ao enviar arquivos'));
    } finally {
      setUploading(false);
    }
  };

  const openDetail = (id: number) => {
    setDetailId(id);
    setDetailLoading(true);
    api.get<InsightsDetailResponse>(`/insights/calls/${id}`)
      .then(r => setDetail(r.data))
      .catch(err => { console.error('Erro ao carregar detalhe:', err); alert('Erro ao carregar detalhe.'); setDetailId(null); })
      .finally(() => setDetailLoading(false));
  };
  const closeDetail = () => { setDetailId(null); setDetail(null); };

  if (selectedBatch) {
    return (
      <>
        {detailId !== null && (
          <div className="modal-overlay" onClick={closeDetail}>
            <div className="modal" style={{ maxWidth: 720, width: '96vw' }} onClick={e => e.stopPropagation()}>
              <div className="modal-header">
                <h3 style={{ fontSize: '1rem', fontWeight: 600 }}>Chamada {detail?.audioFile.callRef ?? detailId}</h3>
                <button className="btn-close" onClick={closeDetail}>×</button>
              </div>
              <div className="modal-body" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
                {detailLoading || !detail ? (
                  <div className="loading-state"><div className="spinner" />Carregando…</div>
                ) : (
                  <>
                    <AuthedAudio path={`/insights/calls/${detailId}/audio`} style={{ width: '100%' }} />
                    {detail.insights && (
                      <div>
                        <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 4 }}>Resumo</div>
                        <p>{detail.insights.resumo}</p>
                      </div>
                    )}
                    {detail.evaluation && (
                      <div>
                        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                          <span className="chip">Nota: {detail.evaluation.notaTotal.toFixed(1)}</span>
                          {detail.evaluation.isFailed && <span className="badge badge-danger">Reprovada</span>}
                        </div>
                      </div>
                    )}
                    {detail.findings.length > 0 && (
                      <div>
                        <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 4 }}>Achados</div>
                        <ul>{detail.findings.map(f => <li key={f.id}>[{f.tipo}] {f.descricao}</li>)}</ul>
                      </div>
                    )}
                  </>
                )}
              </div>
            </div>
          </div>
        )}

        <div className="toolbar">
          <div className="toolbar-left">
            <h2 style={{ margin: 0 }}>Lote — {new Date(selectedBatch.createdAt).toLocaleString('pt-BR')}</h2>
          </div>
          <div className="toolbar-right">
            <button className="btn btn-ghost btn-sm" onClick={() => setSelectedBatch(null)}>← Voltar</button>
          </div>
        </div>
        {isAdmin && <p className="td-muted" style={{ marginBottom: 12 }}>Enviado por {selectedBatch.uploadedBy}</p>}
        {selectedBatch.notes && <p className="td-muted" style={{ marginBottom: 12 }}>Observação: {selectedBatch.notes}</p>}

        <div className="table-wrapper">
          <table>
            <thead><tr><th>Arquivo</th><th>Atendente</th><th>Direção</th><th>Duração</th><th>Status</th></tr></thead>
            <tbody>
              {(selectedBatch.files ?? []).map(f => (
                <tr key={f.id} onClick={() => f.status === 'done' && openDetail(f.id)}
                    style={{ cursor: f.status === 'done' ? 'pointer' : 'default' }}>
                  <td>{f.callRef}</td>
                  <td className="td-muted">{f.agentName || '—'}</td>
                  <td className="td-muted">{f.direction || '—'}</td>
                  <td className="td-muted">{f.durationSeconds ? `${f.durationSeconds}s` : '—'}</td>
                  <td>
                    <span className="badge badge-info">{STATUS_LABELS[f.status] ?? f.status}</span>
                    {f.status === 'error' && f.errorMsg && (
                      <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginTop: 2 }}>{f.errorMsg}</div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </>
    );
  }

  return (
    <>
      <div className="toolbar">
        <div className="toolbar-left"><h2 style={{ margin: 0 }}>Meus Envios</h2></div>
      </div>

      {canWrite && (
        <div className="stat-card" style={{ padding: 16, marginBottom: 20 }}>
          <div className="form-grid" style={{ gridTemplateColumns: '1fr 1fr 1fr', gap: 12, marginBottom: 12 }}>
            <div>
              <label className="form-label">Atendente (opcional, aplicado a todo o lote)</label>
              <input className="form-input" value={agentName} onChange={e => setAgentName(e.target.value)} />
            </div>
            <div>
              <label className="form-label">Direção</label>
              <select className="form-select" value={direction} onChange={e => setDirection(e.target.value)}>
                <option value="">Qualquer</option>
                <option value="inbound">Recebida</option>
                <option value="outbound">Efetuada</option>
              </select>
            </div>
            <div>
              <label className="form-label">Observação</label>
              <input className="form-input" value={notes} onChange={e => setNotes(e.target.value)} />
            </div>
          </div>
          <input
            ref={fileInputRef}
            type="file"
            multiple
            accept=".wav,.mp3,.ogg,.m4a"
            onChange={e => handleFileSelect(e.target.files)}
            style={{ marginBottom: 12 }}
          />
          <p className="td-muted" style={{ marginBottom: 12, fontSize: '.8rem' }}>
            {files.length}/{MAX_FILES} arquivo(s) selecionado(s) — wav/mp3/ogg/m4a, até 50MB cada.
          </p>
          {uploadError && <div className="alert alert-error" style={{ marginBottom: 12 }}>{uploadError}</div>}
          <button className="btn btn-primary btn-sm" onClick={submitUpload} disabled={uploading || files.length === 0}>
            {uploading ? 'Enviando…' : 'Enviar lote'}
          </button>
        </div>
      )}

      {loading ? (
        <div className="loading-state"><div className="spinner" />Carregando…</div>
      ) : (
        <div className="table-wrapper">
          <table>
            <thead>
              <tr>
                <th>Data</th>
                {isAdmin && <th>Enviado por</th>}
                <th>Arquivos</th>
                <th>Observação</th>
              </tr>
            </thead>
            <tbody>
              {batches.length === 0 ? (
                <tr><td colSpan={isAdmin ? 4 : 3} className="table-empty">Nenhum lote enviado ainda</td></tr>
              ) : batches.map(b => (
                <tr key={b.id} onClick={() => loadBatchDetail(b.id)} style={{ cursor: 'pointer' }}>
                  <td className="td-muted">{new Date(b.createdAt).toLocaleString('pt-BR')}</td>
                  {isAdmin && <td className="td-muted">{b.uploadedBy}</td>}
                  <td>{b.fileCount}</td>
                  <td className="td-muted">{b.notes || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
