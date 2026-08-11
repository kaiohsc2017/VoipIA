import { useEffect, useRef, useState, useCallback } from 'react';
import api from '../api/client';

// ─── Tipos ────────────────────────────────────────────────────────────────────

interface LogEntry {
  ts: string; service?: string; level: string; category?: string; msg: string; raw?: string;
  /** Id sintético local (não vem do backend) — só para servir de key estável do React,
   * já que a lista trunca do início (`slice(-999)`) a cada novo evento SSE e `ts` não é
   * garantidamente único entre linhas. */
  _id?: number;
}

let _logIdSeq = 0;
/** Anexa um _id sintético monotônico — usado tanto no snapshot inicial quanto no SSE. */
function withLogId(entry: LogEntry): LogEntry {
  return { ...entry, _id: _logIdSeq++ };
}

interface AsteriskEndpoint { name: string; status: string; }
interface AsteriskTrunk    { name: string; status: string; }
interface AsteriskStatus {
  ok: boolean; uptime?: string; version?: string; channels?: number;
  endpoints?: AsteriskEndpoint[]; trunk?: AsteriskTrunk; error?: string;
}

interface ChartData { byHour: Record<string,number>; errByHour: Record<string,number>; }

type DockerService = 'backend'|'asterisk'|'ai-agent'|'scheduler'|'frontend'|'postgres'|'prometheus'|'grafana';
type LogLevel      = 'ERROR'|'WARN'|'INFO'|'DEBUG';
type AstCategory   = 'REGISTER'|'CALL'|'PJSIP'|'DTLS'|'AMI'|'ERROR'|'WARN';
type ActiveTab     = 'docker'|'asterisk';
type DockerSubTab  = 'live'|'history';

// ─── Constantes ───────────────────────────────────────────────────────────────

const DOCKER_SERVICES: DockerService[] = ['backend','asterisk','ai-agent','scheduler','frontend','postgres'];
const LOG_LEVELS: LogLevel[]           = ['ERROR','WARN','INFO','DEBUG'];
const AST_CATEGORIES: AstCategory[]   = ['REGISTER','CALL','PJSIP','DTLS','AMI','ERROR','WARN'];

const LEVEL_COLORS: Record<string,string> = {
  ERROR:'#b91c1c', WARN:'#b45309', WARNING:'#b45309', INFO:'#075985',
  DEBUG:'#6b7280', REGISTER:'#075985', CALL:'#1a7a3d', PJSIP:'#075985',
  DTLS:'#b91c1c', AMI:'#6b7280', VERBOSE:'#6b7280', NOTICE:'#075985',
};
const LEVEL_BG: Record<string,string> = {
  ERROR:'#fee2e2', WARN:'#fef3c7', WARNING:'#fef3c7', INFO:'#e0f2fe',
  DEBUG:'var(--bg-input)', REGISTER:'#e0f2fe', CALL:'#dcfce7',
  PJSIP:'#e0f2fe', DTLS:'#fee2e2', AMI:'var(--bg-input)',
  VERBOSE:'var(--bg-input)', NOTICE:'#e0f2fe',
};

// ─── Utilitários ──────────────────────────────────────────────────────────────

const today = () => new Date().toISOString().slice(0,10);
const fmtTs  = (ts: string) => ts.length > 10 ? ts.slice(11,19) : ts;

function Badge({ label }: { label: string }) {
  const color = LEVEL_COLORS[label.toUpperCase()] ?? '#6b7280';
  const bg    = LEVEL_BG[label.toUpperCase()]    ?? 'var(--bg-input)';
  return (
    <span style={{
      fontSize:'0.68rem', padding:'1px 6px', borderRadius:4, fontWeight:600,
      color, background: bg, border:`0.5px solid ${color}33`,
      display:'inline-block', flexShrink:0, minWidth:58, textAlign:'center',
    }}>{label}</span>
  );
}

