import { useEffect, useState } from 'react';
import { MessageSquare, Send, FlaskConical } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import type { CcChatSession, CcChatMessage, CcCannedResponse, CcDisposition, CcQueue } from '../api/types';

const POLL_INTERVAL_MS = 4000;

interface ChatTabProps {
  isAdmin: boolean;
}

/**
 * ChatTab — sub-fase 7a do canal de chat (base interna, sem widget público ainda — a
 * autenticação anônima do cliente final fica pra Fase 7b, desenhada com calma). Atualização em
 * tempo real via polling: o backend já publica eventos em /topic/callcenter/chat/** via STOMP,
 * mas esta SPA ainda não tem um client STOMP genérico (diferente do broker do Telecom) — criar
 * um só pra esta tela seria overengineering nesta fatia, então segue o mesmo padrão de polling
 * já usado em DesktopAgenteTab.tsx.
 *
 * Seleção de fila: não existe ainda um endpoint "minhas filas" — usa o catálogo geral
 * (GET /callcenter/filas), com fallback silencioso caso o agente não tenha
 * PERM_READ_callcenter.filas (comum: quem só atende chat pode não ter acesso à administração
 * de filas). Fica registrado como simplificação aceita para esta fatia.
 */
export function ChatTab({ isAdmin }: ChatTabProps) {
  const [queues, setQueues] = useState<CcQueue[]>([]);
  const [selectedQueueId, setSelectedQueueId] = useState<number | ''>('');
  const [waiting, setWaiting] = useState<CcChatSession[]>([]);
  const [mine, setMine] = useState<CcChatSession[]>([]);
  const [activeSessionId, setActiveSessionId] = useState<number | null>(null);
  const [messages, setMessages] = useState<CcChatMessage[]>([]);
  const [draft, setDraft] = useState('');
  const [canned, setCanned] = useState<CcCannedResponse[]>([]);
  const [dispositions, setDispositions] = useState<CcDisposition[]>([]);
  const [selectedDisposition, setSelectedDisposition] = useState<number | ''>('');
  const [error, setError] = useState('');

  // Simulador de cliente (dev, ADMIN only)
  const [simCustomerRef, setSimCustomerRef] = useState('');
  const [simCustomerName, setSimCustomerName] = useState('');
  const [simText, setSimText] = useState('');
  const [simSessionId, setSimSessionId] = useState<number | null>(null);

  useEffect(() => {
    api.get<CcQueue[]>('/callcenter/filas').then(({ data }) => setQueues(data)).catch(() => setQueues([]));
    api.get<CcCannedResponse[]>('/callcenter/chat/canned-responses').then(({ data }) => setCanned(data)).catch(() => setCanned([]));
    api.get<CcDisposition[]>('/callcenter/interactions/dispositions').then(({ data }) => setDispositions(data)).catch(() => setDispositions([]));
  }, []);

  const loadWaiting = (queueId: number) => {
    api.get<CcChatSession[]>(`/callcenter/chat/queue/${queueId}`)
      .then(({ data }) => setWaiting(data))
      .catch(() => setWaiting([]));
  };

  const loadMine = () => {
    api.get<CcChatSession[]>('/callcenter/chat/mine')
      .then(({ data }) => setMine(data))
      .catch(() => setMine([]));
  };

  useEffect(() => {
    if (selectedQueueId === '') return;
    loadWaiting(selectedQueueId);
    const id = setInterval(() => loadWaiting(selectedQueueId), POLL_INTERVAL_MS);
    return () => clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedQueueId]);

  useEffect(() => {
    loadMine();
    const id = setInterval(loadMine, POLL_INTERVAL_MS);
    return () => clearInterval(id);
  }, []);

  const loadMessages = (sessionId: number) => {
    api.get<CcChatMessage[]>(`/callcenter/chat/${sessionId}/messages`)
      .then(({ data }) => setMessages(data))
      .catch(() => setMessages([]));
  };

  useEffect(() => {
    if (activeSessionId == null) return;
    loadMessages(activeSessionId);
    const id = setInterval(() => loadMessages(activeSessionId), POLL_INTERVAL_MS);
    return () => clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeSessionId]);

  const claim = (sessionId: number) => {
    setError('');
    api.post(`/callcenter/chat/${sessionId}/claim`)
      .then(() => { loadMine(); if (selectedQueueId !== '') loadWaiting(selectedQueueId); setActiveSessionId(sessionId); })
      .catch(err => setError(getErrorMessage(err, 'Erro ao assumir a conversa.')));
  };

  const sendMessage = () => {
    if (activeSessionId == null || !draft.trim()) return;
    setError('');
    api.post(`/callcenter/chat/${activeSessionId}/messages`, { text: draft })
      .then(() => { setDraft(''); loadMessages(activeSessionId); })
      .catch(err => setError(getErrorMessage(err, 'Erro ao enviar mensagem.')));
  };

  const closeSession = () => {
    if (activeSessionId == null) return;
    setError('');
    api.post(`/callcenter/chat/${activeSessionId}/close`, { dispositionId: selectedDisposition === '' ? null : selectedDisposition })
      .then(() => { setActiveSessionId(null); setSelectedDisposition(''); loadMine(); })
      .catch(err => setError(getErrorMessage(err, 'Erro ao encerrar a conversa.')));
  };

  // ---- Simulador de cliente (dev, ADMIN only) ----
  const startSimulatedSession = () => {
    if (selectedQueueId === '' || !simCustomerRef.trim()) return;
    setError('');
    api.post('/callcenter/chat/test/sessions', {
      channelCode: 'internal_test', queueId: selectedQueueId, customerRef: simCustomerRef, customerName: simCustomerName || undefined,
    })
      .then(({ data }: { data: CcChatSession }) => { setSimSessionId(data.id); loadWaiting(selectedQueueId as number); })
      .catch(err => setError(getErrorMessage(err, 'Erro ao iniciar sessão simulada.')));
  };

  const sendSimulatedMessage = () => {
    if (simSessionId == null || !simText.trim()) return;
    setError('');
    api.post(`/callcenter/chat/test/sessions/${simSessionId}/messages`, { text: simText })
      .then(() => { setSimText(''); if (activeSessionId === simSessionId) loadMessages(simSessionId); })
      .catch(err => setError(getErrorMessage(err, 'Erro ao enviar mensagem simulada.')));
  };

  return (
    <>
      <div className="page-header">
        <h1>Chat</h1>
        <p>Fila de conversas, atendimento e respostas rápidas (base interna — Fase 7a)</p>
      </div>
      <div className="page-body">
        {error && <div className="alert alert-error" style={{ marginBottom: 16 }}>{error}</div>}

        <div className="card" style={{ marginBottom: 16 }}>
          <div className="flex items-center" style={{ gap: 8 }}>
            <span>Fila:</span>
            <select className="form-input" style={{ width: 250 }} value={selectedQueueId}
              onChange={e => setSelectedQueueId(e.target.value ? Number(e.target.value) : '')}>
              <option value="">— Selecione a fila —</option>
              {queues.map(q => <option key={q.id} value={q.id}>{q.displayName}</option>)}
            </select>
          </div>
        </div>

        <div className="flex" style={{ gap: 16, alignItems: 'flex-start', flexWrap: 'wrap' }}>
          <div className="card" style={{ flex: '1 1 260px', minWidth: 260 }}>
            <strong>Aguardando na fila</strong>
            {selectedQueueId === '' ? (
              <p style={{ color: 'var(--text-muted)', marginTop: 8 }}>Selecione uma fila.</p>
            ) : waiting.length === 0 ? (
              <p style={{ color: 'var(--text-muted)', marginTop: 8 }}>Nenhuma conversa aguardando.</p>
            ) : (
              <ul style={{ listStyle: 'none', padding: 0, marginTop: 8 }}>
                {waiting.map(s => (
                  <li key={s.id} className="flex items-center justify-between" style={{ padding: '6px 0', borderBottom: '1px solid var(--border)' }}>
                    <span>{s.customerName ?? s.customerRef}</span>
                    <button className="btn btn-primary btn-sm" onClick={() => claim(s.id)}>Assumir</button>
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div className="card" style={{ flex: '1 1 260px', minWidth: 260 }}>
            <strong>Minhas conversas</strong>
            {mine.length === 0 ? (
              <p style={{ color: 'var(--text-muted)', marginTop: 8 }}>Nenhuma conversa ativa.</p>
            ) : (
              <ul style={{ listStyle: 'none', padding: 0, marginTop: 8 }}>
                {mine.map(s => (
                  <li key={s.id} style={{ padding: '6px 0', borderBottom: '1px solid var(--border)' }}>
                    <button className={`btn btn-ghost btn-sm${activeSessionId === s.id ? ' active' : ''}`} onClick={() => setActiveSessionId(s.id)}>
                      <MessageSquare size={14} /> {s.customerName ?? s.customerRef}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div className="card" style={{ flex: '2 1 400px', minWidth: 320 }}>
            <strong>Conversa</strong>
            {activeSessionId == null ? (
              <p style={{ color: 'var(--text-muted)', marginTop: 8 }}>Selecione uma conversa em "Minhas conversas".</p>
            ) : (
              <>
                <div style={{ maxHeight: 300, overflowY: 'auto', margin: '12px 0', display: 'flex', flexDirection: 'column', gap: 6 }}>
                  {messages.map(m => (
                    <div key={m.id} style={{ alignSelf: m.senderType === 'agent' ? 'flex-end' : 'flex-start', maxWidth: '80%' }}>
                      <div style={{ fontSize: '.75rem', color: 'var(--text-muted)' }}>{m.senderName ?? m.senderType}</div>
                      <div className="card" style={{ padding: '6px 10px', margin: 0 }}>{m.body}</div>
                    </div>
                  ))}
                </div>
                {canned.length > 0 && (
                  <select className="form-input" style={{ marginBottom: 8 }}
                    onChange={e => { const c = canned.find(x => String(x.id) === e.target.value); if (c) setDraft(c.body); e.target.value = ''; }}>
                    <option value="">— Resposta rápida —</option>
                    {canned.map(c => <option key={c.id} value={c.id}>{c.title}</option>)}
                  </select>
                )}
                <div className="flex" style={{ gap: 8 }}>
                  <textarea className="form-input" style={{ flex: 1 }} rows={2} value={draft}
                    onChange={e => setDraft(e.target.value)} placeholder="Digite sua mensagem..." />
                  <button className="btn btn-primary btn-sm" onClick={sendMessage} disabled={!draft.trim()}>
                    <Send size={14} /> Enviar
                  </button>
                </div>
                <div className="flex items-center" style={{ gap: 8, marginTop: 12 }}>
                  <select className="form-input" style={{ width: 220 }} value={selectedDisposition}
                    onChange={e => setSelectedDisposition(e.target.value ? Number(e.target.value) : '')}>
                    <option value="">— Tabulação (opcional) —</option>
                    {dispositions.map(d => <option key={d.id} value={d.id}>{d.label}</option>)}
                  </select>
                  <button className="btn btn-ghost btn-sm" onClick={closeSession}>Encerrar</button>
                </div>
              </>
            )}
          </div>
        </div>

        {isAdmin && (
          <div className="card" style={{ marginTop: 16, background: '#fff8db', border: '1px solid #e6c200' }}>
            <div className="flex items-center" style={{ gap: 8 }}>
              <FlaskConical size={16} />
              <strong>Simulador de cliente (dev)</strong>
            </div>
            <p style={{ fontSize: '.8rem', margin: '4px 0 12px' }}>
              Ferramenta de teste — não é o widget real. Usada só pra validar o pipeline de chat antes do canal público (Fase 7b).
            </p>
            {simSessionId == null ? (
              <div className="flex items-center" style={{ gap: 8, flexWrap: 'wrap' }}>
                <input className="form-input" style={{ width: 200 }} placeholder="Identificador do contato (ex: telefone)"
                  value={simCustomerRef} onChange={e => setSimCustomerRef(e.target.value)} />
                <input className="form-input" style={{ width: 200 }} placeholder="Nome (opcional)"
                  value={simCustomerName} onChange={e => setSimCustomerName(e.target.value)} />
                <button className="btn btn-primary btn-sm" onClick={startSimulatedSession}
                  disabled={selectedQueueId === '' || !simCustomerRef.trim()}>
                  Iniciar conversa simulada
                </button>
              </div>
            ) : (
              <div className="flex items-center" style={{ gap: 8 }}>
                <span>Sessão simulada #{simSessionId} —</span>
                <input className="form-input" style={{ flex: 1 }} placeholder="Mensagem do cliente simulado"
                  value={simText} onChange={e => setSimText(e.target.value)} />
                <button className="btn btn-ghost btn-sm" onClick={sendSimulatedMessage} disabled={!simText.trim()}>Enviar</button>
                <button className="btn btn-ghost btn-sm" onClick={() => setSimSessionId(null)}>Nova sessão</button>
              </div>
            )}
          </div>
        )}
      </div>
    </>
  );
}
