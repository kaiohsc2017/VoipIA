/**
 * websocket.ts — Cliente STOMP/SockJS para atualizações em tempo real.
 * Conecta ao endpoint /ws do backend e disponibiliza subscrições reutilizáveis.
 *
 * IMPORTANTE: @stomp/stompjs v6 lança erro síncrono se subscribe() for chamado
 * antes do frame CONNECTED. Este módulo enfileira as assinaturas e as executa
 * dentro do onConnect, evitando o crash do React (tela em branco).
 */
import { Client } from '@stomp/stompjs';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
declare const SockJS: any;

const WS_URL = (import.meta.env.VITE_API_URL ?? 'http://localhost:8080/api/v1')
  .replace('/api/v1', '')
  .replace(/\/$/, '');

let client: Client | null = null;
// Chave por id único da chamada (não pelo tópico) — dois assinantes distintos
// do mesmo tópico (ex: KpiBar e DashboardTab ambos em /topic/calls) não podem
// se sobrescrever no Map, senão o unsubscribe de um cancela o do outro.
const subscriptions = new Map<number, { unsubscribe: () => void }>();
let nextSubId = 0;

// Fila de subscriptions que chegaram antes da conexão estar pronta
let pendingSubs: Array<() => void> = [];

export function connectWebSocket(onConnected?: () => void): Client {
  if (client?.active) return client;

  const token = localStorage.getItem('voipia_token');
  client = new Client({
    webSocketFactory: () => new SockJS(`${WS_URL}/ws`),
    // Autentica no frame CONNECT — o backend rejeita sem JWT válido.
    connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
    reconnectDelay: 5000,
    onConnect: () => {
      onConnected?.();
      // Executa subscriptions que chegaram antes da conexão estar pronta
      const toRun = [...pendingSubs];
      pendingSubs = [];
      toRun.forEach(fn => fn());
    },
    onStompError: (frame) => console.error('[WS] Erro STOMP:', frame),
  });

  client.activate();
  return client;
}

export function subscribe<T>(
  topic: string,
  callback: (payload: T) => void,
): () => void {
  if (!client) connectWebSocket();

  const subId = nextSubId++;

  const doSubscribe = () => {
    try {
      const sub = client!.subscribe(topic, (msg) => {
        try {
          const data: T = JSON.parse(msg.body);
          callback(data);
        } catch {
          console.warn('[WS] Payload inválido no tópico', topic, msg.body);
        }
      });
      subscriptions.set(subId, sub);
    } catch (err) {
      console.warn('[WS] Erro ao subscribir tópico', topic, err);
    }
  };

  if (client?.connected) {
    // STOMP já conectado — subscribe imediato
    doSubscribe();
  } else {
    // Ainda conectando — enfileira para executar no onConnect
    pendingSubs.push(doSubscribe);
  }

  return () => {
    subscriptions.get(subId)?.unsubscribe();
    subscriptions.delete(subId);
    pendingSubs = pendingSubs.filter(fn => fn !== doSubscribe);
  };
}

export function disconnectWebSocket(): void {
  client?.deactivate();
  client = null;
  subscriptions.clear();
  pendingSubs = [];
}
