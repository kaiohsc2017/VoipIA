import { useEffect, useState } from 'react';
import api, { getErrorMessage } from '../../api/client';
import type { FlowVersionView, FlowView } from '../../api/types';

interface VersionHistoryModalProps {
  flow: FlowView;
  canWrite: boolean;
  onClose: () => void;
}

const STATUS_LABEL: Record<string, string> = { DRAFT: 'Rascunho', PUBLISHED: 'Publicada', ARCHIVED: 'Arquivada' };
const STATUS_BADGE: Record<string, string> = { DRAFT: 'badge-gray', PUBLISHED: 'badge-success', ARCHIVED: 'badge-info' };

/** VersionHistoryModal — histórico de versões do fluxo, com rollback para uma versão arquivada. */
export function VersionHistoryModal({ flow, canWrite, onClose }: VersionHistoryModalProps) {
  const [versions, setVersions] = useState<FlowVersionView[]>([]);
  const [msg, setMsg] = useState('');

  const flash = (m: string) => { setMsg(m); setTimeout(() => setMsg(''), 4000); };

  const load = () => {
    api.get<FlowVersionView[]>(`/callcenter/fluxos/${flow.id}/versions`)
      .then(({ data }) => setVersions(data.sort((a, b) => b.versionNumber - a.versionNumber)))
      .catch(() => setVersions([]));
  };
  useEffect(load, [flow.id]);

  const rollback = (versionId: number) => {
    api.post(`/callcenter/fluxos/${flow.id}/rollback/${versionId}`)
      .then(load)
      .catch(err => flash(getErrorMessage(err, 'Erro ao fazer rollback — a versão precisa estar arquivada.')));
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal modal-lg" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <h2>Histórico de versões — {flow.name}</h2>
          <button className="btn-close" onClick={onClose}>×</button>
        </div>
        <div className="modal-body">
          {msg && <div className="flash-message" style={{ background: 'var(--bg-danger-soft)', color: 'var(--clr-danger)' }}>{msg}</div>}
          <div className="table-wrapper">
            <table>
              <thead>
                <tr><th>Versão</th><th>Status</th><th>Publicada em</th><th>Por</th>{canWrite && <th></th>}</tr>
              </thead>
              <tbody>
                {versions.map(v => (
                  <tr key={v.id}>
                    <td>v{v.versionNumber}</td>
                    <td><span className={`badge ${STATUS_BADGE[v.status] ?? 'badge-gray'}`}>{STATUS_LABEL[v.status] ?? v.status}</span></td>
                    <td>{v.publishedAt ? new Date(v.publishedAt).toLocaleString('pt-BR') : '—'}</td>
                    <td>{v.publishedBy ?? '—'}</td>
                    {canWrite && (
                      <td>
                        {v.status === 'ARCHIVED' && (
                          <button className="btn btn-ghost btn-sm" onClick={() => rollback(v.id)}>Reverter para esta versão</button>
                        )}
                      </td>
                    )}
                  </tr>
                ))}
                {versions.length === 0 && (
                  <tr><td colSpan={canWrite ? 5 : 4} className="table-empty">Nenhuma versão ainda.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
        <div className="modal-footer">
          <button className="btn btn-ghost" onClick={onClose}>Fechar</button>
        </div>
      </div>
    </div>
  );
}
