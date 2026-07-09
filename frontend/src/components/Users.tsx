import { useEffect, useState } from 'react';
import api from '../api/client';

interface AppUser {
  id: number;
  username: string;
  displayName: string;
  extension: number;
  isActive: boolean;
  role: string;
  createdAt: string;
  businessUnitIds: number[];
  accessExpiresAt: string | null;
  accessIndeterminate: boolean;
  totpEnabled: boolean;
}

interface BusinessUnitOption {
  id: number;
  name: string;
}

interface CreateForm {
  username: string;
  password: string;
  displayName: string;
  role: string;
  businessUnitIds: number[];
  accessExpiresAt: string;
  accessIndeterminate: boolean;
}

interface EditForm {
  displayName: string;
  password: string;
  isActive: boolean;
  role: string;
  businessUnitIds: number[];
  accessExpiresAt: string;
  accessIndeterminate: boolean;
}

// Regra de negócio: acesso com prazo determinado nunca passa de 60 dias (espelha o backend).
const MAX_ACCESS_DAYS = 60;
const maxAccessDate = () => {
  const d = new Date();
  d.setDate(d.getDate() + MAX_ACCESS_DAYS);
  return d.toISOString().slice(0, 10);
};

// ─── 2FA state por usuário ────────────────────────────────────────────────────

interface TotpSetup {
  secret: string;
  qrCodeUrl: string;
  issuer: string;
  account: string;
}

const EMPTY_CREATE: CreateForm = {
  username: '', password: '', displayName: '', role: 'USER',
  businessUnitIds: [], accessExpiresAt: maxAccessDate(), accessIndeterminate: false,
};

