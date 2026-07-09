import { useEffect, useState, useCallback } from 'react';
import api from '../api/client';

// ─── Tipos ────────────────────────────────────────────────────────────────────
interface Jail {
  name: string; enabled: boolean; maxretry: number; findtime: number;
  bantime: number; banaction: string; currentlyBanned: number; totalFailed: number;
  filterRegex?: string; jailConfig?: string;
}
interface BannedIp {
  ip: string; jail: string; origin: 'fail2ban'|'manual'; note?: string; ts?: string;
}
interface Status {
  fail2banRunning: boolean; activeJails: number; totalBanned: number;
  jails: Jail[]; whitelist: string[];
}

type ActiveTab = 'jails'|'blocked'|'whitelist';

// ─── Helpers ──────────────────────────────────────────────────────────────────
const JAIL_LABELS: Record<string,string> = {
  'asterisk-auth':  'Falhas de autenticação',
  'asterisk-scan':  'Scanner de ramais',
  'asterisk-flood': 'Flood SIP',
};
const JAIL_DESC: Record<string,string> = {
  'asterisk-auth':  'Bloqueia brute-force de senha e autenticações repetidas inválidas',
  'asterisk-scan':  'Bloqueia varredura de extensões — IPs tentando muitos ramais diferentes',
  'asterisk-flood': 'Bloqueia flood de pacotes SIP (OPTIONS flood, REGISTER flood em massa)',
};

function fmtBantime(s: number) {
  if (s === -1) return 'Permanente';
  if (s >= 86400) return `${Math.round(s/86400)}d`;
  if (s >= 3600)  return `${Math.round(s/3600)}h`;
  return `${s}s`;
}

function Badge({ children, color }: { children: React.ReactNode; color: 'green'|'red'|'amber'|'blue'|'gray' }) {
  const styles: Record<string, React.CSSProperties> = {
    green: { background:'var(--bg-success-soft)', color:'var(--clr-success)' },
    red:   { background:'var(--bg-danger-soft)',  color:'var(--clr-danger)'  },
    amber: { background:'var(--bg-warning-soft)', color:'var(--clr-warning)' },
    blue:  { background:'var(--bg-primary-soft)', color:'var(--clr-primary)' },
    gray:  { background:'var(--bg-input)',        color:'var(--text-muted)',
             border:'0.5px solid var(--border-glass)' },
  };
  return (
    <span style={{ display:'inline-flex', alignItems:'center', gap:3, fontSize:'0.7rem',
      fontWeight:500, padding:'2px 7px', borderRadius:4, ...styles[color] }}>
      {children}
    </span>
  );
}

