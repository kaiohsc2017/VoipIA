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
const subscriptions = new Map<string, { unsubscribe: () => void }>();

// Fila de subscriptions que chegaram antes da conexão estar pronta
let pendingSubs: Array<() => void> = [];

export function connectWebSocket(onConnected?: () => void): Client {
  if (client?.active) return client;

  client = new Client({
    webSocketFactory: () => new SockJS(`${WS_URL}/ws`),
    reconnectDelay: 5000,
    onConnect: () => {
      console.log('[WS] Conectado ao broker STOMP');
      onConnected?.();
      // Executa subscriptions que chegaram antes da conexão estar pronta
      const toRun = [...pendingSubs];
      pendingSubs = [];
      toRun.forEach(fn => fn());
    },
    onDisconnect: () => console.log('[WS] Desconectado'),
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
      subscriptions.set(topic, sub);
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
    subscriptions.get(topic)?.unsubscribe();
    subscriptions.delete(topic);
    pendingSubs = pendingSubs.filter(fn => fn !== doSubscribe);
  };
}

export function disconnectWebSocket(): void {
  client?.deactivate();
  client = null;
  subscriptions.clear();
  pendingSubs = [];
}
