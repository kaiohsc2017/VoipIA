/**
 * websocket.ts — Cliente STOMP/SockJS para atualizações em tempo real.
 * Conecta ao endpoint /ws do backend e disponibiliza subscrições reutilizáveis.
 */
import { Client } from '@stomp/stompjs';

// sockjs-client não tem tipos oficiais; importá-lo via window global é o padrão mais seguro
// eslint-disable-next-line @typescript-eslint/no-explicit-any
declare const SockJS: any;

const WS_URL = (import.meta.env.VITE_API_URL ?? 'http://localhost:8080/api/v1')
  .replace('/api/v1', '')
  .replace(/\/$/, '');

let client: Client | null = null;
const subscriptions = new Map<string, { unsubscribe: () => void }>();

export function connectWebSocket(onConnected?: () => void): Client {
  if (client?.active) return client;

  client = new Client({
    webSocketFactory: () => new SockJS(`${WS_URL}/ws`),
    reconnectDelay: 5000,
    onConnect: () => {
      console.log('[WS] Conectado ao broker STOMP');
      onConnected?.();
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
  if (!client?.active) connectWebSocket();

  const sub = client!.subscribe(topic, (msg) => {
    try {
      const data: T = JSON.parse(msg.body);
      callback(data);
    } catch {
      console.warn('[WS] Payload inválido no tópico', topic, msg.body);
    }
  });

  subscriptions.set(topic, sub);
  return () => {
    sub.unsubscribe();
    subscriptions.delete(topic);
  };
}

export function disconnectWebSocket(): void {
  client?.deactivate();
  client = null;
  subscriptions.clear();
}
