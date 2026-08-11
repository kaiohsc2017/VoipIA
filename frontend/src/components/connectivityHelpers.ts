/**
 * connectivityHelpers.ts — funções puras compartilhadas entre ModuloConectividade.tsx
 * e HistoricoModal.tsx (extraído na fase 24, O3.5 da refatoração).
 */

export const STATUS_CLASS: Record<string, string> = {
  SUCESSO: 'badge-success', FALHA: 'badge-danger', OCUPADO: 'badge-warning',
  TIMEOUT: 'badge-warning', SEM_RESPOSTA: 'badge-gray',
  INVALIDO: 'badge-danger', INDISPONIVEL: 'badge-danger', RECUSADO: 'badge-danger',
};

export function formatDate(iso: string) {
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit', month: '2-digit', year: '2-digit',
    hour: '2-digit', minute: '2-digit',
  });
}

/** Calcula próxima execução do teste baseado em startTime + intervalMinutes. */
export function nextExecution(startTime: string, intervalMinutes: number): string {
  const now = new Date();
  const [h, m] = startTime.split(':').map(Number);
  const base = new Date(now);
  base.setHours(h, m, 0, 0);
  if (base < now) base.setDate(base.getDate() + 1);

  // Ajusta para o próximo intervalo a partir de agora
  const msInterval = intervalMinutes * 60 * 1000;
  const diff = now.getTime() - base.getTime() + 24 * 60 * 60 * 1000;
  const intervals = Math.ceil(diff / msInterval);
  const next = new Date(base.getTime() + intervals * msInterval);
  if (next < now) return '—';

  const mins = Math.round((next.getTime() - now.getTime()) / 60000);
  if (mins < 60) return `em ${mins}min`;
  const hrs = Math.floor(mins / 60);
  const rem = mins % 60;
  return rem > 0 ? `em ${hrs}h ${rem}min` : `em ${hrs}h`;
}

// Períodos para filtros rápidos
export function getPeriodRange(period: 'today' | 'week' | 'month'): { from: string; to: string } {
  const now = new Date();
  const to = now.toISOString().slice(0, 16);
  let from: Date;
  if (period === 'today') {
    from = new Date(now); from.setHours(0, 0, 0, 0);
  } else if (period === 'week') {
    from = new Date(now); from.setDate(now.getDate() - now.getDay() + 1); from.setHours(0, 0, 0, 0);
  } else {
    from = new Date(now.getFullYear(), now.getMonth(), 1);
  }
  return { from: from.toISOString().slice(0, 16), to };
}