// ─── Componente principal ─────────────────────────────────────────────────────
export default function ModuloSeguranca() {
  const [status,     setStatus]     = useState<Status|null>(null);
  const [banned,     setBanned]     = useState<BannedIp[]>([]);
  const [loading,    setLoading]    = useState(true);
  const [activeTab,  setActiveTab]  = useState<ActiveTab>('jails');

  // Jail editor
  const [editingJail, setEditingJail] = useState<Jail|null>(null);
  const [jailForm,    setJailForm]    = useState<Partial<Jail>>({});
  const [testRegex,   setTestRegex]   = useState('');
  const [testResult,  setTestResult]  = useState<{matches:string[];count:number;tested:number}|null>(null);
  const [testLoading, setTestLoading] = useState(false);

  // Ban form
  const [banIp,   setBanIp]   = useState('');
  const [banNote, setBanNote] = useState('');
  const [banJail, setBanJail] = useState('asterisk-auth');
  const [banLoading, setBanLoading] = useState(false);

  // Whitelist form
  const [wlIp, setWlIp] = useState('');

  // Search
  const [search, setSearch] = useState('');

  // Lockdown
  const [lockdown,        setLockdown]        = useState(false);
  const [lockdownLoading, setLockdownLoading] = useState(false);

  // Toast
  const [toast, setToast] = useState<{msg:string;ok:boolean}|null>(null);
  const showToast = (ok: boolean, msg: string) => {
    setToast({ok,msg});
    setTimeout(() => setToast(null), 3500);
  };

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [statusRes, bannedRes, lockdownRes] = await Promise.all([
        api.get<Status>('/security/status'),
        api.get<BannedIp[]>('/security/banned'),
        api.get<{active:boolean}>('/security/lockdown'),
      ]);
      setStatus(statusRes.data);
      setBanned(bannedRes.data);
      setLockdown(lockdownRes.data.active);
    } catch { showToast(false, 'Erro ao carregar dados de segurança.'); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  // ── Jails ──────────────────────────────────────────────────────────────────
  const openJailEditor = async (jail: Jail) => {
    try {
      const res = await api.get<Jail>(`/security/jails/${jail.name}`);
      setEditingJail(res.data);
      setJailForm(res.data);
      setTestRegex(res.data.filterRegex ?? '');
      setTestResult(null);
    } catch { showToast(false, 'Erro ao carregar configuração do jail.'); }
  };

  const saveJail = async () => {
    if (!editingJail) return;
    try {
      await api.put(`/security/jails/${editingJail.name}`, {
        ...jailForm, filterRegex: testRegex,
      });
      showToast(true, `Jail ${editingJail.name} salvo e recarregado.`);
      setEditingJail(null);
      load();
    } catch { showToast(false, 'Erro ao salvar jail.'); }
  };

  const toggleJail = async (jail: Jail) => {
    try {
      await api.post(`/security/jails/${jail.name}/${jail.enabled ? 'disable' : 'enable'}`);
      showToast(true, `Jail ${jail.name} ${jail.enabled ? 'desativado' : 'ativado'}.`);
      load();
    } catch { showToast(false, 'Erro ao alterar jail.'); }
  };

  const runTestRegex = async () => {
    setTestLoading(true);
    try {
      const res = await api.post<{matches:string[];count:number;tested:number}>(
        '/security/test-regex', { regex: testRegex, lines: 300 });
      setTestResult(res.data);
    } catch { showToast(false, 'Erro ao testar regex.'); }
    finally { setTestLoading(false); }
  };

  // ── Ban / Unban ────────────────────────────────────────────────────────────
  const handleBan = async () => {
    if (!banIp.trim()) return;
    setBanLoading(true);
    try {
      await api.post('/security/ban', { ip: banIp.trim(), note: banNote, jail: banJail });
      showToast(true, `IP ${banIp} bloqueado.`);
      setBanIp(''); setBanNote('');
      load();
    } catch { showToast(false, 'Erro ao bloquear IP.'); }
    finally { setBanLoading(false); }
  };

  const handleUnban = async (ip: string, jail?: string) => {
    try {
      await api.delete(`/security/ban/${ip}${jail ? `?jail=${jail}` : ''}`);
      showToast(true, `IP ${ip} desbloqueado.`);
      load();
    } catch { showToast(false, 'Erro ao desbloquear IP.'); }
  };

  // ── Whitelist ─────────────────────────────────────────────────────────────
  const addWhitelist = async () => {
    if (!wlIp.trim()) return;
    try {
      await api.post('/security/whitelist', { ip: wlIp.trim() });
      showToast(true, `${wlIp} adicionado à lista branca.`);
      setWlIp(''); load();
    } catch { showToast(false, 'Erro ao adicionar à lista branca.'); }
  };

  const removeWhitelist = async (ip: string) => {
    try {
      await api.delete(`/security/whitelist/${ip}`);
      showToast(true, `${ip} removido da lista branca.`);
      load();
    } catch { showToast(false, 'Erro.'); }
  };

  const filteredBanned = banned.filter(b =>
    !search || b.ip.includes(search) || (b.note ?? '').toLowerCase().includes(search.toLowerCase()) || b.jail.includes(search));

  // ─────────────────────────────────────────────────────────────────────────
  return (
    <>
      {/* Toast */}
      {toast && (
        <div style={{
          position:'fixed', top:20, right:24, zIndex:9999,
          background: toast.ok ? 'var(--bg-success-soft)' : 'var(--bg-danger-soft)',
          color: toast.ok ? 'var(--clr-success)' : 'var(--clr-danger)',
          border:`0.5px solid ${toast.ok ? 'var(--clr-success)' : 'var(--clr-danger)'}`,
          padding:'10px 18px', borderRadius:8, fontSize:'0.85rem', fontWeight:500, boxShadow:'0 4px 12px rgba(0,0,0,0.1)',
        }}>
          {toast.ok ? '✅' : '❌'} {toast.msg}
        </div>
      )}

      <div className="page-header">
        <h1>🛡️ Segurança</h1>
        <p>
          {lockdown ? '🔴 MODO LOCKDOWN ATIVO — apenas IPs da lista branca podem conectar ao SIP' : 'Proteção SIP via fail2ban + iptables + ACL Asterisk — política libera geral, bloqueia seletivamente'}
        </p>
      </div>

      <div className="page-body">

      {/* Camadas de proteção */}
      <div style={{ display:'grid', gridTemplateColumns:'1fr auto 1fr auto 1fr', gap:8, alignItems:'center', marginBottom:20 }}>
        {[
          { icon:'🔍', name:'fail2ban', desc:'Monitora log + dispara blocos automáticos',
            ok: status?.fail2banRunning },
          null,
          { icon:'🔥', name:'iptables', desc:'Bloqueio no kernel — antes do Asterisk',
            ok: status?.fail2banRunning },
          null,
          { icon:'🔒', name:'ACL Asterisk', desc:'Bloqueio no nível SIP via acl.conf',
            ok: true },
        ].map((item, i) => item === null ? (
          <div key={i} style={{ textAlign:'center', color:'var(--text-muted)', fontSize:18 }}>→</div>
        ) : (
          <div key={i} className="card" style={{ padding:'10px 14px', display:'flex', alignItems:'center', gap:10 }}>
            <span style={{ fontSize:'1.3rem' }}>{item.icon}</span>
            <div style={{ flex:1 }}>
              <div style={{ fontSize:'0.85rem', fontWeight:600, display:'flex', alignItems:'center', gap:6 }}>
                {item.name}
                {item.ok !== undefined && (
                  <Badge color={item.ok ? 'green' : 'red'}>{item.ok ? 'ativo' : 'inativo'}</Badge>
                )}
              </div>
              <div style={{ fontSize:'0.72rem', color:'var(--text-muted)' }}>{item.desc}</div>
            </div>
          </div>
        ))}
      </div>

      {/* Botão Lockdown */}
      <div style={{ display:'flex', alignItems:'center', gap:12, marginBottom:20,
        padding:'12px 16px', borderRadius:8,
        background: lockdown ? 'rgba(255,107,107,0.08)' : 'rgba(148,163,184,0.06)',
        border: `1px solid ${lockdown ? 'rgba(255,107,107,0.3)' : 'var(--border-glass)'}` }}>
        <div style={{ flex:1 }}>
          <div style={{ fontWeight:600, fontSize:'0.9rem', color: lockdown ? 'var(--clr-danger)' : 'var(--text-primary)' }}>
            {lockdown ? '🔴 Modo Lockdown Ativo' : '🟢 Modo Normal (fail2ban)'}
          </div>
          <div style={{ fontSize:'0.78rem', color:'var(--text-muted)', marginTop:2 }}>
            {lockdown
              ? 'Todos IPs externos BLOQUEADOS. Apenas whitelist pode conectar ao SIP.'
              : 'Fail2ban monitora e bloqueia ameaças automaticamente.'}
          </div>
        </div>
        <button
          disabled={lockdownLoading}
          onClick={async () => {
            setLockdownLoading(true);
            try {
              const endpoint = lockdown ? '/security/lockdown/disable' : '/security/lockdown/enable';
              await api.post(endpoint, {});
              setLockdown(!lockdown);
              showToast(true, lockdown ? 'Lockdown desativado.' : 'Lockdown ativado! Apenas whitelist pode conectar.');
              load();
            } catch { showToast(false, 'Erro ao alterar modo de segurança.'); }
            finally { setLockdownLoading(false); }
          }}
          style={{
            padding:'8px 18px', borderRadius:8, fontWeight:600, fontSize:'0.85rem',
            cursor: lockdownLoading ? 'not-allowed' : 'pointer',
            border:'none', opacity: lockdownLoading ? 0.6 : 1,
            background: lockdown ? 'var(--clr-danger)' : 'var(--text-primary)',
            color: '#fff',
          }}
        >
          {lockdownLoading ? '...' : lockdown ? 'Desativar Lockdown' : 'Ativar Lockdown'}
        </button>
      </div>

      {/* Cards de estatísticas */}
      <div style={{ display:'grid', gridTemplateColumns:'repeat(3,1fr)', gap:12, marginBottom:20 }}>
        {[
          { val: status?.totalBanned ?? '—',  lbl:'IPs bloqueados',     color:'var(--clr-danger)'  },
          { val: status?.activeJails ?? '—',  lbl:'Jails ativos',       color:'var(--clr-primary)'    },
          { val: status?.whitelist?.length ?? 0, lbl:'Na lista branca',  color:'var(--clr-success)' },
        ].map((s,i) => (
          <div key={i} style={{ background:'var(--bg-input)', borderRadius:8, padding:'12px 14px',
            border:'0.5px solid var(--border-glass)' }}>
            <div style={{ fontSize:'1.5rem', fontWeight:600, color:s.color }}>{s.val}</div>
            <div style={{ fontSize:'0.72rem', color:'var(--text-muted)', marginTop:2 }}>{s.lbl}</div>
          </div>
        ))}
      </div>

      {/* Tabs */}
      <div style={{ display:'flex', borderBottom:'1px solid var(--border-glass)', marginBottom:16 }}>
        {([
          ['jails','⚙️ Jails fail2ban'],['blocked','🚫 IPs Bloqueados'],
          ['whitelist','✅ Lista Branca'],
        ] as const).map(([id,label]) => (
          <button key={id} onClick={() => setActiveTab(id)} style={{
            padding:'9px 18px', background:'none', border:'none', cursor:'pointer',
            fontSize:'0.85rem', fontWeight: activeTab===id ? 600 : 400,
            color: activeTab===id ? 'var(--clr-primary)' : 'var(--text-muted)',
            borderBottom: activeTab===id ? '2px solid var(--clr-primary)' : '2px solid transparent',
          }}>{label}</button>
        ))}
      </div>

      {/* ── ABA: JAILS ─────────────────────────────────────────────────────── */}
      {activeTab === 'jails' && (
        <div style={{ display:'flex', flexDirection:'column', gap:12 }}>
          {loading ? <div style={{ color:'var(--text-muted)', padding:32, textAlign:'center' }}>Carregando…</div>
          : (status?.jails ?? []).map(jail => (
            <div key={jail.name} className="card" style={{ padding:0, overflow:'hidden' }}>
              <div style={{ display:'flex', alignItems:'center', gap:12, padding:'14px 18px' }}>
                <div style={{ flex:1 }}>
                  <div style={{ fontWeight:600, fontSize:'0.92rem', display:'flex', alignItems:'center', gap:8 }}>
                    {JAIL_LABELS[jail.name] ?? jail.name}
                    <Badge color={jail.enabled ? 'green' : 'gray'}>{jail.enabled ? 'ativo' : 'inativo'}</Badge>
                    <Badge color="blue">{jail.currentlyBanned} banidos</Badge>
                  </div>
                  <div style={{ fontSize:'0.75rem', color:'var(--text-muted)', marginTop:3 }}>
                    {JAIL_DESC[jail.name]}
                  </div>
                  <div style={{ fontSize:'0.72rem', color:'var(--text-muted)', marginTop:4, fontFamily:'monospace' }}>
                    maxretry: <strong>{jail.maxretry}</strong> &nbsp;·&nbsp;
                    findtime: <strong>{jail.findtime}s</strong> &nbsp;·&nbsp;
                    bantime: <strong>{fmtBantime(jail.bantime)}</strong> &nbsp;·&nbsp;
                    action: <strong>{jail.banaction}</strong>
                  </div>
                </div>
                <div style={{ display:'flex', gap:8 }}>
                  <button onClick={() => toggleJail(jail)} className="btn btn-ghost btn-sm">
                    {jail.enabled ? '⏸ Desativar' : '▶ Ativar'}
                  </button>
                  <button onClick={() => openJailEditor(jail)} className="btn btn-ghost btn-sm">
                    ✏️ Editar
                  </button>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* ── EDITOR DE JAIL (modal inline) ─────────────────────────────────── */}
      {editingJail && (
        <div style={{
          position:'fixed', inset:0, background:'rgba(0,0,0,0.4)', zIndex:1000,
          display:'flex', alignItems:'center', justifyContent:'center', padding:24,
        }}>
          <div style={{
            background:'var(--bg-card)', border:'0.5px solid var(--border-glass)',
            borderRadius:12, width:'100%', maxWidth:640, maxHeight:'90vh',
            overflowY:'auto', boxShadow:'0 8px 32px rgba(0,0,0,0.2)',
          }}>
            <div style={{ padding:'16px 20px', borderBottom:'0.5px solid var(--border-glass)',
              display:'flex', alignItems:'center', gap:10 }}>
              <span style={{ fontWeight:600, flex:1 }}>
                Editar: {JAIL_LABELS[editingJail.name] ?? editingJail.name}
              </span>
              <button onClick={() => setEditingJail(null)} className="btn btn-ghost btn-sm">✕ Fechar</button>
            </div>
            <div style={{ padding:20, display:'flex', flexDirection:'column', gap:16 }}>

              <div style={{ display:'grid', gridTemplateColumns:'1fr 1fr', gap:12 }}>
                {[
                  { key:'maxretry', label:'Máx. tentativas (maxretry)' },
                  { key:'findtime', label:'Janela de detecção — findtime (s)' },
                ].map(f => (
                  <div key={f.key}>
                    <label style={{ fontSize:'0.78rem', color:'var(--text-muted)', display:'block', marginBottom:4 }}>
                      {f.label}
                    </label>
                    <input type="number" className="form-input"
                      value={(jailForm as any)[f.key] ?? ''}
                      onChange={e => setJailForm(p => ({...p, [f.key]: Number(e.target.value)}))} />
                  </div>
                ))}
                <div>
                  <label style={{ fontSize:'0.78rem', color:'var(--text-muted)', display:'block', marginBottom:4 }}>
                    Duração do banimento (bantime — segundos, -1=permanente)
                  </label>
                  <select className="form-input" value={jailForm.bantime ?? 86400}
                    onChange={e => setJailForm(p => ({...p, bantime: Number(e.target.value)}))}>
                    <option value={3600}>1 hora</option>
                    <option value={21600}>6 horas</option>
                    <option value={86400}>24 horas</option>
                    <option value={172800}>48 horas</option>
                    <option value={-1}>Permanente (-1)</option>
                  </select>
                </div>
                <div>
                  <label style={{ fontSize:'0.78rem', color:'var(--text-muted)', display:'block', marginBottom:4 }}>
                    Ação de bloqueio
                  </label>
                  <select className="form-input" value={jailForm.banaction ?? ''}
                    onChange={e => setJailForm(p => ({...p, banaction: e.target.value}))}>
                    <option value="iptables-multiport">iptables-multiport (recomendado)</option>
                    <option value="iptables-allports">iptables-allports</option>
                  </select>
                </div>
              </div>

              <div>
                <label style={{ fontSize:'0.78rem', color:'var(--text-muted)', display:'block', marginBottom:4 }}>
                  Expressão de filtro (failregex — aplicada ao /var/log/asterisk/full)
                </label>
                <textarea className="form-textarea" rows={4}
                  value={testRegex}
                  onChange={e => setTestRegex(e.target.value)}
                  style={{ fontFamily:'var(--font-mono)', fontSize:'0.78rem' }} />
              </div>

              {/* Resultado do teste */}
              {testResult && (
                <div style={{
                  background:'var(--bg-input)', borderRadius:8, padding:'10px 14px',
                  border:'0.5px solid var(--border-glass)', fontSize:'0.78rem',
                }}>
                  <div style={{ fontWeight:600, marginBottom:6 }}>
                    {testResult.count > 0
                      ? <span style={{ color:'var(--clr-danger)' }}>⚠️ {testResult.count} linhas corresponderam (em {testResult.tested} testadas)</span>
                      : <span style={{ color:'var(--clr-success)' }}>✅ Nenhuma correspondência (em {testResult.tested} testadas)</span>}
                  </div>
                  {testResult.matches.slice(0,5).map((m,i) => (
                    <div key={i} style={{ fontFamily:'monospace', fontSize:'0.72rem',
                      color:'var(--text-muted)', borderTop:'0.5px solid var(--border-glass)', padding:'3px 0' }}>
                      {m}
                    </div>
                  ))}
                </div>
              )}

              <div style={{ display:'flex', gap:8, justifyContent:'flex-end',
                paddingTop:12, borderTop:'0.5px solid var(--border-glass)' }}>
                <button onClick={runTestRegex} disabled={testLoading} className="btn btn-ghost btn-sm">
                  {testLoading ? '…' : '🔍 Testar regex'}
                </button>
                <button onClick={() => setEditingJail(null)} className="btn btn-ghost btn-sm">Cancelar</button>
                <button onClick={saveJail} className="btn btn-primary btn-sm" style={{ minWidth:140 }}>
                  💾 Salvar e aplicar
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── ABA: IPs BLOQUEADOS ───────────────────────────────────────────── */}
      {activeTab === 'blocked' && (
        <div className="card" style={{ padding:0, overflow:'hidden' }}>
          <div style={{ display:'flex', alignItems:'center', gap:10, padding:'12px 16px',
            borderBottom:'0.5px solid var(--border-glass)', background:'var(--bg-input)', flexWrap:'wrap' }}>
            <input aria-label="Buscar IP, jail ou motivo" placeholder="Buscar IP, jail ou motivo…" value={search}
              onChange={e => setSearch(e.target.value)}
              style={{ fontSize:'0.8rem', padding:'4px 10px', borderRadius:6,
                border:'0.5px solid var(--border-glass)', background:'var(--bg-card)',
                color:'var(--text-primary)', width:220 }} />
            <div style={{ marginLeft:'auto', display:'flex', gap:8 }}>
              <input value={banIp} onChange={e => setBanIp(e.target.value)}
                placeholder="IP ou CIDR para bloquear"
                style={{ fontSize:'0.8rem', padding:'4px 10px', borderRadius:6,
                  border:'0.5px solid var(--border-glass)', background:'var(--bg-card)',
                  color:'var(--text-primary)', width:180 }} />
              <input value={banNote} onChange={e => setBanNote(e.target.value)}
                placeholder="Motivo (opcional)"
                style={{ fontSize:'0.8rem', padding:'4px 10px', borderRadius:6,
                  border:'0.5px solid var(--border-glass)', background:'var(--bg-card)',
                  color:'var(--text-primary)', width:150 }} />
              <select value={banJail} onChange={e => setBanJail(e.target.value)}
                style={{ fontSize:'0.78rem', padding:'4px 7px', borderRadius:6,
                  border:'0.5px solid var(--border-glass)', background:'var(--bg-card)',
                  color:'var(--text-primary)' }}>
                <option value="asterisk-auth">auth</option>
                <option value="asterisk-scan">scan</option>
              </select>
              <button onClick={handleBan} disabled={banLoading || !banIp.trim()}
                className="btn btn-primary btn-sm">
                {banLoading ? '…' : '🚫 Bloquear'}
              </button>
            </div>
          </div>

          <div style={{ fontFamily:'var(--font-mono)', fontSize:'0.78rem' }}>
            <div style={{ display:'grid', gridTemplateColumns:'140px 80px 1fr 80px 90px',
              gap:8, padding:'7px 16px', background:'var(--bg-input)',
              borderBottom:'0.5px solid var(--border-glass)',
              fontSize:'0.7rem', fontWeight:600, color:'var(--text-muted)' }}>
              <span>IP / CIDR</span><span>Origem</span><span>Jail / Motivo</span>
              <span>Bloqueado</span><span>Ação</span>
            </div>
            {filteredBanned.length === 0 ? (
              <div style={{ color:'var(--text-muted)', textAlign:'center', padding:32 }}>
                {loading ? 'Carregando…' : 'Nenhum IP bloqueado.'}
              </div>
            ) : filteredBanned.map((b) => (
              <div key={`${b.ip}-${b.jail}`} style={{ display:'grid',
                gridTemplateColumns:'140px 80px 1fr 80px 90px',
                gap:8, padding:'8px 16px',
                borderBottom:'0.5px solid var(--border-glass)33',
                alignItems:'center' }}>
                <span style={{ fontFamily:'monospace', fontSize:'0.75rem' }}>{b.ip}</span>
                <span><Badge color={b.origin==='manual' ? 'amber' : 'blue'}>{b.origin}</Badge></span>
                <span style={{ color:'var(--text-muted)', fontSize:'0.75rem' }}>
                  {b.jail}{b.note ? ` — ${b.note}` : ''}
                </span>
                <span style={{ color:'var(--text-muted)', fontSize:'0.7rem' }}>
                  {b.ts ? new Date(b.ts).toLocaleDateString('pt-BR') : '—'}
                </span>
                <button onClick={() => handleUnban(b.ip, b.jail)}
                  className="btn btn-ghost btn-sm" style={{ fontSize:'0.72rem' }}>
                  🔓 Liberar
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* ── ABA: LISTA BRANCA ─────────────────────────────────────────────── */}
      {activeTab === 'whitelist' && (
        <div className="card" style={{ padding:0, overflow:'hidden' }}>
          <div style={{ display:'flex', alignItems:'center', gap:8, padding:'12px 16px',
            borderBottom:'0.5px solid var(--border-glass)', background:'var(--bg-input)' }}>
            <input value={wlIp} onChange={e => setWlIp(e.target.value)}
              placeholder="IP ou CIDR (ex: 192.168.1.0/24)"
              style={{ fontSize:'0.8rem', padding:'4px 10px', borderRadius:6,
                border:'0.5px solid var(--border-glass)', background:'var(--bg-card)',
                color:'var(--text-primary)', width:240 }} />
            <button onClick={addWhitelist} disabled={!wlIp.trim()}
              className="btn btn-primary btn-sm">✅ Adicionar</button>
            <span style={{ marginLeft:'auto', fontSize:'0.75rem', color:'var(--text-muted)' }}>
              IPs aqui nunca serão bloqueados pelo fail2ban
            </span>
          </div>
          <div style={{ padding:'0 16px' }}>
            {(status?.whitelist ?? []).map((ip,i) => (
              <div key={i} style={{ display:'flex', alignItems:'center', gap:10, padding:'9px 0',
                borderBottom:'0.5px solid var(--border-glass)', fontSize:'0.82rem' }}>
                <span style={{ fontFamily:'monospace', flex:1 }}>{ip}</span>
                <button onClick={() => removeWhitelist(ip)}
                  className="btn btn-ghost btn-sm" style={{ fontSize:'0.72rem' }}>
                  ✕ Remover
                </button>
              </div>
            ))}
          </div>
        </div>
      )}
      </div>
    </>
  );
}
