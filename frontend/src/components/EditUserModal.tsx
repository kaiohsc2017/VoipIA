import type { Dispatch, SetStateAction } from 'react';
import type { AccessGroup } from '../api/types';
import type { AppUser, BusinessUnitOption, EditForm } from './userModalTypes';
import { MAX_ACCESS_DAYS, maxAccessDate, toggleBu } from './userModalTypes';

interface EditUserModalProps {
  user: AppUser;
  form: EditForm;
  setForm: Dispatch<SetStateAction<EditForm>>;
  businessUnits: BusinessUnitOption[];
  accessGroups: AccessGroup[];
  saving: boolean;
  onClose: () => void;
  onSave: () => void;
}

export function EditUserModal({ user, form, setForm, businessUnits, accessGroups, saving, onClose, onSave }: EditUserModalProps) {
  return (
    <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal modal-sm">
        <div className="modal-header">
          <h2>✏️ Editar: {user.username}</h2>
          <button className="btn-close" onClick={onClose}>×</button>
        </div>
        <div className="modal-body">
          <div style={{ marginBottom: 14, padding: '10px 14px', background: 'rgba(0,122,255,0.08)', borderRadius: 8, fontSize: '0.8rem', color: '#4da8ff' }}>
            📞 Ramal fixo: <strong>{user.extension}</strong> — não pode ser alterado
          </div>
          <div className="form-group">
            <label className="form-label">Nome de exibição *</label>
            <input type="text" className="form-input" autoFocus
              value={form.displayName}
              onChange={e => setForm(f => ({ ...f, displayName: e.target.value }))} />
          </div>
          <div className="form-group">
            <label className="form-label">Nova senha (deixe em branco para manter)</label>
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
              Se selecionado, este grupo (RBAC granular) prevalece sobre o Perfil acima. Grupo
              atual: {user.accessGroupName ?? '—'}.
            </div>
          </div>
          <div className="form-group">
            <label className="form-label">Status</label>
            <select className="form-select" value={form.isActive ? 'true' : 'false'}
              onChange={e => setForm(f => ({ ...f, isActive: e.target.value === 'true' }))}>
              <option value="true">Ativo</option>
              <option value="false">Inativo</option>
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
        </div>
        <div className="modal-footer">
          <button className="btn btn-ghost" onClick={onClose}>Cancelar</button>
          <button className="btn btn-primary" onClick={onSave} disabled={saving}>
            {saving ? 'Salvando…' : 'Salvar Alterações'}
          </button>
        </div>
      </div>
    </div>
  );
}
