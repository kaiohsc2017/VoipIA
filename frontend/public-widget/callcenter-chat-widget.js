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
 */
(function () {
    'use strict';

    var currentScript = document.currentScript;
    var API_BASE = (currentScript && currentScript.dataset.apiBase) || (location.origin + '/api/v1');
    var CUSTOMER_REF_KEY = 'asteriskia_cc_chat_ref';
    var POLL_INTERVAL_MS = 3000;

    var state = {
        sessionId: null,
        token: null,
        pollTimer: null,
        lastMessageCount: 0,
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
            '<div id="cc-chat-widget-messages"></div>' +
            '<form id="cc-chat-widget-form">' +
            '<input id="cc-chat-widget-input" type="text" placeholder="Digite sua mensagem..." autocomplete="off" />' +
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

    function fetchMessages() {
        if (!state.sessionId || !state.token) return Promise.resolve();
        return fetch(API_BASE + '/callcenter/chat/public/sessions/' + state.sessionId + '/messages', {
            headers: { 'Authorization': 'Bearer ' + state.token },
        })
            .then(function (res) {
                if (!res.ok) throw new Error('Falha ao carregar mensagens (' + res.status + ')');
                return res.json();
            })
            .then(renderMessages)
            .catch(function () {
                // Silencioso no polling — não spamma a UI a cada 3s se a rede oscilar;
                // erros reais de envio já aparecem via sendMessage().
            });
    }

    function startPolling() {
        if (state.pollTimer) return;
        fetchMessages();
        state.pollTimer = setInterval(fetchMessages, POLL_INTERVAL_MS);
    }

    function stopPolling() {
        if (state.pollTimer) {
            clearInterval(state.pollTimer);
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