function MiniChart({ data, height=48 }: { data?: ChartData; height?: number }) {
  if (!data) return null;
  const hours  = Object.keys(data.byHour);
  const maxVal = Math.max(1, ...Object.values(data.byHour));
  return (
    <div style={{ padding:'10px 16px 6px', borderBottom:'0.5px solid var(--border-glass)' }}>
      <div style={{ fontSize:'0.72rem', color:'var(--text-muted)', marginBottom:6, display:'flex', alignItems:'center', gap:6 }}>
        📊 Volume por hora
        <span style={{ marginLeft:'auto', display:'flex', gap:10, fontSize:'0.68rem' }}>
          <span style={{ color:'rgba(99,102,241,0.7)' }}>■ eventos</span>
          <span style={{ color:'rgba(185,28,28,0.6)' }}>■ erros</span>
        </span>
      </div>
      <div style={{ display:'flex', alignItems:'flex-end', gap:3, height }}>
        {hours.map(h => {
          const val = data.byHour[h] ?? 0;
          const err = data.errByHour?.[h] ?? 0;
          const barH = Math.max(2, Math.round((val / maxVal) * height));
          const errH = Math.max(0, Math.round((err / maxVal) * height));
          return (
            <div key={h} style={{ flex:1, display:'flex', flexDirection:'column', alignItems:'center', gap:1 }}>
              <div style={{ width:'100%', display:'flex', flexDirection:'column', alignItems:'center', gap:1 }}>
                {errH > 0 && <div style={{ width:'100%', height:errH, background:'rgba(185,28,28,0.5)', borderRadius:'2px 2px 0 0' }}/>}
                <div style={{ width:'100%', height:barH-errH, background:'rgba(99,102,241,0.4)', borderRadius: errH>0?0:'2px 2px 0 0' }}/>
              </div>
              <span style={{ fontSize:9, color:'var(--text-muted)', whiteSpace:'nowrap' }}>{h}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

// ─── Componente principal ─────────────────────────────────────────────────────

export default function ModuloLogs() {
  const [activeTab,      setActiveTab]      = useState<ActiveTab>('docker');
  const [dockerSubTab,   setDockerSubTab]   = useState<DockerSubTab>('live');

  // Docker state
  const [dockerEntries,  setDockerEntries]  = useState<LogEntry[]>([]);
  const [dockerChart,    setDockerChart]    = useState<ChartData>();
  const [dockerSvcs,     setDockerSvcs]     = useState<Set<DockerService>>(new Set(['backend','asterisk','ai-agent','scheduler']));
  const [dockerLevels,   setDockerLevels]   = useState<Set<LogLevel>>(new Set(['ERROR','WARN','INFO']));
  const [dockerLines,    setDockerLines]    = useState(200);
  const [dockerFrom,     setDockerFrom]     = useState(today());
  const [dockerTo,       setDockerTo]       = useState(today());
  const [dockerSearch,   setDockerSearch]   = useState('');
  const [dockerLive,     setDockerLive]     = useState(false);
  const [dockerLoading,  setDockerLoading]  = useState(false);

  // Asterisk state
  const [astEntries,     setAstEntries]     = useState<LogEntry[]>([]);
  const [astChart,       setAstChart]       = useState<ChartData>();
  const [astStatus,      setAstStatus]      = useState<AsteriskStatus | null>(null);
  const [astCats,        setAstCats]        = useState<Set<AstCategory>>(new Set(['REGISTER','CALL','ERROR','DTLS','PJSIP']));
  const [astLines,       setAstLines]       = useState(300);
  const [astSearch,      setAstSearch]      = useState('');
  const [astLive,        setAstLive]        = useState(false);
  const [astLoading,     setAstLoading]     = useState(false);

  const dockerLogRef = useRef<HTMLDivElement>(null);
  const astLogRef    = useRef<HTMLDivElement>(null);
  const dockerEsRef  = useRef<EventSource | null>(null);
  const astEsRef     = useRef<EventSource | null>(null);

  // ── Auto-scroll ─────────────────────────────────────────────────────────────
  const scrollBottom = (ref: React.RefObject<HTMLDivElement | null>) => {
    setTimeout(() => { if (ref.current) ref.current.scrollTop = ref.current.scrollHeight; }, 50);
  };

  // ── Carregar Docker snapshot ─────────────────────────────────────────────────
  const loadDocker = useCallback(async () => {
    setDockerLoading(true);
    try {
      const params: Record<string,string> = {
        services: [...dockerSvcs].join(','),
        lines:    String(dockerLines),
        levels:   [...dockerLevels].join(','),
      };
      let endpoint = '/logs/docker';
      if (dockerSubTab === 'history') {
        endpoint = '/logs/docker/history';
        params.from = dockerFrom; params.to = dockerTo;
      }
      const res = await api.get<{ entries: LogEntry[]; chart?: ChartData }>(endpoint, { params });
      setDockerEntries((res.data.entries ?? []).map(withLogId));
      if (res.data.chart) setDockerChart(res.data.chart);
      scrollBottom(dockerLogRef);
    } catch { /* silencioso */ }
    finally { setDockerLoading(false); }
  }, [dockerSvcs, dockerLevels, dockerLines, dockerSubTab, dockerFrom, dockerTo]);

  // ── Carregar Asterisk snapshot ───────────────────────────────────────────────
  const loadAsterisk = useCallback(async () => {
    setAstLoading(true);
    try {
      const [snapRes, statusRes] = await Promise.all([
        api.get<{ entries: LogEntry[]; chart: ChartData }>('/logs/asterisk', {
          params: { lines: astLines, levels: [...astCats].join(',') },
        }),
        api.get<AsteriskStatus>('/logs/asterisk/status'),
      ]);
      setAstEntries((snapRes.data.entries ?? []).map(withLogId));
      setAstChart(snapRes.data.chart);
      setAstStatus(statusRes.data);
      scrollBottom(astLogRef);
    } catch { /* silencioso */ }
    finally { setAstLoading(false); }
  }, [astLines, astCats]);

  useEffect(() => { loadDocker(); }, [loadDocker]);
  useEffect(() => { if (activeTab === 'asterisk') loadAsterisk(); }, [activeTab, loadAsterisk]);

  // Achado de segurança (débito aceito): o JWT principal (8h) trafegando na
  // query string de EventSource/download ficava exposto em logs de acesso e
  // histórico do browser. EventSource/window.open não permitem header
  // Authorization customizado, então continuam precisando de query string —
  // mas agora é um token de streaming de vida curta (60s), buscado sob
  // demanda a cada conexão/download em vez do token principal de sessão.
  const getStreamingToken = async (): Promise<string> => {
    const { data } = await api.post<{ token: string }>('/auth/streaming-token');
    return data.token;
  };

  // ── SSE Docker ──────────────────────────────────────────────────────────────
  const toggleDockerLive = async () => {
    if (dockerLive) {
      dockerEsRef.current?.close(); dockerEsRef.current = null;
      setDockerLive(false); return;
    }
    const token = await getStreamingToken();
    const svcs  = [...dockerSvcs].join(',');
    const lvls  = [...dockerLevels].join(',');
    const base  = (import.meta.env.VITE_API_URL ?? '').replace('/api/v1','');
    const url   = `${base}/api/v1/logs/docker/stream?services=${svcs}&levels=${lvls}&token=${token}`;
    const es = new EventSource(url);
    es.onmessage = (ev) => {
      try {
        const e: LogEntry = JSON.parse(ev.data);
        setDockerEntries(prev => [...prev.slice(-999), withLogId(e)]);
        scrollBottom(dockerLogRef);
      } catch { /* skip */ }
    };
    dockerEsRef.current = es; setDockerLive(true);
  };

  // ── SSE Asterisk ─────────────────────────────────────────────────────────────
  const toggleAstLive = async () => {
    if (astLive) {
      astEsRef.current?.close(); astEsRef.current = null;
      setAstLive(false); return;
    }
    const token = await getStreamingToken();
    const lvls  = [...astCats].join(',');
    const base  = (import.meta.env.VITE_API_URL ?? '').replace('/api/v1','');
    const url   = `${base}/api/v1/logs/asterisk/stream?levels=${lvls}&token=${token}`;
    const es = new EventSource(url);
    es.onmessage = (ev) => {
      try {
        const e: LogEntry = JSON.parse(ev.data);
        setAstEntries(prev => [...prev.slice(-999), withLogId(e)]);
        scrollBottom(astLogRef);
      } catch { /* skip */ }
    };
    astEsRef.current = es; setAstLive(true);
  };

  // Cleanup SSE on unmount
  useEffect(() => () => { dockerEsRef.current?.close(); astEsRef.current?.close(); }, []);

  // ── Download ─────────────────────────────────────────────────────────────────
  const downloadDocker = async () => {
    const params = new URLSearchParams({
      services: [...dockerSvcs].join(','), lines: String(dockerLines),
      from: dockerFrom, to: dockerTo,
    });
    const token = await getStreamingToken();
    const base  = (import.meta.env.VITE_API_URL ?? '').replace('/api/v1','');
    window.open(`${base}/api/v1/logs/docker/download?${params}&token=${token}`);
  };

  const downloadAsterisk = async () => {
    const token = await getStreamingToken();
    const base  = (import.meta.env.VITE_API_URL ?? '').replace('/api/v1','');
    window.open(`${base}/api/v1/logs/asterisk/download?lines=${astLines}&token=${token}`);
  };

  // ── Filtro local por busca ────────────────────────────────────────────────────
  const filteredDocker  = dockerEntries.filter(e =>
    !dockerSearch || e.msg?.toLowerCase().includes(dockerSearch.toLowerCase()) ||
    e.service?.toLowerCase().includes(dockerSearch.toLowerCase()));

  const filteredAst = astEntries.filter(e =>
    !astSearch || e.msg?.toLowerCase().includes(astSearch.toLowerCase()) ||
    e.category?.toLowerCase().includes(astSearch.toLowerCase()));

  // ── Toggle helpers ────────────────────────────────────────────────────────────
  const toggleSet = <T extends string>(set: Set<T>, val: T): Set<T> => {
    const n = new Set(set); n.has(val) ? n.delete(val) : n.add(val); return n;
  };

  // ─────────────────────────────────────────────────────────────────────────────
  // RENDER
  // ─────────────────────────────────────────────────────────────────────────────

  return (
    <>
      <div className="page-header">
        <h1>🖥️ Logs do Sistema</h1>
        <p>Logs em tempo real e histórico de todos os serviços — Docker e Asterisk</p>
      </div>

      <div className="page-body">

      {/* Tabs principais */}
      <div style={{ display:'flex', borderBottom:'1px solid var(--border-glass)', marginBottom:20 }}>
        {([['docker','🐳 Docker'],['asterisk','☎️ Asterisk']] as const).map(([id,label]) => (
          <button key={id} onClick={() => setActiveTab(id)} style={{
            padding:'10px 20px', background:'none', border:'none', cursor:'pointer',
            fontSize:'0.88rem', fontWeight: activeTab===id ? 600 : 400,
            color: activeTab===id ? 'var(--clr-primary)' : 'var(--text-muted)',
            borderBottom: activeTab===id ? '2px solid var(--clr-primary)' : '2px solid transparent',
          }}>{label}</button>
        ))}
      </div>

      {/* ── DOCKER ─────────────────────────────────────────────────────────── */}
      {activeTab === 'docker' && (
        <div className="card" style={{ padding:0, overflow:'hidden' }}>

          {/* Sub-tabs */}
          <div style={{ display:'flex', borderBottom:'0.5px solid var(--border-glass)', background:'var(--bg-input)' }}>
            {([['live','⚡ Tempo real'],['history','📅 Histórico']] as const).map(([id,label]) => (
              <button key={id} onClick={() => { setDockerSubTab(id); if(dockerLive){toggleDockerLive();} }} style={{
                padding:'8px 16px', background:'none', border:'none', cursor:'pointer',
                fontSize:'0.82rem', fontWeight: dockerSubTab===id ? 600 : 400,
                color: dockerSubTab===id ? 'var(--clr-primary)' : 'var(--text-muted)',
                borderBottom: dockerSubTab===id ? '2px solid var(--clr-primary)' : '2px solid transparent',
              }}>{label}</button>
            ))}
          </div>

          {/* Toolbar */}
          <div style={{ display:'flex', alignItems:'center', gap:8, padding:'10px 14px', borderBottom:'0.5px solid var(--border-glass)', background:'var(--bg-input)', flexWrap:'wrap' }}>
            <input
              aria-label="Filtrar mensagem"
              placeholder="Filtrar mensagem…"
              value={dockerSearch} onChange={e => setDockerSearch(e.target.value)}
              style={{ fontSize:'0.8rem', padding:'4px 10px', borderRadius:6, border:'0.5px solid var(--border-glass)', background:'var(--bg-card)', color:'var(--text-primary)', width:180 }}
            />
            {dockerSubTab === 'history' && (
              <>
                <input type="date" value={dockerFrom} onChange={e => setDockerFrom(e.target.value)}
                  style={{ fontSize:'0.78rem', padding:'3px 7px', borderRadius:5, border:'0.5px solid var(--border-glass)', background:'var(--bg-card)', color:'var(--text-primary)' }} />
                <span style={{ color:'var(--text-muted)', fontSize:'0.78rem' }}>→</span>
                <input type="date" value={dockerTo} onChange={e => setDockerTo(e.target.value)}
                  style={{ fontSize:'0.78rem', padding:'3px 7px', borderRadius:5, border:'0.5px solid var(--border-glass)', background:'var(--bg-card)', color:'var(--text-primary)' }} />
              </>
            )}
            <select value={dockerLines} onChange={e => setDockerLines(Number(e.target.value))}
              style={{ fontSize:'0.78rem', padding:'3px 7px', borderRadius:5, border:'0.5px solid var(--border-glass)', background:'var(--bg-card)', color:'var(--text-primary)' }}>
              {[100,200,500,1000].map(n => <option key={n} value={n}>Últimas {n}</option>)}
            </select>
            <div style={{ marginLeft:'auto', display:'flex', gap:6 }}>
              <button onClick={loadDocker} disabled={dockerLoading} className="btn btn-ghost btn-sm">
                {dockerLoading ? <><span className="spinner" style={{width:10,height:10,marginRight:4}}/>Carregando</> : '🔄 Atualizar'}
              </button>
              <button onClick={downloadDocker} className="btn btn-ghost btn-sm">⬇️ Baixar</button>
              <button onClick={toggleDockerLive} className={`btn btn-sm ${dockerLive ? 'btn-primary' : 'btn-ghost'}`}>
                {dockerLive ? '⏸ Pausar' : '▶ Ao vivo'}
              </button>
            </div>
          </div>

          {/* Filtros serviços */}
          <div style={{ display:'flex', gap:5, padding:'7px 14px', borderBottom:'0.5px solid var(--border-glass)', flexWrap:'wrap', alignItems:'center' }}>
            <span style={{ fontSize:'0.72rem', color:'var(--text-muted)', marginRight:2 }}>Serviços:</span>
            {DOCKER_SERVICES.map(s => (
              <button key={s} onClick={() => setDockerSvcs(toggleSet(dockerSvcs, s))}
                style={{
                  fontSize:'0.72rem', padding:'2px 8px', borderRadius:4, cursor:'pointer',
                  border: dockerSvcs.has(s) ? '1px solid rgba(0,122,255,0.4)' : '0.5px solid var(--border-glass)',
                  background: dockerSvcs.has(s) ? 'rgba(0,122,255,0.08)' : 'var(--bg-card)',
                  color: dockerSvcs.has(s) ? 'var(--clr-primary)' : 'var(--text-muted)',
                }}>{s}</button>
            ))}
            <span style={{ fontSize:'0.72rem', color:'var(--text-muted)', marginLeft:8, marginRight:2 }}>Nível:</span>
            {LOG_LEVELS.map(l => (
              <button key={l} onClick={() => setDockerLevels(toggleSet(dockerLevels, l))}
                style={{
                  fontSize:'0.72rem', padding:'2px 8px', borderRadius:4, cursor:'pointer',
                  border: dockerLevels.has(l) ? `1px solid ${LEVEL_COLORS[l]}55` : '0.5px solid var(--border-glass)',
                  background: dockerLevels.has(l) ? `${LEVEL_BG[l]}` : 'var(--bg-card)',
                  color: dockerLevels.has(l) ? LEVEL_COLORS[l] : 'var(--text-muted)',
                }}>{l}</button>
            ))}
            <span style={{ marginLeft:'auto', fontSize:'0.72rem', color:'var(--text-muted)' }}>
              {filteredDocker.length} entradas
            </span>
          </div>

          {/* Gráfico */}
          {dockerChart && <MiniChart data={dockerChart} />}

          {/* Log */}
          <div ref={dockerLogRef} style={{
            fontFamily:'var(--font-mono,monospace)', fontSize:'0.78rem', lineHeight:1.7,
            padding:'10px 14px', background:'var(--bg-input)',
            maxHeight:420, overflowY:'auto',
          }}>
            {filteredDocker.length === 0 ? (
              <div style={{ color:'var(--text-muted)', textAlign:'center', padding:32 }}>
                {dockerLoading ? 'Carregando…' : 'Nenhum log encontrado.'}
              </div>
            ) : filteredDocker.map((e) => (
              <div key={e._id} style={{ display:'flex', gap:8, alignItems:'baseline', padding:'1px 0', borderBottom:'0.5px solid var(--border-glass)33' }}>
                <span style={{ color:'var(--text-muted)', flexShrink:0, minWidth:58, fontSize:'0.72rem' }}>{fmtTs(e.ts)}</span>
                <span style={{ flexShrink:0, minWidth:68 }}><Badge label={e.service ?? 'sys'} /></span>
                <span style={{ flexShrink:0, minWidth:42 }}><Badge label={e.level} /></span>
                <span style={{ color:'var(--text-primary)', wordBreak:'break-all' }}>{e.msg}</span>
              </div>
            ))}
            {dockerLive && <div style={{ color:'var(--text-muted)', fontSize:'0.72rem' }}>▌</div>}
          </div>
        </div>
      )}

      {/* ── ASTERISK ───────────────────────────────────────────────────────── */}
      {activeTab === 'asterisk' && (
        <div style={{ display:'flex', flexDirection:'column', gap:16 }}>

          {/* Status cards */}
          {astStatus?.ok && (
            <div style={{ display:'grid', gridTemplateColumns:'repeat(auto-fill,minmax(200px,1fr))', gap:12 }}>

              {/* Ramais */}
              <div className="card" style={{ padding:'12px 14px' }}>
                <div style={{ fontSize:'0.72rem', fontWeight:600, color:'var(--text-muted)', marginBottom:8, display:'flex', alignItems:'center', gap:5 }}>
                  🔌 Ramais registrados
                </div>
                {(astStatus.endpoints ?? []).slice(0,5).map(ep => (
                  <div key={ep.name} style={{ display:'flex', justifyContent:'space-between', alignItems:'center', padding:'3px 0', borderBottom:'0.5px solid var(--border-glass)', fontSize:'0.78rem' }}>
                    <span style={{ color:'var(--text-secondary)', display:'flex', alignItems:'center', gap:5 }}>
                      <span style={{ width:6, height:6, borderRadius:'50%', background: ep.status.toLowerCase().includes('avail') ? 'var(--clr-success)' : 'var(--clr-danger)', display:'inline-block' }} />
                      {ep.name}
                    </span>
                    <span style={{ fontFamily:'var(--font-mono)', fontSize:'0.72rem', color: ep.status.toLowerCase().includes('avail') ? '#1a7a3d' : '#b3342f', fontWeight:500 }}>
                      {ep.status}
                    </span>
                  </div>
                ))}
              </div>

              {/* Tronco */}
              <div className="card" style={{ padding:'12px 14px' }}>
                <div style={{ fontSize:'0.72rem', fontWeight:600, color:'var(--text-muted)', marginBottom:8 }}>🌐 Tronco SIP</div>
                <div style={{ display:'flex', justifyContent:'space-between', fontSize:'0.78rem', padding:'3px 0', borderBottom:'0.5px solid var(--border-glass)' }}>
                  <span style={{ color:'var(--text-muted)' }}>{astStatus.trunk?.name}</span>
                  <span style={{ fontWeight:500, color: astStatus.trunk?.status==='Registered'?'#1a7a3d':'#b3342f' }}>
                    {astStatus.trunk?.status}
                  </span>
                </div>
                <div style={{ display:'flex', justifyContent:'space-between', fontSize:'0.78rem', padding:'3px 0' }}>
                  <span style={{ color:'var(--text-muted)' }}>Canais ativos</span>
                  <span style={{ fontWeight:500, fontFamily:'var(--font-mono)' }}>{astStatus.channels ?? 0}</span>
                </div>
              </div>

              {/* Sistema */}
              <div className="card" style={{ padding:'12px 14px' }}>
                <div style={{ fontSize:'0.72rem', fontWeight:600, color:'var(--text-muted)', marginBottom:8 }}>⚙️ Sistema</div>
                <div style={{ display:'flex', justifyContent:'space-between', fontSize:'0.78rem', padding:'3px 0', borderBottom:'0.5px solid var(--border-glass)' }}>
                  <span style={{ color:'var(--text-muted)' }}>Uptime</span>
                  <span style={{ fontFamily:'var(--font-mono)', fontSize:'0.72rem' }}>{astStatus.uptime ?? 'N/A'}</span>
                </div>
                <div style={{ display:'flex', justifyContent:'space-between', fontSize:'0.78rem', padding:'3px 0' }}>
                  <span style={{ color:'var(--text-muted)' }}>Versão</span>
                  <span style={{ fontFamily:'var(--font-mono)', fontSize:'0.72rem' }}>{(astStatus.version ?? 'N/A').slice(0,20)}</span>
                </div>
              </div>

            </div>
          )}

          {/* Log Asterisk */}
          <div className="card" style={{ padding:0, overflow:'hidden' }}>

            {/* Toolbar */}
            <div style={{ display:'flex', alignItems:'center', gap:8, padding:'10px 14px', borderBottom:'0.5px solid var(--border-glass)', background:'var(--bg-input)', flexWrap:'wrap' }}>
              <input
                aria-label="Filtrar evento"
                placeholder="Filtrar evento…"
                value={astSearch} onChange={e => setAstSearch(e.target.value)}
                style={{ fontSize:'0.8rem', padding:'4px 10px', borderRadius:6, border:'0.5px solid var(--border-glass)', background:'var(--bg-card)', color:'var(--text-primary)', width:180 }}
              />
              <select value={astLines} onChange={e => setAstLines(Number(e.target.value))}
                style={{ fontSize:'0.78rem', padding:'3px 7px', borderRadius:5, border:'0.5px solid var(--border-glass)', background:'var(--bg-card)', color:'var(--text-primary)' }}>
                {[200,500,1000,2000].map(n => <option key={n} value={n}>Últimas {n}</option>)}
              </select>
              <div style={{ marginLeft:'auto', display:'flex', gap:6 }}>
                <button onClick={loadAsterisk} disabled={astLoading} className="btn btn-ghost btn-sm">
                  {astLoading ? <><span className="spinner" style={{width:10,height:10,marginRight:4}}/>Carregando</> : '🔄 Atualizar'}
                </button>
                <button onClick={downloadAsterisk} className="btn btn-ghost btn-sm">⬇️ Baixar</button>
                <button onClick={toggleAstLive} className={`btn btn-sm ${astLive ? 'btn-primary' : 'btn-ghost'}`}>
                  {astLive ? '⏸ Pausar' : '▶ Ao vivo'}
                </button>
              </div>
            </div>

            {/* Filtros categoria */}
            <div style={{ display:'flex', gap:5, padding:'7px 14px', borderBottom:'0.5px solid var(--border-glass)', flexWrap:'wrap', alignItems:'center' }}>
              <span style={{ fontSize:'0.72rem', color:'var(--text-muted)', marginRight:2 }}>Categoria:</span>
              {AST_CATEGORIES.map(c => (
                <button key={c} onClick={() => setAstCats(toggleSet(astCats, c))}
                  style={{
                    fontSize:'0.72rem', padding:'2px 8px', borderRadius:4, cursor:'pointer',
                    border: astCats.has(c) ? `1px solid ${LEVEL_COLORS[c] ?? '#007aff'}55` : '0.5px solid var(--border-glass)',
                    background: astCats.has(c) ? (LEVEL_BG[c] ?? 'rgba(0,122,255,0.08)') : 'var(--bg-card)',
                    color: astCats.has(c) ? (LEVEL_COLORS[c] ?? 'var(--clr-primary)') : 'var(--text-muted)',
                  }}>{c}</button>
              ))}
              <span style={{ marginLeft:'auto', fontSize:'0.72rem', color:'var(--text-muted)' }}>
                {filteredAst.length} entradas
              </span>
            </div>

            {/* Gráfico */}
            {astChart && <MiniChart data={astChart} />}

            {/* Log */}
            <div ref={astLogRef} style={{
              fontFamily:'var(--font-mono,monospace)', fontSize:'0.78rem', lineHeight:1.7,
              padding:'10px 14px', background:'var(--bg-input)',
              maxHeight:460, overflowY:'auto',
            }}>
              {filteredAst.length === 0 ? (
                <div style={{ color:'var(--text-muted)', textAlign:'center', padding:32 }}>
                  {astLoading ? 'Carregando…' : 'Nenhum log encontrado.'}
                </div>
              ) : filteredAst.map((e) => (
                <div key={e._id} style={{ display:'flex', gap:8, alignItems:'baseline', padding:'2px 0', borderBottom:'0.5px solid var(--border-glass)33' }}>
                  <span style={{ color:'var(--text-muted)', flexShrink:0, fontSize:'0.72rem', minWidth:100 }}>{e.ts}</span>
                  <span style={{ flexShrink:0, minWidth:64 }}><Badge label={e.category ?? e.level ?? 'INFO'} /></span>
                  <span style={{ color:'var(--text-primary)', wordBreak:'break-all' }}>{e.msg}</span>
                </div>
              ))}
              {astLive && <div style={{ color:'var(--text-muted)', fontSize:'0.72rem' }}>▌</div>}
            </div>
          </div>
        </div>
      )}
      </div>
    </>
  );
}
