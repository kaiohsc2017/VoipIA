import { useEffect, useState } from 'react';
import api from '../api/client';
import type { AccessGroup, AccessGroupPermission, AccessGroupRequest } from '../api/types';

// Catálogo fixo de recursos (menus) — espelha ResourceCatalog.java, o NAV do
// agents-platform/frontend e o App.tsx da SPA insights-platform/frontend.
// Manter em sincronia manual.
const RESOURCE_CATALOG: { key: string; label: string; system: 'Telecom' | 'Agentes' | 'Insights' | 'Financeiro' | 'Call Center' }[] = [
  { key: 'telecom.dashboard',    label: 'Dashboard',              system: 'Telecom' },
  { key: 'telecom.modulo1',      label: 'URA',                    system: 'Telecom' },
  { key: 'telecom.insights_link', label: 'Insights (link de nav)', system: 'Telecom' },
  { key: 'telecom.modulo2',      label: 'Conectividade',          system: 'Telecom' },
  { key: 'telecom.modulo3',      label: 'Monitoramento',          system: 'Telecom' },
  { key: 'telecom.agents_link',  label: 'Agentes (link de nav)',  system: 'Telecom' },
  { key: 'telecom.callcenter_link', label: 'Call Center (link de nav)', system: 'Telecom' },
  { key: 'telecom.masterdata',   label: 'Clientes',               system: 'Telecom' },
  { key: 'telecom.operadoras',   label: 'Operadoras',             system: 'Telecom' },
  { key: 'telecom.0800',         label: '0800',                   system: 'Telecom' },
  { key: 'telecom.linhas',       label: 'Linhas',                 system: 'Telecom' },
  { key: 'telecom.users',        label: 'Usuários',               system: 'Telecom' },
  { key: 'telecom.settings',     label: 'Configurações',          system: 'Telecom' },
  { key: 'telecom.logs',         label: 'Logs',                   system: 'Telecom' },
  { key: 'telecom.security',     label: 'Segurança',              system: 'Telecom' },
  { key: 'telecom.audit',        label: 'Auditoria',              system: 'Telecom' },
  { key: 'telecom.docs',         label: 'Documentação',           system: 'Telecom' },
  { key: 'telecom.release',      label: 'Release',                system: 'Telecom' },
  { key: 'agents.dashboard',     label: 'Dashboard',              system: 'Agentes' },
  { key: 'agents.agents',        label: 'Agentes (cadastro)',     system: 'Agentes' },
  { key: 'agents.servers',       label: 'Servidores SSH',         system: 'Agentes' },
  { key: 'agents.knowledge',     label: 'Base de Conhecimento',   system: 'Agentes' },
  { key: 'agents.logs',          label: 'Logs de Execução',       system: 'Agentes' },
  { key: 'agents.reports',       label: 'Alertas',                system: 'Agentes' },
  { key: 'agents.secrets',       label: 'Secrets',                system: 'Agentes' },
  { key: 'agents.llm',           label: 'Config. IA',             system: 'Agentes' },
  { key: 'insights.calls',       label: 'Chamadas',               system: 'Insights' },
  { key: 'insights.dashboard',   label: 'Dashboard',              system: 'Insights' },
  { key: 'insights.processing',  label: 'Processamento',          system: 'Insights' },
  { key: 'insights.scorecards',  label: 'Fichas',                 system: 'Insights' },
  { key: 'insights.reports',     label: 'Relatórios',             system: 'Insights' },
  { key: 'insights.uploads',     label: 'Meus Envios (upload)',   system: 'Insights' },
  { key: 'financeiro.ura',       label: 'URA',                    system: 'Financeiro' },
  { key: 'financeiro.insights',  label: 'Insights',                system: 'Financeiro' },
  { key: 'financeiro.envios',    label: 'Análise Sob Demanda',    system: 'Financeiro' },
  { key: 'financeiro.callcenter', label: 'Call Center',           system: 'Financeiro' },
  { key: 'callcenter.agentes',   label: 'Agentes',                system: 'Call Center' },
  { key: 'callcenter.ramais',    label: 'Senha do ramal (sensível)', system: 'Call Center' },
  { key: 'callcenter.filas',     label: 'Filas',                  system: 'Call Center' },
  { key: 'callcenter.skills',    label: 'Skills',                 system: 'Call Center' },
  { key: 'callcenter.gravacoes', label: 'Gravações',              system: 'Call Center' },
  { key: 'callcenter.desktop',   label: 'Desktop do Agente',      system: 'Call Center' },
  { key: 'callcenter.supervisao', label: 'Supervisão',            system: 'Call Center' },
  { key: 'callcenter.fluxos',    label: 'Fluxos (Flow Builder)',  system: 'Call Center' },
  { key: 'callcenter.insights.calls',       label: 'Insights — Chamadas',       system: 'Call Center' },
  { key: 'callcenter.insights.dashboard',   label: 'Insights — Dashboard',      system: 'Call Center' },
  { key: 'callcenter.insights.processing',  label: 'Insights — Processamento',  system: 'Call Center' },
  { key: 'callcenter.config',    label: 'Configurações (pausas/tabulações)', system: 'Call Center' },
];

