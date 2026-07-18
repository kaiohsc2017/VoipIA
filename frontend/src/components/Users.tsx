import { useEffect, useState } from 'react';
import api, { getErrorMessage } from '../api/client';
import type { AppUser, BusinessUnitOption, CreateForm, EditForm, TotpSetup } from './userModalTypes';
import { EMPTY_CREATE, maxAccessDate } from './userModalTypes';
import { CreateUserModal } from './CreateUserModal';
import { EditUserModal } from './EditUserModal';
import { TotpModal } from './TotpModal';

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
    } catch (e) {
      alert(getErrorMessage(e, 'Erro ao criar usuário.'));
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
    } catch (e) {
      alert(getErrorMessage(e, 'Erro ao salvar usuário.'));
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
    } catch (e) {
      alert(getErrorMessage(e, 'Erro ao resetar MFA.'));
    }
  };

  // ---- Desativar ----
  const handleDeactivate = async (u: AppUser) => {
    if (!confirm(`Desativar o usuário "${u.username}"? O ramal ${u.extension} será preservado.`)) return;
    try {
      await api.delete(`/users/${u.id}`);
      load();
    } catch (err) {
      alert(getErrorMessage(err, 'Erro ao desativar usuário.'));
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
        <h1>👥 Usuários</h1>
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
        <CreateUserModal
          form={createForm} setForm={setCreateForm} businessUnits={businessUnits}
          saving={saving} onClose={() => setShowCreate(false)} onSave={handleCreate}
        />
      )}

      {/* Modal — Editar Usuário */}
      {editUser && (
        <EditUserModal
          user={editUser} form={editForm} setForm={setEditForm} businessUnits={businessUnits}
          saving={saving} onClose={() => setEditUser(null)} onSave={handleEdit}
        />
      )}

      {/* Modal — Gerenciar 2FA (TOTP) */}
      {totpUser && (
        <TotpModal
          user={totpUser} status={totpStatus} step={totpStep} setup={totpSetup}
          code={totpCode} setCode={setTotpCode} msg={totpMsg} loading={totpLoading}
          onClose={closeTotpModal}
          onStartSetup={startTotpSetup}
          onGoToDisableStep={() => { setTotpStep('disable'); setTotpCode(''); setTotpMsg(''); }}
          onBackToStatus={() => { setTotpStep('status'); setTotpMsg(''); }}
          onConfirmEnable={confirmTotpEnable}
          onConfirmDisable={confirmTotpDisable}
        />
      )}
    </>
  );
}
