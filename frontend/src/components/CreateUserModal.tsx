import type { Dispatch, SetStateAction } from 'react';
import type { AccessGroup } from '../api/types';
import type { BusinessUnitOption, CcQueueOption, CreateForm } from './userModalTypes';
import { MAX_ACCESS_DAYS, maxAccessDate, toggleBu } from './userModalTypes';

interface CreateUserModalProps {
  form: CreateForm;
  setForm: Dispatch<SetStateAction<CreateForm>>;
  businessUnits: BusinessUnitOption[];
  accessGroups: AccessGroup[];
  queues: CcQueueOption[];
  saving: boolean;
  onClose: () => void;
  onSave: () => void;
}

export function CreateUserModal({ form, setForm, businessUnits, accessGroups, queues, saving, onClose, onSave }: CreateUserModalProps) {
  const toggleQueue = (queueId: number) => {
    setForm(f => {
      const exists = f.queueMemberships.some(m => m.queueId === queueId);
      return {
        ...f,
        queueMemberships: exists
          ? f.queueMemberships.filter(m => m.queueId !== queueId)
          : [...f.queueMemberships, { queueId, priority: 0 }],
      };
    });
  };

  const updateQueuePriority = (queueId: number, priority: number) => {
    setForm(f => ({
      ...f,
      queueMemberships: f.queueMemberships.map(m => m.queueId === queueId ? { ...m, priority } : m),
    }));
  };
  return (
    <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal modal-sm">
        <div className="modal-header">
          <h2>👤 Novo Usuário</h2>
          <button className="btn-close" onClick={onClose}>×</button>
        </div>
        <div className="modal-body">
          <div className="form-group">
            <label className="form-label">Username *</label>
            <input type="text" className="form-input" autoFocus
              placeholder="ex: joao.silva"
              value={form.username}
              onChange={e => setForm(f => ({ ...f, username: e.target.value }))} />
          </div>
          <div className="form-group">
            <label className="form-label">Nome de exibição *</label>
            <input type="text" className="form-input"
              placeholder="ex: João Silva"
              value={form.displayName}
              onChange={e => setForm(f => ({ ...f, displayName: e.target.value }))} />
          </div>
          <div className="form-group">
            <label className="form-label">Senha (mín. 6 caracteres) *</label>
            <input type="password" className="form-input"
              placeholder="••••••••"
              value={form.password}
              onChange={e => setForm(f => ({ ...f, password: e.target.value }))} />
          </div>
          <div className="form-group">
            <label className="form-label">Perfil</label>
            <select className="form-select" value={form.role}
              onChange={e => setForm(f => ({ ...f, role: e.target.value }))}>
              <option value="USER">👤 Usuário</option>
              <option value="ADMIN">🛡 Administrador</option>
            </select>
          </div>
          <div className="form-group">
            <label className="form-label">Grupo de acesso customizado</label>
            <select className="form-select"
              value={form.accessGroupId ?? ''}
              onChange={e => setForm(f => ({ ...f, accessGroupId: e.target.value ? Number(e.target.value) : null }))}>
              <option value="">— usar Perfil acima (padrão) —</option>
              {accessGroups.map(g => (
                <option key={g.id} value={g.id}>{g.name}</option>
              ))}
            </select>
            <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: 4 }}>
              Se selecionado, este grupo (RBAC granular) prevalece sobre o Perfil acima.
            </div>
          </div>
          <div className="form-group">
            <label className="form-label">Unidade(s) de Negócio (BU) *</label>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
              {businessUnits.map(bu => (
                <span key={bu.id} className="chip"
                  style={{
                    cursor: 'pointer',
                    background: form.businessUnitIds.includes(bu.id) ? 'rgba(0,122,255,0.25)' : 'rgba(255,255,255,0.06)',
                    color: form.businessUnitIds.includes(bu.id) ? '#4da8ff' : 'var(--text-muted)',
                  }}
                  onClick={() => setForm(f => ({ ...f, businessUnitIds: toggleBu(f.businessUnitIds, bu.id) }))}
                >{bu.name}</span>
              ))}
              {businessUnits.length === 0 && <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Nenhuma BU cadastrada.</span>}
            </div>
            <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: 4 }}>
              O usuário só verá dados das BUs selecionadas.
            </div>
          </div>
          <div className="form-group">
            <label className="form-label">Acesso ao sistema</label>
            <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: '0.85rem', marginBottom: 8 }}>
              <input type="checkbox" checked={form.accessIndeterminate}
                onChange={e => setForm(f => ({ ...f, accessIndeterminate: e.target.checked }))} />
              Acesso por tempo indeterminado
            </label>
            <input type="date" className="form-input"
              disabled={form.accessIndeterminate}
              min={new Date().toISOString().slice(0, 10)}
              max={maxAccessDate()}
              value={form.accessExpiresAt}
              onChange={e => setForm(f => ({ ...f, accessExpiresAt: e.target.value }))} />
            <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: 4 }}>
              Prazo máximo: {MAX_ACCESS_DAYS} dias a partir de hoje.
            </div>
          </div>
          <div style={{ marginTop: 10, padding: '10px 14px', background: 'rgba(0,122,255,0.08)', borderRadius: 8, fontSize: '0.8rem', color: '#4da8ff' }}>
            📞 Um ramal SIP WebRTC será atribuído automaticamente ao novo usuário.
          </div>

          <div className="form-group" style={{ marginTop: 12 }}>
            <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: '0.85rem' }}>
              <input type="checkbox" checked={form.callCenterAgent}
                onChange={e => setForm(f => ({ ...f, callCenterAgent: e.target.checked }))} />
              Atendente do Call Center
            </label>
            <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: 4 }}>
              Provisiona um ramal (4000-4999) e vincula às filas selecionadas abaixo.
            </div>
          </div>

          {form.callCenterAgent && (
            <div className="form-group">
              <label className="form-label">Filas e prioridade (menor = atendido antes)</label>
              {queues.length === 0 && (
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>
                  Nenhuma fila disponível (ou sem permissão de leitura em Filas do Call Center).
                </div>
              )}
              {queues.map(q => {
                const membership = form.queueMemberships.find(m => m.queueId === q.id);
                return (
                  <div key={q.id} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
                    <label style={{ display: 'flex', alignItems: 'center', gap: 6, flex: 1, fontSize: '0.85rem' }}>
                      <input type="checkbox" checked={!!membership} onChange={() => toggleQueue(q.id)} />
                      {q.displayName} ({q.name})
                    </label>
                    {membership && (
                      <input type="number" min={0} className="form-input" style={{ width: 70 }}
                        value={membership.priority}
                        onChange={e => updateQueuePriority(q.id, Number(e.target.value) || 0)} />
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>
        <div className="modal-footer">
          <button className="btn btn-ghost" onClick={onClose}>Cancelar</button>
          <button className="btn btn-primary" onClick={onSave} disabled={saving}>
            {saving ? 'Criando…' : 'Criar Usuário'}
          </button>
        </div>
      </div>
    </div>
  );
}