type PermMap = Record<string, { canRead: boolean; canWrite: boolean }>;

function emptyPermMap(): PermMap {
  const map: PermMap = {};
  RESOURCE_CATALOG.forEach(r => { map[r.key] = { canRead: false, canWrite: false }; });
  return map;
}

function permsToMap(perms: AccessGroupPermission[]): PermMap {
  const map = emptyPermMap();
  perms.forEach(p => { map[p.resourceKey] = { canRead: p.canRead, canWrite: p.canWrite }; });
  return map;
}

function mapToPerms(map: PermMap): AccessGroupPermission[] {
  return Object.entries(map).map(([resourceKey, v]) => ({ resourceKey, canRead: v.canRead, canWrite: v.canWrite }));
}

interface FormState {
  name: string;
  description: string;
  perms: PermMap;
}

const EMPTY_FORM: FormState = { name: '', description: '', perms: emptyPermMap() };

export default function AccessGroups() {
  const [groups, setGroups] = useState<AccessGroup[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreate, setShowCreate] = useState(false);
  const [editGroup, setEditGroup] = useState<AccessGroup | null>(null);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [saving, setSaving] = useState(false);

  const load = () => {
    setLoading(true);
    api.get<AccessGroup[]>('/access-groups')
      .then(r => setGroups(r.data ?? []))
      .catch(() => setGroups([]))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  const openCreate = () => { setForm(EMPTY_FORM); setShowCreate(true); };

  const openEdit = (g: AccessGroup) => {
    setEditGroup(g);
    setForm({ name: g.name, description: g.description ?? '', perms: permsToMap(g.permissions) });
  };

  const closeModal = () => { setShowCreate(false); setEditGroup(null); };

  const togglePerm = (resourceKey: string, field: 'canRead' | 'canWrite') => {
    setForm(f => ({
      ...f,
      perms: {
        ...f.perms,
        [resourceKey]: { ...f.perms[resourceKey], [field]: !f.perms[resourceKey][field] },
      },
    }));
  };

  const handleSave = async () => {
    if (!form.name.trim()) { alert('Informe o nome do grupo.'); return; }
    const body: AccessGroupRequest = {
      name: form.name.trim(),
      description: form.description.trim() || null,
      permissions: mapToPerms(form.perms),
    };
    setSaving(true);
    try {
      if (editGroup) {
        await api.put(`/access-groups/${editGroup.id}`, body);
      } else {
        await api.post('/access-groups', body);
      }
      closeModal();
      load();
    } catch (e: unknown) {
      const err = e as { response?: { data?: { message?: string } } };
      alert(err?.response?.data?.message ?? 'Erro ao salvar grupo.');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (g: AccessGroup) => {
    if (g.isSystem) { alert('Grupos de sistema não podem ser excluídos.'); return; }
    if (!confirm(`Excluir o grupo "${g.name}"? Usuários vinculados a ele precisam ser realocados antes.`)) return;
    try {
      await api.delete(`/access-groups/${g.id}`);
      load();
    } catch (e: unknown) {
      const err = e as { response?: { data?: { message?: string } } };
      alert(err?.response?.data?.message ?? 'Erro ao excluir grupo (verifique se algum usuário ainda está nele).');
    }
  };

  const countActive = (g: AccessGroup) => g.permissions.filter(p => p.canRead || p.canWrite).length;

  return (
    <>
      <div className="page-header">
        <h1>🔑 Grupos de Acesso</h1>
        <p>Defina grupos com permissão de leitura e escrita por menu, em vez do perfil binário Admin/Usuário.</p>
      </div>
      <div className="page-body">

        <div className="toolbar">
          <div className="toolbar-left">
            <span style={{ color: 'var(--text-muted)', fontSize: '0.855rem' }}>
              {groups.length} grupo{groups.length !== 1 ? 's' : ''} cadastrado{groups.length !== 1 ? 's' : ''}
            </span>
          </div>
          <div className="toolbar-right">
            <button className="btn btn-primary" onClick={openCreate}>＋ Novo Grupo</button>
          </div>
        </div>

        {loading ? (
          <div className="loading-state"><div className="spinner" />Carregando grupos…</div>
        ) : (
          <div className="table-wrapper">
            <table>
              <thead>
                <tr>
                  <th>Nome</th>
                  <th>Descrição</th>
                  <th style={{ width: 110 }}>Tipo</th>
                  <th style={{ width: 140 }}>Recursos c/ acesso</th>
                  <th style={{ width: 120 }}>Ações</th>
                </tr>
              </thead>
              <tbody>
                {groups.length === 0 ? (
                  <tr><td colSpan={5} className="table-empty">Nenhum grupo cadastrado.</td></tr>
                ) : groups.map(g => (
                  <tr key={g.id}>
                    <td style={{ fontWeight: 600 }}>{g.name}</td>
                    <td className="td-muted">{g.description || '—'}</td>
                    <td>
                      <span className={`badge ${g.isSystem ? 'badge-warning' : 'badge-info'}`}>
                        {g.isSystem ? '⚙ Sistema' : '✎ Custom'}
                      </span>
                    </td>
                    <td>{countActive(g)} / {RESOURCE_CATALOG.length}</td>
                    <td>
                      <div className="flex gap-1">
                        <button className="btn btn-ghost btn-sm btn-icon" onClick={() => openEdit(g)} title="Editar">✏️</button>
                        {!g.isSystem && (
                          <button className="btn btn-danger btn-sm btn-icon" onClick={() => handleDelete(g)} title="Excluir">🗑️</button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {(showCreate || editGroup) && (
        <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) closeModal(); }}>
          <div className="modal modal-lg">
            <div className="modal-header">
              <h2>{editGroup ? `✏️ Editar: ${editGroup.name}` : '🔑 Novo Grupo de Acesso'}</h2>
              <button className="btn-close" onClick={closeModal}>×</button>
            </div>
            <div className="modal-body">
              <div className="form-group">
                <label className="form-label">Nome *</label>
                <input
                  type="text" className="form-input" autoFocus
                  placeholder="ex: Suporte N1"
                  value={form.name}
                  onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                  disabled={!!editGroup?.isSystem}
                />
              </div>
              <div className="form-group">
                <label className="form-label">Descrição</label>
                <input
                  type="text" className="form-input"
                  placeholder="ex: Acesso operacional sem configurações administrativas"
                  value={form.description}
                  onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
                />
              </div>

              <div className="form-group">
                <label className="form-label">Permissões por menu</label>
                <div className="table-wrapper" style={{ maxHeight: 360, overflowY: 'auto' }}>
                  <table>
                    <thead>
                      <tr>
                        <th>Sistema</th>
                        <th>Menu</th>
                        <th style={{ width: 80, textAlign: 'center' }}>Leitura</th>
                        <th style={{ width: 80, textAlign: 'center' }}>Escrita</th>
                      </tr>
                    </thead>
                    <tbody>
                      {RESOURCE_CATALOG.map(r => (
                        <tr key={r.key}>
                          <td className="td-muted">{r.system}</td>
                          <td>{r.label}</td>
                          <td style={{ textAlign: 'center' }}>
                            <input
                              type="checkbox"
                              checked={form.perms[r.key]?.canRead ?? false}
                              onChange={() => togglePerm(r.key, 'canRead')}
                            />
                          </td>
                          <td style={{ textAlign: 'center' }}>
                            <input
                              type="checkbox"
                              checked={form.perms[r.key]?.canWrite ?? false}
                              onChange={() => togglePerm(r.key, 'canWrite')}
                            />
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn btn-ghost" onClick={closeModal}>Cancelar</button>
              <button className="btn btn-primary" onClick={handleSave} disabled={saving}>
                {saving ? 'Salvando…' : 'Salvar Grupo'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
