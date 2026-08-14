/**
 * Widget de chat público do Call Center (Fase 7b do módulo Call Center — base interna da
 * autenticação anônima já implementada na Fase 7a/7b, ver
 * .claude/plans/modulo-callcenter-omnicanal.plan.md). JS puro, sem framework nem dependências,
 * pra poder ser embutido em qualquer site com uma única tag <script>.
 *
 * Como incluir:
 *   <script src="https://app.voiphash.com.br/widget/callcenter-chat-widget.js"
 *           data-api-base="https://app.voiphash.com.br/api/v1"></script>
 *   (data-api-base é opcional — sem ele, o widget usa location.origin + '/api/v1', o que só
 *   funciona se o widget estiver embutido no mesmo domínio do backend.)
 *
 * Limitações conhecidas desta v1 (fora de escopo desta fatia, ver plano — Fase 7 completa):
 *   - Sem retomada de conversa após reload da página (o token/sessão fica só em memória).
 *   - Sem anexos, sem indicador de digitação, sem WebSocket (atualização por polling a cada 3s).
 *   - Sessão só é criada no envio da primeira mensagem, não ao simplesmente abrir o painel.
 *
 * Fase 17a (co-browsing gravado do chat): banner de consentimento distinto do de gravação de
 * voz, mostrado uma única vez por sessão assim que a primeira mensagem do agente chega — só
 * nesse momento existe uma CcCobrowseSession pendente (criada no claim, se o agente tiver o
 * toggle ligado). Recusar (ou nunca decidir) não afeta o chat em nada; 404 do backend (nenhum
 * agente com o toggle ligado atendeu esta conversa) é tratado como silêncio total — nunca
 * aparece como erro.
 *
 * Fase 17b (captura real via rrweb): SÓ inicia depois do aceite explícito (nunca antes — o
 * script do rrweb nem é carregado até esse momento). Vendorizado localmente em
 * vendor/rrweb-record.min.js (pacote oficial @rrweb/record v2.1.1, MIT — carregado sob demanda
 * via <script>, nunca de CDN externo). Captura sem mascaramento — tudo que aparece na tela do
 * colaborador durante a captura é gravado (decisão explícita do usuário: visibilidade total).
 * Falha da captura (rrweb indisponível, erro de rede) nunca quebra o chat — é sempre best-effort.
 */
