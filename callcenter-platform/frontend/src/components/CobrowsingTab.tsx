import { useEffect, useState } from 'react';
import { Play, Trash2, Settings } from 'lucide-react';
import api from '../api/client';
import { ConfirmModal } from './ConfirmModal';
import { CobrowsingPlayer } from './CobrowsingPlayer';
import { CobrowseRetentionConfigModal } from './CobrowseRetentionConfigModal';
import type { CcCobrowseSession, Page } from '../api/types';

const CONSENT_LABEL: Record<CcCobrowseSession['consentStatus'], string> = {
  pending: 'Pendente',
  granted: 'Concedido',
  denied: 'Recusado',
  revoked: 'Revogado',
};

const CONSENT_BADGE: Record<CcCobrowseSession['consentStatus'], string> = {
  pending: 'badge-gray',
  granted: 'badge-success',
  denied: 'badge-danger',
  revoked: 'badge-danger',
};

function formatBytes(bytes: number): string {
  if (!bytes) return '0 KB';
  return `${(bytes / 1024).toFixed(1)} KB`;
}

/**
 * CobrowsingTab — sub-view "Co-browsing" dentro da aba Gravações (Fase 17c). RBAC própria
 * (`callcenter.cobrowsing`, não reusa `callcenter.gravacoes`) — o gating de leitura/escrita já
 * é decidido pelo componente pai (`GravacoesTab`), que só renderiza esta sub-view quando o
 * usuário tem `canRead`.
 */
export function CobrowsingTab({ canWrite }: { canWrite: boolean }) {
  const [sessions, setSessions] = useState<CcCobrowseSession[]>([]);
  const [totalPages, setTotalPages] = useState(0);
  const [page, setPage] = useState(0);
  const [playingId, setPlayingId] = useState<number | null>(null);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [showRetentionConfig, setShowRetentionConfig] = useState(false);

  const load = () => {
    api.get<Page<CcCobrowseSession>>('/callcenter/cobrowsing', { params: { page, size: 20 } })
      .then(({ data }) => { setSessions(data.content); setTotalPages(data.totalPages); })
      .catch(() => { setSessions([]); setTotalPages(0); });
  };

  useEffect(load, [page]);

  const handleDelete = () => {
    if (deletingId == null) return;
    api.delete(`/callcenter/cobrowsing/${deletingId}`)
      .then(() => { setDeletingId(null); load(); })
      .catch(() => { setDeletingId(null); alert('Erro ao eliminar a sessão de co-browsing.'); });
  };

  return (
    <>
      <div className="flex items-center justify-end" style={{ marginBottom: 8 }}>
        <button className="btn btn-ghost btn-sm" onClick={() => setShowRetentionConfig(true)}>
          <Settings size={14} /> Retenção
        </button>
      </div>

      <div className="table-wrapper">
        <table>
          <thead>
            <tr>
              <th>Sessão de chat</th><th>Início</th><th>Consentimento</th>
              <th>Eventos</th><th>Tamanho</th><th>Truncado</th><th></th>
            </tr>
          </thead>
          <tbody>
            {sessions.map(s => (
              <tr key={s.id}>
                <td>#{s.chatSessionId}</td>
                <td>{new Date(s.startedAt).toLocaleString('pt-BR')}</td>
                <td><span className={`badge ${CONSENT_BADGE[s.consentStatus]}`}>{CONSENT_LABEL[s.consentStatus]}</span></td>
                <td>{s.eventCount}</td>
                <td>{formatBytes(s.sizeBytes)}</td>
                <td><span className={`badge ${s.truncated ? 'badge-warning' : 'badge-gray'}`}>{s.truncated ? 'Sim' : 'Não'}</span></td>
                <td style={{ display: 'flex', gap: 4 }}>
                  {s.consentStatus === 'granted' && s.filePath && s.purgedAt == null && (
                    <button className="btn btn-ghost btn-sm" title="Reproduzir" onClick={() => setPlayingId(s.id)}>
                      <Play size={14} />
                    </button>
                  )}
                  {canWrite && s.purgedAt == null && (
                    <button className="btn btn-ghost btn-sm" title="Eliminar" onClick={() => setDeletingId(s.id)}>
                      <Trash2 size={14} />
                    </button>
                  )}
                  {s.purgedAt != null && <span style={{ color: 'var(--text-muted)', fontSize: '.8rem' }}>Eliminado</span>}
                </td>
              </tr>
            ))}
            {sessions.length === 0 && <tr><td colSpan={7} className="table-empty">Nenhuma sessão de co-browsing encontrada.</td></tr>}
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

      {showRetentionConfig && (
        <CobrowseRetentionConfigModal canWrite={canWrite} onClose={() => setShowRetentionConfig(false)} />
      )}
      {playingId != null && <CobrowsingPlayer sessionId={playingId} onClose={() => setPlayingId(null)} />}
      {deletingId != null && (
        <ConfirmModal
          message="Eliminar definitivamente esta sessão de co-browsing (arquivo capturado)? Esta ação não pode ser desfeita."
          onConfirm={handleDelete}
          onCancel={() => setDeletingId(null)}
        />
      )}
    </>
  );
}