export default function Users() {
  const [users, setUsers]         = useState<AppUser[]>([]);
  const [businessUnits, setBusinessUnits] = useState<BusinessUnitOption[]>([]);
  const [loading, setLoading]     = useState(true);
  const [showCreate, setShowCreate] = useState(false);
  const [editUser, setEditUser]   = useState<AppUser | null>(null);
  const [createForm, setCreateForm] = useState<CreateForm>(EMPTY_CREATE);
  const [editForm, setEditForm]   = useState<EditForm>({
    displayName: '', password: '', isActive: true, role: 'USER',
    businessUnitIds: [], accessExpiresAt: maxAccessDate(), accessIndeterminate: false,
  });
  const [saving, setSaving]       = useState(false);
  const [revealedPass, setRevealedPass] = useState<number | null>(null);
  const [revealedPasswords, setRevealedPasswords] = useState<Record<number, string>>({});

  // Achado de segurança: extensionPassword não vem mais na listagem —
  // busca sob demanda no endpoint dedicado ao clicar "revelar".
  const handleToggleReveal = (userId: number) => {
    if (revealedPass === userId) {
      setRevealedPass(null);
      return;
    }
    setRevealedPass(userId);
    if (!(userId in revealedPasswords)) {
      api.get<{ extensionPassword: string }>(`/users/${userId}/extension-password`)
        .then(r => setRevealedPasswords(prev => ({ ...prev, [userId]: r.data?.extensionPassword ?? '' })))
        .catch(() => setRevealedPasswords(prev => ({ ...prev, [userId]: '(erro ao buscar)' })));
    }
  };

  // 2FA modal state
  const [totpUser, setTotpUser]   = useState<AppUser | null>(null);
  const [totpSetup, setTotpSetup] = useState<TotpSetup | null>(null);
  const [totpStatus, setTotpStatus] = useState<boolean>(false);
  const [totpCode, setTotpCode]   = useState('');
  const [totpStep, setTotpStep]   = useState<'status' | 'setup' | 'confirm' | 'disable'>('status');
  const [totpMsg, setTotpMsg]     = useState('');
  const [totpLoading, setTotpLoading] = useState(false);

  const load = () => {
    setLoading(true);
    api.get<AppUser[]>('/users')
      .then(r => setUsers(r.data ?? []))
      .catch(() => setUsers([]))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    api.get<BusinessUnitOption[]>('/business-units')
      .then(r => setBusinessUnits(r.data ?? []))
      .catch(() => setBusinessUnits([]));
  }, []);

  const toggleBu = (ids: number[], id: number): number[] =>
    ids.includes(id) ? ids.filter(x => x !== id) : [...ids, id];

  // ---- Criar usuário ----
  const handleCreate = async () => {
    if (!createForm.username.trim()) { alert('Informe o username.'); return; }
    if (!createForm.password.trim() || createForm.password.length < 6) { alert('Senha deve ter ao menos 6 caracteres.'); return; }
    if (!createForm.displayName.trim()) { alert('Informe o nome de exibição.'); return; }
    if (createForm.businessUnitIds.length === 0) { alert('Selecione ao menos uma Unidade de Negócio (BU).'); return; }
    if (!createForm.accessIndeterminate && !createForm.accessExpiresAt) { alert('Informe a data de expiração do acesso ou marque acesso indeterminado.'); return; }
    setSaving(true);
    try {
      await api.post('/users', {
        ...createForm,
        accessExpiresAt: createForm.accessIndeterminate ? null : createForm.accessExpiresAt,
      });
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
    setEditForm({
      displayName: u.displayName, password: '', isActive: u.isActive, role: u.role,
      businessUnitIds: u.businessUnitIds ?? [],
      accessExpiresAt: u.accessExpiresAt ?? maxAccessDate(),
      accessIndeterminate: u.accessIndeterminate,
    });
  };

  const handleEdit = async () => {
    if (!editUser) return;
    if (!editForm.displayName.trim()) { alert('Informe o nome de exibição.'); return; }
    if (editForm.password && editForm.password.length < 6) { alert('Nova senha deve ter ao menos 6 caracteres.'); return; }
    if (editForm.businessUnitIds.length === 0) { alert('Selecione ao menos uma Unidade de Negócio (BU).'); return; }
    if (!editForm.accessIndeterminate && !editForm.accessExpiresAt) { alert('Informe a data de expiração do acesso ou marque acesso indeterminado.'); return; }
    setSaving(true);
    try {
      await api.put(`/users/${editUser.id}`, {
        displayName: editForm.displayName,
        password: editForm.password || undefined,
        isActive: editForm.isActive,
        role: editForm.role,
        businessUnitIds: editForm.businessUnitIds,
        accessExpiresAt: editForm.accessIndeterminate ? null : editForm.accessExpiresAt,
        accessIndeterminate: editForm.accessIndeterminate,
      });
      setEditUser(null);
      load();
    } catch (e: unknown) {
      const err = e as { response?: { data?: { message?: string } } };
      alert(err?.response?.data?.message ?? 'Erro ao salvar usuário.');
    } finally {
      setSaving(false);
    }
  };

  // ---- Reset de MFA pelo admin ----
  const handleResetTotp = async (u: AppUser) => {
    if (!confirm(`Resetar o MFA de "${u.username}"? O usuário precisará configurar o 2FA novamente no próximo login, se quiser continuar usando.`)) return;
    try {
      await api.post(`/users/${u.id}/totp/reset`);
      load();
    } catch (e: unknown) {
      const err = e as { response?: { data?: { message?: string } } };
      alert(err?.response?.data?.message ?? 'Erro ao resetar MFA.');
    }
  };

  // ---- Desativar ----
  const handleDeactivate = async (u: AppUser) => {
    if (!confirm(`Desativar o usuário "${u.username}"? O ramal ${u.extension} será preservado.`)) return;
    try {
      await api.delete(`/users/${u.id}`);
      load();
    } catch (err: any) {
      alert(err?.response?.data?.message ?? 'Erro ao desativar usuário.');
    }
  };

  // ── 2FA (TOTP) ─────────────────────────────────────────────────────────────

  const openTotp = async (u: AppUser) => {
    setTotpUser(u);
    setTotpMsg('');
    setTotpCode('');
    setTotpLoading(true);
    setTotpStep('status');
    try {
      const { data } = await api.get<{ enabled: boolean }>(`/totp/status?username=${u.username}`);
      setTotpStatus(data.enabled);
    } catch {
      setTotpStatus(false);
    } finally {
      setTotpLoading(false);
    }
  };

  const startTotpSetup = async () => {
    if (!totpUser) return;
    setTotpLoading(true);
    setTotpMsg('');
    try {
      const { data } = await api.post<TotpSetup>('/totp/setup', { username: totpUser.username });
      setTotpSetup(data);
      setTotpStep('setup');
    } catch {
      setTotpMsg('Erro ao iniciar configuração do 2FA.');
    } finally {
      setTotpLoading(false);
    }
  };

  const confirmTotpEnable = async () => {
    if (!totpUser || !totpSetup) return;
    const code = totpCode.replace(/\s/g, '');
    if (code.length !== 6) { setTotpMsg('Digite os 6 dígitos do código.'); return; }
    setTotpLoading(true);
    setTotpMsg('');
    try {
      await api.post('/totp/enable', { username: totpUser.username, code });
      setTotpStatus(true);
      setTotpStep('status');
      setTotpMsg('✅ 2FA ativado com sucesso!');
      setTotpCode('');
    } catch {
      setTotpMsg('Código inválido. Verifique o app autenticador e tente novamente.');
    } finally {
      setTotpLoading(false);
    }
  };

  const confirmTotpDisable = async () => {
    if (!totpUser) return;
    const code = totpCode.replace(/\s/g, '');
    if (code.length !== 6) { setTotpMsg('Digite os 6 dígitos para confirmar a desativação.'); return; }
    setTotpLoading(true);
    setTotpMsg('');
    try {
      await api.post('/totp/disable', { username: totpUser.username, code });
      setTotpStatus(false);
      setTotpStep('status');
      setTotpMsg('2FA desativado.');
      setTotpCode('');
    } catch {
      setTotpMsg('Código inválido. Não foi possível desativar o 2FA.');
    } finally {
      setTotpLoading(false);
    }
  };

  const closeTotpModal = () => {
    setTotpUser(null);
    setTotpSetup(null);
    setTotpCode('');
    setTotpMsg('');
    setTotpStep('status');
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
            <div style={{ fontSize: '1.5rem', fontWeight: 700, color: '#34c759' }}>{activeCount}</div>
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
                  <th>BU</th>
                  <th style={{ width: 110 }}>Acesso até</th>
                  <th style={{ width: 90 }}>2FA</th>
                  <th style={{ width: 90 }}>Status</th>
                  <th style={{ width: 200 }}>Ações</th>
                </tr>
              </thead>
              <tbody>
                {users.length === 0 ? (
                  <tr>
                    <td colSpan={11} className="table-empty">
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
                      <span className="chip" style={{ background: 'rgba(0,122,255,0.15)', color: '#4da8ff', fontFamily: 'monospace' }}>
                        📞 {u.extension}
                      </span>
                    </td>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                        <span className="mono" style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>
                          {revealedPass === u.id ? (revealedPasswords[u.id] ?? '...') : '••••••••••••'}
                        </span>
                        <button
                          className="btn btn-ghost btn-sm btn-icon"
                          title={revealedPass === u.id ? 'Ocultar' : 'Revelar senha'}
                          onClick={() => handleToggleReveal(u.id)}
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
                    <td style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
                      {(u.businessUnitIds ?? []).length === 0
                        ? '—'
                        : (u.businessUnitIds ?? [])
                            .map(id => businessUnits.find(b => b.id === id)?.name ?? `#${id}`)
                            .join(', ')}
                    </td>
                    <td style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
                      {u.accessIndeterminate ? 'Indeterminado' : (u.accessExpiresAt ?? '—')}
                    </td>
                    <td>
                      <span
                        className={`badge ${u.totpEnabled ? 'badge-success' : 'badge-gray'}`}
                        style={{ cursor: 'pointer', fontSize: '0.68rem' }}
                        onClick={() => openTotp(u)}
                        title="Gerenciar 2FA"
                      >
                        {u.totpEnabled ? '🛡️ Ativo' : '🔓 2FA'}
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
                        <button
                          className="btn btn-ghost btn-sm btn-icon"
                          onClick={() => openTotp(u)}
                          title="Configurar 2FA"
                          style={{ color: '#4da8ff' }}
                        >🔐</button>
                        {u.totpEnabled && (
                          <button
                            className="btn btn-ghost btn-sm btn-icon"
                            onClick={() => handleResetTotp(u)}
                            title="Resetar MFA (admin)"
                            style={{ color: '#fbbf24' }}
                          >♻️</button>
                        )}
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
              <div className="form-group">
                <label className="form-label">Unidade(s) de Negócio (BU) *</label>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                  {businessUnits.map(bu => (
                    <span key={bu.id} className="chip"
                      style={{
                        cursor: 'pointer',
                        background: createForm.businessUnitIds.includes(bu.id) ? 'rgba(0,122,255,0.25)' : 'rgba(255,255,255,0.06)',
                        color: createForm.businessUnitIds.includes(bu.id) ? '#4da8ff' : 'var(--text-muted)',
                      }}
                      onClick={() => setCreateForm(f => ({ ...f, businessUnitIds: toggleBu(f.businessUnitIds, bu.id) }))}
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
                  <input type="checkbox" checked={createForm.accessIndeterminate}
                    onChange={e => setCreateForm(f => ({ ...f, accessIndeterminate: e.target.checked }))} />
                  Acesso por tempo indeterminado
                </label>
                <input type="date" className="form-input"
                  disabled={createForm.accessIndeterminate}
                  min={new Date().toISOString().slice(0, 10)}
                  max={maxAccessDate()}
                  value={createForm.accessExpiresAt}
                  onChange={e => setCreateForm(f => ({ ...f, accessExpiresAt: e.target.value }))} />
                <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: 4 }}>
                  Prazo máximo: {MAX_ACCESS_DAYS} dias a partir de hoje.
                </div>
              </div>
              <div style={{ marginTop: 10, padding: '10px 14px', background: 'rgba(0,122,255,0.08)', borderRadius: 8, fontSize: '0.8rem', color: '#4da8ff' }}>
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
              <div style={{ marginBottom: 14, padding: '10px 14px', background: 'rgba(0,122,255,0.08)', borderRadius: 8, fontSize: '0.8rem', color: '#4da8ff' }}>
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
              <div className="form-group">
                <label className="form-label">Unidade(s) de Negócio (BU) *</label>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                  {businessUnits.map(bu => (
                    <span key={bu.id} className="chip"
                      style={{
                        cursor: 'pointer',
                        background: editForm.businessUnitIds.includes(bu.id) ? 'rgba(0,122,255,0.25)' : 'rgba(255,255,255,0.06)',
                        color: editForm.businessUnitIds.includes(bu.id) ? '#4da8ff' : 'var(--text-muted)',
                      }}
                      onClick={() => setEditForm(f => ({ ...f, businessUnitIds: toggleBu(f.businessUnitIds, bu.id) }))}
                    >{bu.name}</span>
                  ))}
                  {businessUnits.length === 0 && <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Nenhuma BU cadastrada.</span>}
                </div>
              </div>
              <div className="form-group">
                <label className="form-label">Acesso ao sistema</label>
                <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: '0.85rem', marginBottom: 8 }}>
                  <input type="checkbox" checked={editForm.accessIndeterminate}
                    onChange={e => setEditForm(f => ({ ...f, accessIndeterminate: e.target.checked }))} />
                  Acesso por tempo indeterminado
                </label>
                <input type="date" className="form-input"
                  disabled={editForm.accessIndeterminate}
                  min={new Date().toISOString().slice(0, 10)}
                  max={maxAccessDate()}
                  value={editForm.accessExpiresAt}
                  onChange={e => setEditForm(f => ({ ...f, accessExpiresAt: e.target.value }))} />
                <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: 4 }}>
                  Prazo máximo: {MAX_ACCESS_DAYS} dias a partir de hoje.
                </div>
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

      {/* Modal — Gerenciar 2FA (TOTP) */}
      {totpUser && (
        <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) closeTotpModal(); }}>
          <div className="modal modal-sm">
            <div className="modal-header">
              <h2>🔐 2FA — {totpUser.username}</h2>
              <button className="btn-close" onClick={closeTotpModal}>×</button>
            </div>
            <div className="modal-body">
              {totpLoading ? (
                <div className="loading-state"><div className="spinner" />Carregando…</div>
              ) : (
                <>
                  {/* ── STATUS ── */}
                  {totpStep === 'status' && (
                    <>
                      <div style={{
                        textAlign: 'center', padding: '20px',
                        background: totpStatus ? 'rgba(72,199,142,0.08)' : 'rgba(0,122,255,0.08)',
                        borderRadius: 10, marginBottom: 16,
                        border: `1px solid ${totpStatus ? 'rgba(72,199,142,0.3)' : 'rgba(0,122,255,0.3)'}`,
                      }}>
                        <div style={{ fontSize: '2.5rem', marginBottom: 8 }}>
                          {totpStatus ? '🛡️' : '🔓'}
                        </div>
                        <div style={{ fontWeight: 600, fontSize: '1rem', color: totpStatus ? '#34c759' : '#4da8ff' }}>
                          {totpStatus ? '2FA Ativado' : '2FA Desativado'}
                        </div>
                        <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginTop: 4 }}>
                          {totpStatus
                            ? 'Este usuário usa verificação em 2 etapas no login.'
                            : 'O login deste usuário usa apenas senha.'}
                        </div>
                      </div>

                      {totpMsg && (
                        <div style={{ padding: '8px 12px', borderRadius: 8, marginBottom: 12, fontSize: '0.85rem',
                          background: totpMsg.startsWith('✅') ? 'rgba(72,199,142,0.1)' : 'rgba(255,107,107,0.1)',
                          color: totpMsg.startsWith('✅') ? '#34c759' : '#ff6b6b',
                          border: `1px solid ${totpMsg.startsWith('✅') ? 'rgba(72,199,142,0.3)' : 'rgba(255,107,107,0.3)'}`,
                        }}>
                          {totpMsg}
                        </div>
                      )}

                      {!totpStatus ? (
                        <button className="btn btn-primary" style={{ width: '100%' }} onClick={startTotpSetup}>
                          🔐 Configurar 2FA (ativar)
                        </button>
                      ) : (
                        <button
                          className="btn btn-ghost"
                          style={{ width: '100%', borderColor: 'rgba(255,107,107,0.4)', color: '#ff6b6b' }}
                          onClick={() => { setTotpStep('disable'); setTotpCode(''); setTotpMsg(''); }}
                        >
                          🚫 Desativar 2FA
                        </button>
                      )}
                    </>
                  )}

                  {/* ── SETUP: exibe QR Code ── */}
                  {totpStep === 'setup' && totpSetup && (
                    <>
                      <div style={{ fontSize: '0.85rem', color: 'var(--text-muted)', marginBottom: 12, lineHeight: 1.6 }}>
                        <strong>1.</strong> Abra o <strong>Google Authenticator</strong> ou outro app TOTP.<br />
                        <strong>2.</strong> Escaneie o QR Code abaixo.<br />
                        <strong>3.</strong> Insira o código de 6 dígitos para confirmar.
                      </div>

                      <div style={{ textAlign: 'center', marginBottom: 16 }}>
                        <img
                          src={totpSetup.qrCodeUrl}
                          alt="QR Code 2FA"
                          style={{ width: 200, height: 200, borderRadius: 8, background: '#fff', padding: 8 }}
                        />
                        <div style={{ marginTop: 8, fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                          Ou insira manualmente: <br />
                          <code style={{ background: 'rgba(255,255,255,0.08)', padding: '2px 8px', borderRadius: 4, fontSize: '0.8rem', letterSpacing: '0.15em' }}>
                            {totpSetup.secret}
                          </code>
                        </div>
                      </div>

                      <div className="form-group">
                        <label className="form-label">Código do app autenticador</label>
                        <input
                          type="text"
                          inputMode="numeric"
                          pattern="[0-9 ]*"
                          maxLength={7}
                          className="form-input"
                          placeholder="000 000"
                          autoFocus
                          autoComplete="one-time-code"
                          style={{ letterSpacing: '0.3em', fontSize: '1.3rem', textAlign: 'center' }}
                          value={totpCode}
                          onChange={e => setTotpCode(e.target.value)}
                        />
                      </div>

                      {totpMsg && (
                        <div style={{ padding: '8px 12px', borderRadius: 8, marginBottom: 8, fontSize: '0.85rem',
                          background: 'rgba(255,107,107,0.1)', color: '#ff6b6b', border: '1px solid rgba(255,107,107,0.3)' }}>
                          {totpMsg}
                        </div>
                      )}
                    </>
                  )}

                  {/* ── DISABLE: confirmar com código ── */}
                  {totpStep === 'disable' && (
                    <>
                      <div style={{ padding: '12px 16px', borderRadius: 8, marginBottom: 14,
                        background: 'rgba(255,107,107,0.08)', border: '1px solid rgba(255,107,107,0.3)',
                        fontSize: '0.85rem', color: '#ff6b6b', lineHeight: 1.6 }}>
                        ⚠️ Para desativar o 2FA, insira o código atual do app autenticador para confirmar.
                      </div>

                      <div className="form-group">
                        <label className="form-label">Código do app autenticador</label>
                        <input
                          type="text"
                          inputMode="numeric"
                          pattern="[0-9 ]*"
                          maxLength={7}
                          className="form-input"
                          placeholder="000 000"
                          autoFocus
                          style={{ letterSpacing: '0.3em', fontSize: '1.3rem', textAlign: 'center' }}
                          value={totpCode}
                          onChange={e => setTotpCode(e.target.value)}
                        />
                      </div>

                      {totpMsg && (
                        <div style={{ padding: '8px 12px', borderRadius: 8, marginBottom: 8, fontSize: '0.85rem',
                          background: 'rgba(255,107,107,0.1)', color: '#ff6b6b', border: '1px solid rgba(255,107,107,0.3)' }}>
                          {totpMsg}
                        </div>
                      )}
                    </>
                  )}
                </>
              )}
            </div>

            <div className="modal-footer">
              {totpStep === 'status' && (
                <button className="btn btn-ghost" onClick={closeTotpModal}>Fechar</button>
              )}
              {totpStep === 'setup' && (
                <>
                  <button className="btn btn-ghost" onClick={() => { setTotpStep('status'); setTotpMsg(''); }}>← Voltar</button>
                  <button
                    className="btn btn-primary"
                    onClick={confirmTotpEnable}
                    disabled={totpLoading || totpCode.replace(/\s/g, '').length < 6}
                  >
                    {totpLoading ? 'Verificando…' : '✅ Ativar 2FA'}
                  </button>
                </>
              )}
              {totpStep === 'disable' && (
                <>
                  <button className="btn btn-ghost" onClick={() => { setTotpStep('status'); setTotpMsg(''); }}>← Voltar</button>
                  <button
                    className="btn btn-ghost"
                    style={{ borderColor: 'rgba(255,107,107,0.4)', color: '#ff6b6b' }}
                    onClick={confirmTotpDisable}
                    disabled={totpLoading || totpCode.replace(/\s/g, '').length < 6}
                  >
                    {totpLoading ? 'Desativando…' : '🚫 Confirmar Desativação'}
                  </button>
                </>
              )}
            </div>
          </div>
        </div>
      )}
    </>
  );
}