(function () {
    'use strict';

    var currentScript = document.currentScript;
    var API_BASE = (currentScript && currentScript.dataset.apiBase) || (location.origin + '/api/v1');
    var CUSTOMER_REF_KEY = 'asteriskia_cc_chat_ref';
    var POLL_INTERVAL_MS = 3000;
    var POLL_MAX_BACKOFF_MS = 30000;

    // Fase 17a — texto fixo exibido no banner; o hash SHA-256 dele é o que trafega no
    // consentimento (nunca o texto em si), pra o backend nunca precisar guardar/validar texto
    // livre. Mudar este texto muda o hash — decisão consciente (nova aceitação é esperada).
    var COBROWSE_CONSENT_TEXT =
        'Para ajudar no seu atendimento, o atendente pode visualizar sua tela nesta conversa ' +
        '(sem controle remoto). Você pode recusar ou parar a qualquer momento sem afetar o chat.';

    // Fase 17b — caminho do build vendorizado do rrweb, resolvido em runtime a partir do próprio
    // <script src> deste widget (nunca hardcoded pra um domínio fixo, nem carregado de CDN
    // externa — evita quebrar se o widget for embutido com outro data-api-base/domínio).
    var RRWEB_VENDOR_PATH = 'vendor/rrweb-record.min.js';

    // Fase 17b, item 3 do plano — lote: flush a cada 5s OU ao atingir ~64KB (o que vier
    // primeiro), o que vier primeiro. Decisão sobre compressão (D do plano): o cliente NÃO
    // comprime (evita depender de CompressionStream, indisponível em Safari mais antigo, e
    // mantém o widget simples) — a compressão acontece só no backend, no momento de persistir em
    // disco (.jsonl.gz). O corpo trafega em JSON puro, sujeito ao teto de 512KB do endpoint.
    var COBROWSE_FLUSH_INTERVAL_MS = 5000;
    var COBROWSE_FLUSH_MAX_BYTES = 64 * 1024;

    var state = {
        sessionId: null,
        token: null,
        pollTimer: null,
        pollBackoffMs: POLL_INTERVAL_MS,
        lastMessageCount: 0,
        lastAttachmentCount: 0,
        cobrowseConsentShown: false,
        cobrowseCapturing: false,
        cobrowseStopFn: null,
        cobrowseBuffer: [],
        cobrowseBufferBytes: 0,
        cobrowseSeq: 0,
        cobrowseFlushTimer: null,
    };

    function getCustomerRef() {
        var ref = localStorage.getItem(CUSTOMER_REF_KEY);
        if (!ref) {
            ref = (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random());
            localStorage.setItem(CUSTOMER_REF_KEY, ref);
        }
        return ref;
    }

    function injectStyles() {
        var style = document.createElement('style');
        style.textContent = [
            '#cc-chat-widget-button{position:fixed;bottom:20px;right:20px;width:56px;height:56px;',
            'border-radius:50%;background:#2563eb;color:#fff;border:none;cursor:pointer;',
            'font-size:24px;box-shadow:0 2px 8px rgba(0,0,0,.3);z-index:999999;}',
            '#cc-chat-widget-panel{position:fixed;bottom:88px;right:20px;width:320px;height:420px;',
            'background:#fff;border-radius:12px;box-shadow:0 4px 20px rgba(0,0,0,.25);',
            'display:none;flex-direction:column;overflow:hidden;z-index:999999;',
            'font-family:system-ui,sans-serif;font-size:14px;}',
            '#cc-chat-widget-panel.open{display:flex;}',
            '#cc-chat-widget-header{background:#2563eb;color:#fff;padding:12px;font-weight:600;}',
            '#cc-chat-widget-messages{flex:1;overflow-y:auto;padding:10px;background:#f3f4f6;}',
            '.cc-chat-msg{margin-bottom:8px;max-width:80%;padding:8px 10px;border-radius:8px;',
            'word-wrap:break-word;}',
            '.cc-chat-msg.customer{background:#2563eb;color:#fff;margin-left:auto;}',
            '.cc-chat-msg.agent,.cc-chat-msg.system{background:#e5e7eb;color:#111;margin-right:auto;}',
            '#cc-chat-widget-form{display:flex;border-top:1px solid #e5e7eb;}',
            '#cc-chat-widget-input{flex:1;border:none;padding:10px;font-size:14px;outline:none;}',
            '#cc-chat-widget-send{border:none;background:#2563eb;color:#fff;padding:0 16px;',
            'cursor:pointer;}',
            '#cc-chat-widget-cobrowse-consent{background:#fffbeb;border-bottom:1px solid #fde68a;',
            'padding:8px 10px;font-size:12px;color:#92400e;}',
            '#cc-chat-widget-cobrowse-consent .cc-cb-actions{margin-top:6px;display:flex;gap:8px;}',
            '#cc-chat-widget-cobrowse-consent button{font-size:12px;padding:4px 10px;border-radius:6px;',
            'border:1px solid #d97706;cursor:pointer;background:#fff;}',
            '#cc-chat-widget-cobrowse-consent button.cc-cb-accept{background:#d97706;color:#fff;}',
            '#cc-chat-widget-cobrowse-indicator{background:#fee2e2;border-bottom:1px solid #fca5a5;',
            'padding:6px 10px;font-size:11px;color:#991b1b;display:none;align-items:center;',
            'justify-content:space-between;gap:8px;}',
            '#cc-chat-widget-cobrowse-indicator.active{display:flex;}',
            '#cc-chat-widget-cobrowse-indicator button{font-size:11px;padding:2px 8px;',
            'border-radius:6px;border:1px solid #991b1b;background:#fff;color:#991b1b;',
            'cursor:pointer;}',
        ].join('');
        document.head.appendChild(style);
    }

    function buildUi() {
        var button = document.createElement('button');
        button.id = 'cc-chat-widget-button';
        button.type = 'button';
        button.textContent = '💬';
        button.setAttribute('aria-label', 'Fale conosco');

        var panel = document.createElement('div');
        panel.id = 'cc-chat-widget-panel';
        panel.innerHTML =
            '<div id="cc-chat-widget-header">Fale conosco</div>' +
            '<div id="cc-chat-widget-cobrowse-consent" hidden></div>' +
            '<div id="cc-chat-widget-cobrowse-indicator">' +
            '<span>🔴 Tela sendo compartilhada com o atendente</span>' +
            '<button type="button" id="cc-chat-widget-cobrowse-stop">Parar</button>' +
            '</div>' +
            '<div id="cc-chat-widget-messages"></div>' +
            '<form id="cc-chat-widget-form">' +
            '<input id="cc-chat-widget-input" type="text" placeholder="Digite sua mensagem..." autocomplete="off" />' +
            '<label id="cc-chat-widget-attach" title="Anexar arquivo">📎' +
            '<input id="cc-chat-widget-file" type="file" style="display:none" />' +
            '</label>' +
            '<button id="cc-chat-widget-send" type="submit">Enviar</button>' +
            '</form>';

        document.body.appendChild(button);
        document.body.appendChild(panel);

        button.addEventListener('click', function () {
            panel.classList.toggle('open');
            if (panel.classList.contains('open')) {
                startPolling();
            } else {
                stopPolling();
            }
        });

        panel.querySelector('#cc-chat-widget-form').addEventListener('submit', function (ev) {
            ev.preventDefault();
            var input = panel.querySelector('#cc-chat-widget-input');
            var text = input.value.trim();
            if (!text) return;
            input.value = '';
            sendMessage(text);
        });

        panel.querySelector('#cc-chat-widget-file').addEventListener('change', function (ev) {
            var file = ev.target.files && ev.target.files[0];
            if (!file) return;
            uploadAttachment(file);
            ev.target.value = '';
        });

        panel.querySelector('#cc-chat-widget-cobrowse-stop').addEventListener('click', function () {
            postCobrowseConsent(false); // registra a revogação no backend (vira "revoked")
            stopCobrowseCapture();
        });
    }

    function messagesContainer() {
        return document.getElementById('cc-chat-widget-messages');
    }

    function renderMessages(messages) {
        var container = messagesContainer();
        if (!container || messages.length === state.lastMessageCount) return;
        container.innerHTML = '';
        messages.forEach(function (msg) {
            var div = document.createElement('div');
            div.className = 'cc-chat-msg ' + msg.senderType;
            div.textContent = msg.body;
            container.appendChild(div);
        });
        container.scrollTop = container.scrollHeight;
        state.lastMessageCount = messages.length;

        // Fase 17a — assim que a primeira mensagem de agente chega, pode existir uma
        // CcCobrowseSession pendente (criada no claim, se o agente tiver o toggle ligado).
        // Mostra o banner uma única vez por sessão; se não existir (404), o banner some
        // silenciosamente na primeira interação, sem nunca virar erro visível.
        if (!state.cobrowseConsentShown && messages.some(function (m) { return m.senderType === 'agent'; })) {
            state.cobrowseConsentShown = true;
            showCobrowseConsentBanner();
        }
    }

    function sha256Hex(text) {
        if (!(window.crypto && window.crypto.subtle)) {
            return Promise.resolve(null); // ambiente sem WebCrypto (http não-seguro) — sem hash, sem POST.
        }
        var data = new TextEncoder().encode(text);
        return window.crypto.subtle.digest('SHA-256', data).then(function (buffer) {
            return Array.prototype.map
                .call(new Uint8Array(buffer), function (b) { return b.toString(16).padStart(2, '0'); })
                .join('');
        });
    }

    function postCobrowseConsent(granted) {
        if (!state.sessionId || !state.token) return;
        sha256Hex(COBROWSE_CONSENT_TEXT).then(function (textHash) {
            if (!textHash) return;
            return fetch(API_BASE + '/callcenter/chat/public/sessions/' + state.sessionId + '/cobrowse-consent', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': 'Bearer ' + state.token,
                },
                body: JSON.stringify({ granted: granted, textHash: textHash }),
            });
        }).catch(function () {
            // Sem CcCobrowseSession pendente (404) ou qualquer outra falha — nunca interrompe o
            // chat, a decisão do cliente simplesmente não fica registrada nesta tentativa.
        });
    }

    function showCobrowseConsentBanner() {
        var banner = document.getElementById('cc-chat-widget-cobrowse-consent');
        if (!banner) return;
        banner.hidden = false;
        banner.innerHTML =
            '<div>' + COBROWSE_CONSENT_TEXT + '</div>' +
            '<div class="cc-cb-actions">' +
            '<button type="button" class="cc-cb-accept">Aceitar</button>' +
            '<button type="button" class="cc-cb-decline">Recusar</button>' +
            '</div>';
        banner.querySelector('.cc-cb-accept').addEventListener('click', function () {
            postCobrowseConsent(true);
            banner.hidden = true;
            startCobrowseCapture();
        });
        banner.querySelector('.cc-cb-decline').addEventListener('click', function () {
            postCobrowseConsent(false);
            banner.hidden = true;
        });
    }

    // ---- Fase 17b: captura rrweb (só depois do aceite explícito) ------------------------------

    function rrwebScriptUrl() {
        // Resolve a partir do próprio <script src> deste widget — funciona independente do
        // domínio/base configurado em data-api-base.
        var base = currentScript && currentScript.src ? currentScript.src : '';
        var lastSlash = base.lastIndexOf('/');
        var dir = lastSlash >= 0 ? base.substring(0, lastSlash + 1) : '';
        return dir + RRWEB_VENDOR_PATH;
    }

    function loadRrwebScript() {
        if (window.rrwebRecord) {
            return Promise.resolve();
        }
        return new Promise(function (resolve, reject) {
            var script = document.createElement('script');
            script.src = rrwebScriptUrl();
            script.async = true;
            script.onload = function () { resolve(); };
            script.onerror = function () { reject(new Error('Falha ao carregar biblioteca de captura.')); };
            document.head.appendChild(script);
        });
    }

    function showCobrowseIndicator(active) {
        var indicator = document.getElementById('cc-chat-widget-cobrowse-indicator');
        if (indicator) {
            indicator.classList.toggle('active', !!active);
        }
    }

    function onRrwebEvent(event) {
        state.cobrowseBuffer.push(event);
        // Estimativa de tamanho — JSON.stringify aqui é só pra medir, o payload real é
        // serializado de novo no momento do flush (evita segurar duas cópias por muito tempo).
        try {
            state.cobrowseBufferBytes += JSON.stringify(event).length;
        } catch (e) {
            // Evento não serializável (raríssimo) — conta como 0 pra não travar o buffer; ainda
            // assim entra no lote, e falhas de serialização no flush já são tratadas lá.
        }
        if (state.cobrowseBufferBytes >= COBROWSE_FLUSH_MAX_BYTES) {
            flushCobrowseBuffer(false);
        }
    }

    function flushCobrowseBuffer(useBeacon) {
        if (!state.cobrowseBuffer.length || !state.sessionId || !state.token) {
            return;
        }
        var events = state.cobrowseBuffer;
        state.cobrowseBuffer = [];
        state.cobrowseBufferBytes = 0;
        state.cobrowseSeq += 1;

        var payload;
        try {
            payload = JSON.stringify({ seq: state.cobrowseSeq, events: events });
        } catch (e) {
            return; // falha ao serializar o lote — descarta silenciosamente, nunca quebra o chat.
        }

        var url = API_BASE + '/callcenter/chat/public/sessions/' + state.sessionId + '/cobrowse-events';

        // sendBeacon não permite header Authorization — o endpoint exige Bearer, então
        // preferimos sempre fetch(keepalive) (suporta headers custom e funciona no unload,
        // mesma garantia prática do sendBeacon). Só cai pra sendBeacon (sem auth — o lote será
        // rejeitado com 401 e descartado no backend) se fetch nem existir neste navegador —
        // caminho de emergência que nunca deve bloquear o unload da página.
        if (window.fetch) {
            fetch(url, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + state.token },
                body: payload,
                keepalive: true,
            }).catch(function () {
                // Falha de rede num lote de co-browsing nunca aparece como erro pro cliente — é
                // best-effort; o próximo flush tenta de novo com os eventos seguintes.
            });
            return;
        }
        if (useBeacon && navigator.sendBeacon) {
            navigator.sendBeacon(url, new Blob([payload], { type: 'application/json' }));
        }
    }

    function onVisibilityOrPageHide() {
        if (state.cobrowseCapturing) {
            flushCobrowseBuffer(true);
        }
    }

    function startCobrowseCapture() {
        if (state.cobrowseCapturing) return;
        loadRrwebScript()
            .then(function () {
                if (!window.rrwebRecord || typeof window.rrwebRecord.record !== 'function') {
                    throw new Error('Biblioteca de captura indisponível.');
                }
                state.cobrowseStopFn = window.rrwebRecord.record({
                    emit: onRrwebEvent,
                });
                state.cobrowseCapturing = true;
                state.cobrowseFlushTimer = setInterval(function () { flushCobrowseBuffer(false); }, COBROWSE_FLUSH_INTERVAL_MS);
                document.addEventListener('visibilitychange', onVisibilityOrPageHide);
                window.addEventListener('pagehide', onVisibilityOrPageHide);
                showCobrowseIndicator(true);
            })
            .catch(function () {
                // Falha ao carregar/iniciar a captura nunca quebra o chat — só não há co-browsing
                // nesta conversa; sem retry automático (evita martelar um recurso indisponível).
            });
    }

    function stopCobrowseCapture() {
        if (!state.cobrowseCapturing) return;
        flushCobrowseBuffer(false);
        if (state.cobrowseFlushTimer) {
            clearInterval(state.cobrowseFlushTimer);
            state.cobrowseFlushTimer = null;
        }
        document.removeEventListener('visibilitychange', onVisibilityOrPageHide);
        window.removeEventListener('pagehide', onVisibilityOrPageHide);
        if (typeof state.cobrowseStopFn === 'function') {
            try { state.cobrowseStopFn(); } catch (e) { /* nunca deixa o stop quebrar a UI */ }
        }
        state.cobrowseStopFn = null;
        state.cobrowseCapturing = false;
        showCobrowseIndicator(false);
    }

    function ensureSession(firstMessage) {
        if (state.sessionId && state.token) {
            return Promise.resolve();
        }
        return fetch(API_BASE + '/callcenter/chat/public/sessions', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ customerRef: getCustomerRef(), customerName: null }),
        })
            .then(function (res) {
                if (!res.ok) throw new Error('Falha ao iniciar conversa (' + res.status + ')');
                return res.json();
            })
            .then(function (data) {
                state.sessionId = data.sessionId;
                state.token = data.token;
            });
    }

    function sendMessage(text) {
        ensureSession()
            .then(function () {
                return fetch(API_BASE + '/callcenter/chat/public/sessions/' + state.sessionId + '/messages', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'Authorization': 'Bearer ' + state.token,
                    },
                    body: JSON.stringify({ text: text }),
                });
            })
            .then(function (res) {
                if (!res.ok) throw new Error('Falha ao enviar mensagem (' + res.status + ')');
                return fetchMessages();
            })
            .catch(function (err) {
                var container = messagesContainer();
                if (container) {
                    var div = document.createElement('div');
                    div.className = 'cc-chat-msg system';
                    div.textContent = 'Erro: ' + err.message;
                    container.appendChild(div);
                }
            });
    }

    // Fase 7d — anexo enviado pelo cliente (D6: bidirecional). Mesmo token de sessão do resto do
    // widget; validação de extensão/magic-bytes/cota acontece no backend (ChatAttachmentService).
    function uploadAttachment(file) {
        ensureSession()
            .then(function () {
                var form = new FormData();
                form.append('file', file);
                return fetch(API_BASE + '/callcenter/chat/public/sessions/' + state.sessionId + '/attachments', {
                    method: 'POST',
                    headers: { 'Authorization': 'Bearer ' + state.token },
                    body: form,
                });
            })
            .then(function (res) {
                if (!res.ok) {
                    return res.json().catch(function () { return {}; }).then(function (body) {
                        throw new Error(body.error || 'Falha ao enviar anexo (' + res.status + ')');
                    });
                }
                return fetchMessages();
            })
            .catch(function (err) {
                var container = messagesContainer();
                if (container) {
                    var div = document.createElement('div');
                    div.className = 'cc-chat-msg system';
                    div.textContent = 'Erro: ' + err.message;
                    container.appendChild(div);
                }
            });
    }

    function fetchMessages() {
        if (!state.sessionId || !state.token) return Promise.resolve();
        return fetch(API_BASE + '/callcenter/chat/public/sessions/' + state.sessionId + '/messages', {
            headers: { 'Authorization': 'Bearer ' + state.token },
        })
            .then(function (res) {
                if (!res.ok) throw new Error('Falha ao carregar mensagens (' + res.status + ')');
                return res.json();
            })
            .then(function (data) {
                state.pollBackoffMs = POLL_INTERVAL_MS;
                renderMessages(data);
                fetchAttachments();
            })
            .catch(function () {
                // Fase 10 (achado MEDIUM): sem backoff, uma queda do backend (deploy, restart de
                // container) mantinha todo widget aberto batendo a cada 3s durante a
                // indisponibilidade inteira. Dobra o intervalo até um teto, volta ao normal no
                // próximo sucesso — silencioso na UI (erros reais de envio já aparecem via
                // sendMessage()).
                state.pollBackoffMs = Math.min(state.pollBackoffMs * 2, POLL_MAX_BACKOFF_MS);
            })
            .then(scheduleNextPoll);
    }

    // Fase 7d — anexos não são cc_chat_messages (tabela particionada — ver nota da migration
    // V78), então precisam de um fetch/render próprio, encaixado no mesmo ciclo de polling.
    function fetchAttachments() {
        if (!state.sessionId || !state.token) return;
        fetch(API_BASE + '/callcenter/chat/public/sessions/' + state.sessionId + '/attachments', {
            headers: { 'Authorization': 'Bearer ' + state.token },
        })
            .then(function (res) { return res.ok ? res.json() : []; })
            .then(function (attachments) {
                if (attachments.length === state.lastAttachmentCount) return;
                state.lastAttachmentCount = attachments.length;
                var container = messagesContainer();
                if (!container) return;
                attachments.forEach(function (a) {
                    if (container.querySelector('[data-attachment-id="' + a.id + '"]')) return;
                    var div = document.createElement('div');
                    div.className = 'cc-chat-msg ' + a.senderType;
                    div.setAttribute('data-attachment-id', String(a.id));
                    div.textContent = '📎 ' + a.originalFileName;
                    container.appendChild(div);
                });
                container.scrollTop = container.scrollHeight;
            })
            .catch(function () { /* silencioso — mesma disciplina do fetchMessages */ });
    }

    function scheduleNextPoll() {
        if (!state.pollTimer) return; // polling foi parado (stopPolling) enquanto a requisição estava em voo.
        state.pollTimer = setTimeout(fetchMessages, state.pollBackoffMs);
    }

    function startPolling() {
        if (state.pollTimer) return;
        state.pollBackoffMs = POLL_INTERVAL_MS;
        state.pollTimer = setTimeout(function () {}, 0); // marca "ativo" antes do primeiro fetch assíncrono.
        fetchMessages();
    }

    function stopPolling() {
        if (state.pollTimer) {
            clearTimeout(state.pollTimer);
            state.pollTimer = null;
        }
    }

    function init() {
        injectStyles();
        buildUi();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
