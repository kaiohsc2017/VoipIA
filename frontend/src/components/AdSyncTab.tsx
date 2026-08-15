import { useEffect, useRef, useState } from 'react';
import api from '../api/client';

// ─── Tipos ───────────────────────────────────────────────────────────────────

interface SyncStatus {
  status: string;
  startedAt: string | null;
  finishedAt: string | null;
  usersSynced: number;
  errorMessage: string | null;
}

interface AccessGroupOption {
  id: number;
  name: string;
}

interface GroupMapping {
  id: number;
  adGroupName: string;
  accessGroupId: number;
  accessGroupName: string;
}

interface AdUserLookup {
  samAccountName: string;
  displayName: string | null;
  department: string | null;
  office: string | null;
  title: string | null;
  email: string | null;
  telephoneNumber: string | null;
  employeeId: string | null;
  lastSyncedAt: string | null;
}

// ─── Componente ───────────────────────────────────────────────────────────────

/**
 * AdSyncTab — painel de sincronização e mapeamento de grupos AD → grupo de acesso.
 * Consome o AdSyncController já existente no backend (Fase 1) — RBAC herdado de
 * `telecom.settings`, mesma permissão que já protege a seção "ad" de Settings.tsx.
 */
export function AdSyncTab() {
  const [status, setStatus] = useState<SyncStatus | null>(null);
  const [statusLoading, setStatusLoading] = useState(true);
  const [syncing, setSyncing] = useState(false);

  const [query, setQuery] = useState('');
  const [lookupResult, setLookupResult] = useState<AdUserLookup | null>(null);
  const [lookupError, setLookupError] = useState('');
  const [lookupLoading, setLookupLoading] = useState(false);

  const [mappings, setMappings] = useState<GroupMapping[]>([]);
  const [mappingsLoading, setMappingsLoading] = useState(true);
  const [accessGroups, setAccessGroups] = useState<AccessGroupOption[]>([]);
  const [modalOpen, setModalOpen] = useState(false);
  const [newAdGroupName, setNewAdGroupName] = useState('');
  const [newAccessGroupId, setNewAccessGroupId] = useState<number | ''>('');
  const [modalError, setModalError] = useState('');
  const [modalSaving, setModalSaving] = useState(false);

  // Guarda contra setState após desmonte (seção "ad" pode ser recolhida antes da resposta
  // chegar, já que este componente só é montado enquanto a seção está expandida).
  const mountedRef = useRef(true);
  useEffect(() => () => { mountedRef.current = false; }, []);

  // Guarda de sequência contra corrida na busca — mesmo padrão já usado no projeto (ex:
  // ReportsQueueTab/perfil do cliente da Fase 9c/27) para nunca deixar uma resposta antiga
  // sobrescrever o resultado de uma busca mais recente.
  const lookupSeqRef = useRef(0);

  const loadStatus = async () => {
    setStatusLoading(true);
    try {
      const res = await api.get<SyncStatus>('/ad/sync-status');
      if (mountedRef.current) setStatus(res.data);
    } catch {
      if (mountedRef.current) setStatus(null);
    } finally {
      if (mountedRef.current) setStatusLoading(false);
    }
  };

  const loadMappings = async () => {
    setMappingsLoading(true);
    try {
      const res = await api.get<GroupMapping[]>('/ad/group-mappings');
      if (mountedRef.current) setMappings(res.data);
    } catch {
      if (mountedRef.current) setMappings([]);
    } finally {
      if (mountedRef.current) setMappingsLoading(false);
    }
  };

  const loadAccessGroups = async () => {
    try {
      const res = await api.get<AccessGroupOption[]>('/access-groups');
      if (mountedRef.current) setAccessGroups(res.data);
    } catch {
      if (mountedRef.current) setAccessGroups([]);
    }
  };

  useEffect(() => {
    loadStatus();
    loadMappings();
    loadAccessGroups();
  }, []);

  const handleSyncNow = async () => {
    setSyncing(true);
    try {
      const res = await api.post<SyncStatus>('/ad/sync');
      if (mountedRef.current) setStatus(res.data);
    } catch {
      alert('Erro ao disparar a sincronização. Verifique a configuração de AD acima.');
    } finally {
      if (mountedRef.current) setSyncing(false);
    }
  };

  const handleLookup = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!query.trim()) return;
    const seq = ++lookupSeqRef.current;
    setLookupLoading(true);
    setLookupError('');
    setLookupResult(null);
    try {
      const res = await api.get<AdUserLookup>('/ad/users', { params: { query: query.trim() } });
      if (seq === lookupSeqRef.current && mountedRef.current) setLookupResult(res.data);
    } catch {
      if (seq === lookupSeqRef.current && mountedRef.current) {
        setLookupError('Usuário não encontrado no espelho local. Rode a sincronização se o usuário for recente.');
      }
    } finally {
      if (seq === lookupSeqRef.current && mountedRef.current) setLookupLoading(false);
    }
  };

  const openModal = async () => {
    setNewAdGroupName('');
    setModalError('');
    setModalOpen(true);
    await loadAccessGroups();
    if (mountedRef.current) setNewAccessGroupId(prev => (prev !== '' ? prev : accessGroups[0]?.id ?? ''));
  };

  const handleCreateMapping = async () => {
    if (!newAdGroupName.trim() || newAccessGroupId === '') {
      setModalError('Preencha o nome do grupo AD e selecione um grupo de acesso.');
      return;
    }
    setModalSaving(true);
    setModalError('');
    try {
      await api.post('/ad/group-mappings', {
        adGroupName: newAdGroupName.trim(),
        accessGroupId: newAccessGroupId,
      });
      setModalOpen(false);
      await loadMappings();
    } catch (err: unknown) {
      const message =
        (err as { response?: { data?: { error?: string } } })?.response?.data?.error ??
        'Erro ao criar mapeamento.';
      setModalError(message);
    } finally {
      setModalSaving(false);
    }
  };

  const handleDeleteMapping = async (mapping: GroupMapping) => {
    if (!confirm(`Remover o mapeamento do grupo AD "${mapping.adGroupName}"?`)) return;
    try {
      await api.delete(`/ad/group-mappings/${mapping.id}`);
      await loadMappings();
    } catch {
      alert('Erro ao remover mapeamento.');
    }
  };

  return (
    <div style={{ marginTop: 16, display: 'flex', flexDirection: 'column', gap: 16 }}>

      {/* Status da sincronização */}
      <div className="stat-card" style={{ padding: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12 }}>
          <div>
            <div style={{ fontWeight: 600, fontSize: '0.9rem' }}>Sincronização com o Active Directory</div>
            {statusLoading ? (
              <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginTop: 4 }}>Carregando status…</div>
            ) : status ? (
              <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginTop: 4 }}>
                {status.status === 'NEVER_RUN' ? (
                  'Nunca sincronizado ainda.'
                ) : (
                  <>
                    Última execução: <strong>{status.status}</strong>
                    {status.startedAt && ` — iniciada em ${new Date(status.startedAt).toLocaleString('pt-BR')}`}
                    {' — '}{status.usersSynced} usuário(s) sincronizado(s)
                    {status.errorMessage && (
                      <div style={{ color: '#ff6b6b', marginTop: 2 }}>⚠ {status.errorMessage}</div>
                    )}
                  </>
                )}
              </div>
            ) : (
              <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginTop: 4 }}>
                Não foi possível carregar o status.
              </div>
            )}
          </div>
          <button className="btn btn-primary btn-sm" onClick={handleSyncNow} disabled={syncing}>
            {syncing ? <><span className="spinner" style={{ width: 11, height: 11, margin: '0 5px 0 0', borderTopColor: '#fff' }} />Sincronizando…</> : '🔄 Sincronizar agora'}
          </button>
        </div>
      </div>

      {/* Busca de usuário AD */}
      <div className="stat-card" style={{ padding: 16 }}>
        <div style={{ fontWeight: 600, fontSize: '0.9rem', marginBottom: 8 }}>Consultar usuário no espelho local</div>
        <form onSubmit={handleLookup} style={{ display: 'flex', gap: 8 }}>
          <input
            className="form-input" style={{ flex: 1 }}
            placeholder="sAMAccountName (ex: joao.silva)"
            value={query} onChange={e => setQuery(e.target.value)}
          />
          <button type="submit" className="btn btn-ghost btn-sm" disabled={lookupLoading || !query.trim()}>
            {lookupLoading ? 'Buscando…' : '🔍 Buscar'}
          </button>
        </form>
        {lookupError && <div style={{ color: '#ff6b6b', fontSize: '0.78rem', marginTop: 8 }}>{lookupError}</div>}
        {lookupResult && (
          <div style={{ marginTop: 12, fontSize: '0.82rem', display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 8 }}>
            <div><strong>Nome:</strong> {lookupResult.displayName ?? '—'}</div>
            <div><strong>Departamento:</strong> {lookupResult.department ?? '—'}</div>
            <div><strong>Cargo:</strong> {lookupResult.title ?? '—'}</div>
            <div><strong>E-mail:</strong> {lookupResult.email ?? '—'}</div>
            <div><strong>Telefone:</strong> {lookupResult.telephoneNumber ?? '—'}</div>
            <div><strong>Matrícula:</strong> {lookupResult.employeeId ?? '—'}</div>
            <div>
              <strong>Última sincronização:</strong>{' '}
              {lookupResult.lastSyncedAt ? new Date(lookupResult.lastSyncedAt).toLocaleString('pt-BR') : '—'}
            </div>
          </div>
        )}
      </div>

      {/* Mapeamento de grupos AD → grupo de acesso */}
      <div className="stat-card" style={{ padding: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
          <div style={{ fontWeight: 600, fontSize: '0.9rem' }}>Mapeamento de grupos AD → grupo de acesso</div>
          <button className="btn btn-ghost btn-sm" onClick={openModal}>+ Novo mapeamento</button>
        </div>
        <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginBottom: 8 }}>
          Use o DN completo do grupo, exatamente como retornado pelo AD (ex: <code style={{ fontFamily: 'monospace' }}>CN=Suporte,OU=Grupos,DC=empresa,DC=local</code>) — não o nome simples.
        </div>
        {mappingsLoading ? (
          <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Carregando…</div>
        ) : (
          <div className="table-wrapper">
            <table className="table">
              <thead>
                <tr><th>Grupo AD</th><th>Grupo de Acesso</th><th></th></tr>
              </thead>
              <tbody>
                {mappings.length === 0 ? (
                  <tr><td colSpan={3} className="table-empty">Nenhum mapeamento cadastrado — novos usuários AD recebem o grupo padrão configurado acima.</td></tr>
                ) : mappings.map(m => (
                  <tr key={m.id}>
                    <td style={{ fontFamily: 'monospace', fontSize: '0.78rem' }}>{m.adGroupName}</td>
                    <td>{m.accessGroupName}</td>
                    <td>
                      <button className="btn btn-danger btn-sm btn-icon" title="Remover" onClick={() => handleDeleteMapping(m)}>🗑️</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {modalOpen && (
        <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) setModalOpen(false); }}>
          <div className="modal">
            <div className="modal-header">
              <h3>Novo mapeamento de grupo</h3>
            </div>
            <div className="modal-body">
              <div className="form-group">
                <label className="form-label">Nome do grupo AD (DN completo)</label>
                <input
                  type="text" className="form-input" autoFocus
                  placeholder="CN=Suporte,OU=Grupos,DC=empresa,DC=local"
                  value={newAdGroupName} onChange={e => setNewAdGroupName(e.target.value)}
                />
              </div>
              <div className="form-group">
                <label className="form-label">Grupo de acesso</label>
                <select className="form-select" value={newAccessGroupId}
                  onChange={e => setNewAccessGroupId(e.target.value ? Number(e.target.value) : '')}>
                  <option value="">Selecione…</option>
                  {accessGroups.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}
                </select>
              </div>
              {modalError && <div style={{ color: '#ff6b6b', fontSize: '0.8rem' }}>{modalError}</div>}
            </div>
            <div className="modal-footer">
              <button className="btn btn-ghost btn-sm" onClick={() => setModalOpen(false)}>Cancelar</button>
              <button className="btn btn-primary btn-sm" onClick={handleCreateMapping} disabled={modalSaving}>
                {modalSaving ? 'Salvando…' : 'Salvar'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
