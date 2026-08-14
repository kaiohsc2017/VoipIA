import { useEffect, useRef, useState } from 'react';
import { MessageSquare, Send, FlaskConical, Bot, Paperclip, Download, Trash2 } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import type {
  CcChatSession, CcChatMessage, CcCannedResponse, CcDisposition, CcQueue,
  ChatChannelView, ChatChannelRequest, FlowView, CcChatAttachment, CcChatAttachmentExtension,
} from '../api/types';

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

  // Canais (Fase 24, ADMIN only — configuração, não atendimento)
  const [channels, setChannels] = useState<ChatChannelView[]>([]);
  const [chatFlows, setChatFlows] = useState<FlowView[]>([]);
  const [editingChannel, setEditingChannel] = useState<ChatChannelView | null>(null);
  const [channelForm, setChannelForm] = useState<ChatChannelRequest>({ code: '', displayName: '', type: 'webchat', active: true });

  // Anexos (Fase 7d) — bidirecional, allowlist de extensão configurável, cota/retenção por canal.
  const [attachments, setAttachments] = useState<CcChatAttachment[]>([]);
  const [uploadingAttachment, setUploadingAttachment] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [extensions, setExtensions] = useState<CcChatAttachmentExtension[]>([]);
  const [newExtension, setNewExtension] = useState('');

  const loadExtensions = () => {
    api.get<CcChatAttachmentExtension[]>('/callcenter/chat/attachment-extensions')
      .then(({ data }) => setExtensions(data)).catch(() => setExtensions([]));
  };

  const addExtension = () => {
    if (!newExtension.trim()) return;
    api.post('/callcenter/chat/attachment-extensions', { extension: newExtension.trim() })
      .then(() => { setNewExtension(''); loadExtensions(); })
      .catch(err => setError(getErrorMessage(err, 'Erro ao cadastrar extensão.')));
  };

  const removeExtension = (id: number) => {
    api.delete(`/callcenter/chat/attachment-extensions/${id}`).then(loadExtensions)
      .catch(err => setError(getErrorMessage(err, 'Erro ao remover extensão.')));
  };

  const loadChannels = () => {
    api.get<ChatChannelView[]>('/callcenter/chat/channels').then(({ data }) => setChannels(data)).catch(() => setChannels([]));
  };

  useEffect(() => {
    api.get<CcQueue[]>('/callcenter/filas').then(({ data }) => setQueues(data)).catch(() => setQueues([]));
    api.get<CcCannedResponse[]>('/callcenter/chat/canned-responses').then(({ data }) => setCanned(data)).catch(() => setCanned([]));
    api.get<CcDisposition[]>('/callcenter/interactions/dispositions').then(({ data }) => setDispositions(data)).catch(() => setDispositions([]));
    if (isAdmin) {
      loadChannels();
      loadExtensions();
      api.get<FlowView[]>('/callcenter/fluxos')
        .then(({ data }) => setChatFlows(data.filter(f => f.channel === 'chat' || f.channel === 'both')))
        .catch(() => setChatFlows([]));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAdmin]);

  const startEditChannel = (channel: ChatChannelView | null) => {
    setEditingChannel(channel);
    setChannelForm(channel
      ? {
          code: channel.code, displayName: channel.displayName, type: channel.type,
          defaultQueueId: channel.defaultQueueId ?? null, botFlowId: channel.botFlowId ?? null,
          greetingMessage: channel.greetingMessage ?? '', awayMessage: channel.awayMessage ?? '', active: channel.active,
          attachmentQuotaBytes: channel.attachmentQuotaBytes, attachmentRetentionDays: channel.attachmentRetentionDays,
        }
      : { code: '', displayName: '', type: 'webchat', active: true });
  };

  const saveChannel = () => {
    if (!channelForm.code.trim() || !channelForm.displayName.trim()) return;
    setError('');
    const request = editingChannel
      ? api.put(`/callcenter/chat/channels/${editingChannel.id}`, channelForm)
      : api.post('/callcenter/chat/channels', channelForm);
    request
      .then(() => { setEditingChannel(null); setChannelForm({ code: '', displayName: '', type: 'webchat', active: true }); loadChannels(); })
      .catch(err => setError(getErrorMessage(err, 'Erro ao salvar o canal.')));
  };

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

  const loadAttachments = (sessionId: number) => {
    api.get<CcChatAttachment[]>(`/callcenter/chat/${sessionId}/attachments`)
      .then(({ data }) => setAttachments(data))
      .catch(() => setAttachments([]));
  };

  useEffect(() => {
    if (activeSessionId == null) return;
    loadMessages(activeSessionId);
    loadAttachments(activeSessionId);
    const id = setInterval(() => { loadMessages(activeSessionId); loadAttachments(activeSessionId); }, POLL_INTERVAL_MS);
    return () => clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeSessionId]);

  const uploadAttachment = () => {
    const file = fileInputRef.current?.files?.[0];
    if (activeSessionId == null || !file) return;
    setError('');
    setUploadingAttachment(true);
    const form = new FormData();
    form.append('file', file);
    api.post(`/callcenter/chat/${activeSessionId}/attachments`, form, { headers: { 'Content-Type': 'multipart/form-data' } })
      .then(() => { if (fileInputRef.current) fileInputRef.current.value = ''; loadAttachments(activeSessionId); })
      .catch(err => setError(getErrorMessage(err, 'Erro ao enviar anexo.')))
      .finally(() => setUploadingAttachment(false));
  };

  const downloadAttachment = (attachment: CcChatAttachment) => {
    api.get(`/callcenter/chat/attachments/${attachment.id}/download`, { responseType: 'blob' })
      .then(({ data }) => {
        const url = window.URL.createObjectURL(data as Blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = attachment.originalFileName;
        a.click();
        window.URL.revokeObjectURL(url);
      })
      .catch(err => setError(getErrorMessage(err, 'Erro ao baixar anexo.')));
  };

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
                {attachments.length > 0 && (
                  <div style={{ margin: '8px 0' }}>
                    <div style={{ fontSize: '.75rem', color: 'var(--text-muted)', marginBottom: 4 }}>Anexos</div>
                    <ul style={{ listStyle: 'none', padding: 0, display: 'flex', flexDirection: 'column', gap: 4 }}>
                      {attachments.map(a => (
                        <li key={a.id} className="flex items-center justify-between" style={{ fontSize: '.8rem' }}>
                          <span>{a.originalFileName} <span style={{ color: 'var(--text-muted)' }}>({a.senderName ?? a.senderType})</span></span>
                          <button className="btn btn-ghost btn-sm" onClick={() => downloadAttachment(a)} title="Baixar anexo">
                            <Download size={14} />
                          </button>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
                <div className="flex items-center" style={{ gap: 8, marginBottom: 8 }}>
                  <input ref={fileInputRef} type="file" aria-label="Selecionar arquivo para anexar" style={{ maxWidth: 220 }} />
                  <button className="btn btn-ghost btn-sm" onClick={uploadAttachment} disabled={uploadingAttachment} title="Enviar anexo">
                    <Paperclip size={14} /> {uploadingAttachment ? 'Enviando…' : 'Anexar'}
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
          <div className="card" style={{ marginTop: 16 }}>
            <div className="flex items-center" style={{ gap: 8 }}>
              <Bot size={16} />
              <strong>Canais (Fase 24)</strong>
            </div>
            <p style={{ fontSize: '.8rem', margin: '4px 0 12px' }}>
              Cada canal define a fila padrão (substitui a variável de ambiente única que o widget interno usava)
              e, opcionalmente, um fluxo de bot do Flow Builder (canal "chat") que atende antes de chegar a um agente humano.
            </p>
            <ul style={{ listStyle: 'none', padding: 0, marginBottom: 12 }}>
              {channels.map(c => (
                <li key={c.id} className="flex items-center justify-between" style={{ padding: '6px 0', borderBottom: '1px solid var(--border)' }}>
                  <span>
                    <strong>{c.displayName}</strong> ({c.code}) — fila: {c.defaultQueueName ?? '— nenhuma —'}
                    {c.botFlowName && <> — bot: {c.botFlowName}</>}
                    {!c.active && <span className="badge badge-gray" style={{ marginLeft: 8 }}>inativo</span>}
                  </span>
                  <button className="btn btn-ghost btn-sm" onClick={() => startEditChannel(c)}>Editar</button>
                </li>
              ))}
              {channels.length === 0 && <p style={{ color: 'var(--text-muted)' }}>Nenhum canal cadastrado.</p>}
            </ul>
            <div className="flex items-center" style={{ gap: 8, flexWrap: 'wrap' }}>
              <input className="form-input" style={{ width: 140 }} placeholder="Código (ex: webchat)"
                aria-label="Código do canal"
                value={channelForm.code} onChange={e => setChannelForm({ ...channelForm, code: e.target.value })} />
              <input className="form-input" style={{ width: 200 }} placeholder="Nome de exibição"
                aria-label="Nome de exibição do canal"
                value={channelForm.displayName} onChange={e => setChannelForm({ ...channelForm, displayName: e.target.value })} />
              <select className="form-input" style={{ width: 200 }} aria-label="Fila padrão do canal" value={channelForm.defaultQueueId ?? ''}
                onChange={e => setChannelForm({ ...channelForm, defaultQueueId: e.target.value ? Number(e.target.value) : null })}>
                <option value="">— Fila padrão —</option>
                {queues.map(q => <option key={q.id} value={q.id}>{q.displayName}</option>)}
              </select>
              <select className="form-input" style={{ width: 200 }} aria-label="Fluxo de bot do canal" value={channelForm.botFlowId ?? ''}
                onChange={e => setChannelForm({ ...channelForm, botFlowId: e.target.value ? Number(e.target.value) : null })}>
                <option value="">— Sem fluxo de bot —</option>
                {chatFlows.map(f => <option key={f.id} value={f.id}>{f.name}</option>)}
              </select>
              <textarea className="form-input" style={{ width: '100%', marginTop: 8 }} rows={2}
                placeholder="Mensagem de saudação (opcional) — exibida ao cliente ao abrir o chat"
                aria-label="Mensagem de saudação do canal"
                value={channelForm.greetingMessage ?? ''}
                onChange={e => setChannelForm({ ...channelForm, greetingMessage: e.target.value })} />
              <textarea className="form-input" style={{ width: '100%' }} rows={2}
                placeholder="Mensagem de ausência (opcional) — fora do horário/sem bot associado"
                aria-label="Mensagem de ausência do canal"
                value={channelForm.awayMessage ?? ''}
                onChange={e => setChannelForm({ ...channelForm, awayMessage: e.target.value })} />
              <div className="flex items-center" style={{ gap: 8, width: '100%', marginTop: 4 }}>
                <label htmlFor="chat-attachment-quota" style={{ fontSize: '.8rem' }}>Cota de anexos por usuário (MB)</label>
                <input id="chat-attachment-quota" type="number" min={1} className="form-input" style={{ width: 100 }}
                  value={channelForm.attachmentQuotaBytes != null ? Math.round(channelForm.attachmentQuotaBytes / (1024 * 1024)) : 2048}
                  onChange={e => setChannelForm({ ...channelForm, attachmentQuotaBytes: Number(e.target.value) * 1024 * 1024 })} />
                <label htmlFor="chat-attachment-retention" style={{ fontSize: '.8rem' }}>Retenção (dias)</label>
                <input id="chat-attachment-retention" type="number" min={1} className="form-input" style={{ width: 80 }}
                  value={channelForm.attachmentRetentionDays ?? 10}
                  onChange={e => setChannelForm({ ...channelForm, attachmentRetentionDays: Number(e.target.value) })} />
              </div>
              <button className="btn btn-primary btn-sm" onClick={saveChannel}
                disabled={!channelForm.code.trim() || !channelForm.displayName.trim()}>
                {editingChannel ? 'Salvar' : 'Criar canal'}
              </button>
              {editingChannel && <button className="btn btn-ghost btn-sm" onClick={() => startEditChannel(null)}>Cancelar</button>}
            </div>
          </div>
        )}

        {isAdmin && (
          <div className="card" style={{ marginTop: 16 }}>
            <div className="flex items-center" style={{ gap: 8 }}>
              <Paperclip size={16} />
              <strong>Extensões de anexo aceitas (Fase 7d)</strong>
            </div>
            <p style={{ fontSize: '.8rem', margin: '4px 0 12px' }}>
              Nenhum anexo é aceito no chat até que ao menos uma extensão esteja cadastrada aqui.
            </p>
            <ul style={{ listStyle: 'none', padding: 0, marginBottom: 12, display: 'flex', flexWrap: 'wrap', gap: 8 }}>
              {extensions.map(e => (
                <li key={e.id} className="badge badge-gray flex items-center" style={{ gap: 6 }}>
                  .{e.extension}
                  <button className="btn btn-ghost btn-sm" style={{ padding: 0 }} onClick={() => removeExtension(e.id)} title="Remover extensão">
                    <Trash2 size={12} />
                  </button>
                </li>
              ))}
              {extensions.length === 0 && <p style={{ color: 'var(--text-muted)' }}>Nenhuma extensão cadastrada.</p>}
            </ul>
            <div className="flex items-center" style={{ gap: 8 }}>
              <input className="form-input" style={{ width: 140 }} placeholder="ex: pdf"
                aria-label="Nova extensão de anexo"
                value={newExtension} onChange={e => setNewExtension(e.target.value)} />
              <button className="btn btn-primary btn-sm" onClick={addExtension} disabled={!newExtension.trim()}>Adicionar</button>
            </div>
          </div>
        )}

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
