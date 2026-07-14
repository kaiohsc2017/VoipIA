import type { Dispatch, SetStateAction } from 'react';
import type { BusinessUnitOption, CreateForm } from './userModalTypes';
import { MAX_ACCESS_DAYS, maxAccessDate, toggleBu } from './userModalTypes';

interface CreateUserModalProps {
  form: CreateForm;
  setForm: Dispatch<SetStateAction<CreateForm>>;
  businessUnits: BusinessUnitOption[];
  saving: boolean;
  onClose: () => void;
  onSave: () => void;
}

export function CreateUserModal({ form, setForm, businessUnits, saving, onClose, onSave }: CreateUserModalProps) {
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
