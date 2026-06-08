import { useEffect, useState } from 'react';
import api from '../api/client';

interface AppUser {
  id: number;
  username: string;
  displayName: string;
  extension: number;
  extensionPassword: string;
  isActive: boolean;
  role: string;
  createdAt: string;
}

interface CreateForm {
  username: string;
  password: string;
  displayName: string;
  role: string;
}

interface EditForm {
  displayName: string;
  password: string;
  isActive: boolean;
  role: string;
}

const EMPTY_CREATE: CreateForm = { username: '', password: '', displayName: '', role: 'USER' };

export default function Users() {
  const [users, setUsers] = useState<AppUser[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreate, setShowCreate] = useState(false);
  const [editUser, setEditUser] = useState<AppUser | null>(null);
  const [createForm, setCreateForm] = useState<CreateForm>(EMPTY_CREATE);
  const [editForm, setEditForm] = useState<EditForm>({ displayName: '', password: '', isActive: true, role: 'USER' });
  const [saving, setSaving] = useState(false);
  const [revealedPass, setRevealedPass] = useState<number | null>(null);

  const load = () => {
    setLoading(true);
    api.get<AppUser[]>('/users')
      .then(r => setUsers(r.data ?? []))
      .catch(() => setUsers([]))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  // ---- Criar usuário ----
  const handleCreate = async () => {
    if (!createForm.username.trim()) { alert('Informe o username.'); return; }
    if (!createForm.password.trim() || createForm.password.length < 6) { alert('Senha deve ter ao menos 6 caracteres.'); return; }
    if (!createForm.displayName.trim()) { alert('Informe o nome de exibição.'); return; }
    setSaving(true);
    try {
      await api.post('/users', createForm);
      setShowCreate(false);
      setCreateForm(EMPTY_CREATE);
      load();
    } catch (e: unknown) {
      const err = e as { response?: { data?: { message?: string } } };
      alert(err?.response?.data?.message ?? 'Erro ao criar usuário.');
    } finally {
      setSaving(false);
    }
  };

  // ---- Editar usuário ----
  const openEdit = (u: AppUser) => {
    setEditUser(u);
    setEditForm({ displayName: u.displayName, password: '', isActive: u.isActive, role: u.role });
  };

  const handleEdit = async () => {
    if (!editUser) return;
    if (!editForm.displayName.trim()) { alert('Informe o nome de exibição.'); return; }
    if (editForm.password && editForm.password.length < 6) { alert('Nova senha deve ter ao menos 6 caracteres.'); return; }
    setSaving(true);
    try {
      await api.put(`/users/${editUser.id}`, {
        displayName: editForm.displayName,
        password: editForm.password || undefined,
        isActive: editForm.isActive,
        role: editForm.role,
      });
      setEditUser(null);
      load();
    } finally {
      setSaving(false);
    }
  };

  // ---- Desativar ----
  const handleDeactivate = async (u: AppUser) => {
    if (!confirm(`Desativar o usuário "${u.username}"? O ramal ${u.extension} será preservado.`)) return;
    await api.delete(`/users/${u.id}`);
    load();
  };

  const activeCount = users.filter(u => u.isActive).length;

  return (
    <>
      <div className="page-header">
        <h1>👥 Usuários e Ramais</h1>
        <p>Gerencie os usuários do sistema. Cada usuário recebe um ramal SIP WebRTC exclusivo a partir de 9001.</p>
      </div>
      <div className="page-body">

        {/* Info card */}
        <div className="stat-card" style={{ padding: '14px 20px', marginBottom: 20, display: 'flex', gap: 32, flexWrap: 'wrap', alignItems: 'center' }}>
          <div>
            <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>Usuários ativos</div>
            <div style={{ fontSize: '1.5rem', fontWeight: 700, color: '#68d391' }}>{activeCount}</div>
          </div>
          <div>
            <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>Total cadastrado</div>
            <div style={{ fontSize: '1.5rem', fontWeight: 700, color: '#94a3b8' }}>{users.length}</div>
          </div>
          <div style={{ flex: 1 }} />
          <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)', lineHeight: 1.6 }}>
            🔐 Os ramais SIP são criados <strong>automaticamente</strong> (9001, 9002, …).<br />
            A senha do ramal segue o padrão: <code style={{ background: 'rgba(255,255,255,0.08)', padding: '1px 6px', borderRadius: 4 }}>webrtcXXXXpass</code>
          </div>
        </div>

        {/* Toolbar */}
        <div className="toolbar">
          <div className="toolbar-left">
            <span style={{ color: 'var(--text-muted)', fontSize: '0.855rem' }}>
              {activeCount} usuário{activeCount !== 1 ? 's' : ''} ativo{activeCount !== 1 ? 's' : ''}
            </span>
          </div>
          <div className="toolbar-right">
            <button className="btn btn-primary" onClick={() => { setCreateForm(EMPTY_CREATE); setShowCreate(true); }}>
              ＋ Novo Usuário
            </button>
          </div>
        </div>

        {/* Tabela */}
        {loading ? (
          <div className="loading-state"><div className="spinner" />Carregando usuários…</div>
        ) : (
          <div className="table-wrapper">
            <table>
              <thead>
                <tr>
                  <th style={{ width: 50 }}>#</th>
                  <th>Username</th>
                  <th>Nome</th>
                  <th style={{ width: 90 }}>Ramal</th>
                  <th>Senha do Ramal</th>
                  <th style={{ width: 80 }}>Perfil</th>
                  <th style={{ width: 90 }}>Status</th>
                  <th style={{ width: 150 }}>Ações</th>
                </tr>
              </thead>
              <tbody>
                {users.length === 0 ? (
                  <tr>
                    <td colSpan={8} className="table-empty">
                      Nenhum usuário cadastrado.<br />
                      <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>
                        Clique em "＋ Novo Usuário" para adicionar.
                      </span>
                    </td>
                  </tr>
                ) : users.map(u => (
                  <tr key={u.id} style={{ opacity: u.isActive ? 1 : 0.5 }}>
                    <td className="td-muted">{u.id}</td>
                    <td><span className="mono" style={{ fontWeight: 600 }}>{u.username}</span></td>
                    <td>{u.displayName}</td>
                    <td>
                      <span className="chip" style={{ background: 'rgba(124,58,237,0.15)', color: '#a78bfa', fontFamily: 'monospace' }}>
                        📞 {u.extension}
                      </span>
                    </td>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                        <span className="mono" style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>
                          {revealedPass === u.id ? u.extensionPassword : '••••••••••••'}
                        </span>
                        <button
                          className="btn btn-ghost btn-sm btn-icon"
                          title={revealedPass === u.id ? 'Ocultar' : 'Revelar senha'}
                          onClick={() => setRevealedPass(prev => prev === u.id ? null : u.id)}
                        >
                          {revealedPass === u.id ? '🙈' : '👁️'}
                        </button>
                      </div>
                    </td>
                    <td>
                      <span className={`badge ${u.role === 'ADMIN' ? 'badge-warning' : 'badge-info'}`}>
                        {u.role === 'ADMIN' ? '🛡 Admin' : '👤 User'}
                      </span>
                    </td>
                    <td>
                      <span className={`badge ${u.isActive ? 'badge-success' : 'badge-gray'}`}>
                        {u.isActive ? 'Ativo' : 'Inativo'}
                      </span>
                    </td>
                    <td>
                      <div className="flex gap-1">
                        <button
                          className="btn btn-ghost btn-sm btn-icon"
                          onClick={() => openEdit(u)}
                          title="Editar"
                        >✏️</button>
                        {u.isActive && (
                          <button
                            className="btn btn-danger btn-sm btn-icon"
                            onClick={() => handleDeactivate(u)}
                            title="Desativar"
                          >🚫</button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Nota sobre configuração Asterisk */}
        <div style={{
          marginTop: 24, padding: '14px 18px',
          background: 'rgba(251,191,36,0.08)', borderRadius: 10,
          border: '1px solid rgba(251,191,36,0.2)',
          fontSize: '0.82rem', color: '#fbbf24', lineHeight: 1.7,
        }}>
          <strong>⚠️ Configuração do Asterisk:</strong> Ao criar um usuário com ramal novo (ex: 9003), adicione o endpoint correspondente no
          <code style={{ margin: '0 4px', background: 'rgba(255,255,255,0.08)', padding: '1px 6px', borderRadius: 4 }}>pjsip.conf</code>
          seguindo o padrão dos ramais 9001/9002 já configurados.
          Os ramais pré-configurados (9001–9010) funcionam imediatamente.
        </div>
      </div>

      {/* Modal — Criar Usuário */}
      {showCreate && (
        <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) setShowCreate(false); }}>
          <div className="modal modal-sm">
            <div className="modal-header">
              <h2>👤 Novo Usuário</h2>
              <button className="btn-close" onClick={() => setShowCreate(false)}>×</button>
            </div>
            <div className="modal-body">
              <div className="form-group">
                <label className="form-label">Username *</label>
                <input type="text" className="form-input" autoFocus
                  placeholder="ex: joao.silva"
                  value={createForm.username}
                  onChange={e => setCreateForm(f => ({ ...f, username: e.target.value }))} />
              </div>
              <div className="form-group">
                <label className="form-label">Nome de exibição *</label>
                <input type="text" className="form-input"
                  placeholder="ex: João Silva"
                  value={createForm.displayName}
                  onChange={e => setCreateForm(f => ({ ...f, displayName: e.target.value }))} />
              </div>
              <div className="form-group">
                <label className="form-label">Senha (mín. 6 caracteres) *</label>
                <input type="password" className="form-input"
                  placeholder="••••••••"
                  value={createForm.password}
                  onChange={e => setCreateForm(f => ({ ...f, password: e.target.value }))} />
              </div>
              <div className="form-group">
                <label className="form-label">Perfil</label>
                <select className="form-select" value={createForm.role}
                  onChange={e => setCreateForm(f => ({ ...f, role: e.target.value }))}>
                  <option value="USER">👤 Usuário</option>
                  <option value="ADMIN">🛡 Administrador</option>
                </select>
              </div>
              <div style={{ marginTop: 10, padding: '10px 14px', background: 'rgba(124,58,237,0.08)', borderRadius: 8, fontSize: '0.8rem', color: '#a78bfa' }}>
                📞 Um ramal SIP WebRTC será atribuído automaticamente ao novo usuário.
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn btn-ghost" onClick={() => setShowCreate(false)}>Cancelar</button>
              <button className="btn btn-primary" onClick={handleCreate} disabled={saving}>
                {saving ? 'Criando…' : 'Criar Usuário'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Modal — Editar Usuário */}
      {editUser && (
        <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) setEditUser(null); }}>
          <div className="modal modal-sm">
            <div className="modal-header">
              <h2>✏️ Editar: {editUser.username}</h2>
              <button className="btn-close" onClick={() => setEditUser(null)}>×</button>
            </div>
            <div className="modal-body">
              <div style={{ marginBottom: 14, padding: '10px 14px', background: 'rgba(124,58,237,0.08)', borderRadius: 8, fontSize: '0.8rem', color: '#a78bfa' }}>
                📞 Ramal fixo: <strong>{editUser.extension}</strong> — não pode ser alterado
              </div>
              <div className="form-group">
                <label className="form-label">Nome de exibição *</label>
                <input type="text" className="form-input" autoFocus
                  value={editForm.displayName}
                  onChange={e => setEditForm(f => ({ ...f, displayName: e.target.value }))} />
              </div>
              <div className="form-group">
                <label className="form-label">Nova senha (deixe em branco para manter)</label>
                <input type="password" className="form-input"
                  placeholder="••••••••"
                  value={editForm.password}
                  onChange={e => setEditForm(f => ({ ...f, password: e.target.value }))} />
              </div>
              <div className="form-group">
                <label className="form-label">Perfil</label>
                <select className="form-select" value={editForm.role}
                  onChange={e => setEditForm(f => ({ ...f, role: e.target.value }))}>
                  <option value="USER">👤 Usuário</option>
                  <option value="ADMIN">🛡 Administrador</option>
                </select>
              </div>
              <div className="form-group">
                <label className="form-label">Status</label>
                <select className="form-select" value={editForm.isActive ? 'true' : 'false'}
                  onChange={e => setEditForm(f => ({ ...f, isActive: e.target.value === 'true' }))}>
                  <option value="true">Ativo</option>
                  <option value="false">Inativo</option>
                </select>
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn btn-ghost" onClick={() => setEditUser(null)}>Cancelar</button>
              <button className="btn btn-primary" onClick={handleEdit} disabled={saving}>
                {saving ? 'Salvando…' : 'Salvar Alterações'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
