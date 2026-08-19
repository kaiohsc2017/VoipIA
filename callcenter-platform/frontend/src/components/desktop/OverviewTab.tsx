import React from 'react';
import type { DesktopSummaryView, DesktopCallHistoryItem } from '../../api/types';

interface OverviewTabProps {
  summary?: DesktopSummaryView | null;
  recentCalls: DesktopCallHistoryItem[];
  onNavigateTab: (tab: 'interacoes' | 'qualidade' | 'escala') => void;
}

export const OverviewTab: React.FC<OverviewTabProps> = ({
  summary,
  recentCalls,
  onNavigateTab,
}) => {
  const formatDurationSec = (sec: number | null | undefined) => {
    if (!sec) return '0m 00s';
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m}m ${s.toString().padStart(2, '0')}s`;
  };

  const loggedSec = summary?.loggedSeconds ?? 0;
  const pauseSec = summary?.pauseSeconds ?? 0;
  const availableSec = Math.max(0, loggedSec - pauseSec);
  const totalBarSec = Math.max(1, loggedSec);

  const availablePct = Math.round((availableSec / totalBarSec) * 100);
  const pausePct = Math.round((pauseSec / totalBarSec) * 100);

  const adherenceText = summary?.adherencePct != null
    ? `Aderência de ${summary.adherencePct.toFixed(0)}%`
    : 'Sem escala apurada hoje';

  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
      {/* ─── Card 1: Minha Jornada de Hoje (Top-Left) ─── */}
      <div className="bg-white border border-slate-200/90 rounded-xl p-5 shadow-xs flex flex-col justify-between">
        <div>
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-sm font-bold text-slate-900">Minha jornada de hoje</h3>
            <span className="text-xs font-mono text-slate-400">
              {loggedSec > 0 ? `${formatDurationSec(loggedSec)} logado` : 'Início do turno'}
            </span>
          </div>

          {/* Timeline Bar */}
          <div className="py-2 space-y-1.5">
            <div className="h-6 w-full rounded-md overflow-hidden flex bg-slate-100 border border-slate-200">
              {loggedSec > 0 ? (
                <>
                  <div
                    style={{ width: `${availablePct}%` }}
                    className="bg-emerald-500 h-full"
                    title={`Disponível: ${availablePct}%`}
                  />
                  <div
                    style={{ width: `${pausePct}%` }}
                    className="bg-amber-500 h-full"
                    title={`Pausa: ${pausePct}%`}
                  />
                </>
              ) : (
                <div className="w-full h-full bg-slate-200/60 flex items-center justify-center text-[10px] text-slate-500">
                  Aguardando atividade do turno
                </div>
              )}
            </div>

            {/* Time labels */}
            <div className="flex justify-between text-[10px] font-mono text-slate-400 px-0.5">
              <span>08:00</span>
              <span>10:00</span>
              <span>12:00</span>
              <span>14:00</span>
              <span>16:00</span>
              <span>18:00</span>
            </div>
          </div>
        </div>

        {/* Legend */}
        <div className="flex flex-wrap items-center gap-4 text-[11px] text-slate-600 pt-3 mt-2 border-t border-slate-100">
          <div className="flex items-center gap-1.5">
            <span className="w-2.5 h-2.5 rounded-full bg-emerald-500" />
            <span>Disponível</span>
          </div>
          <div className="flex items-center gap-1.5">
            <span className="w-2.5 h-2.5 rounded-full bg-indigo-600" />
            <span>Em atendimento</span>
          </div>
          <div className="flex items-center gap-1.5">
            <span className="w-2.5 h-2.5 rounded-full bg-amber-500" />
            <span>Pausa</span>
          </div>
        </div>
      </div>

      {/* ─── Card 2: Programação de Hoje (Top-Right) ─── */}
      <div className="bg-white border border-slate-200/90 rounded-xl p-5 shadow-xs flex flex-col justify-between">
        <div>
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-sm font-bold text-slate-900">Programação de hoje</h3>
            <span className="text-xs text-slate-400 font-medium">escala do dia</span>
          </div>

          <div className="py-2 space-y-2">
            <div className="flex items-center gap-3">
              <span className="text-xs font-mono text-slate-500">08:00</span>
              <div className="flex-1 h-5 rounded-md bg-slate-100 border border-slate-200 overflow-hidden relative">
                <div
                  style={{ width: summary?.adherencePct ? `${Math.min(100, summary.adherencePct)}%` : '0%' }}
                  className="h-full bg-indigo-600 rounded-sm"
                />
              </div>
              <span className="text-xs font-mono text-slate-500">17:00</span>
            </div>

            <div className="flex items-center gap-4 text-[11px] text-slate-500 pt-1">
              <div className="flex items-center gap-1.5">
                <span className="w-2.5 h-2.5 rounded-xs border border-slate-400" />
                <span>Previsto</span>
              </div>
              <div className="flex items-center gap-1.5">
                <span className="w-2.5 h-2.5 rounded-xs bg-indigo-600" />
                <span>Realizado</span>
              </div>
            </div>
          </div>
        </div>

        <div className="text-xs text-slate-600 pt-3 mt-2 border-t border-slate-100">
          <span className="font-semibold text-slate-900">{adherenceText}</span>
        </div>
      </div>

      {/* ─── Card 3: Últimas Interações (Bottom-Left) ─── */}
      <div className="bg-white border border-slate-200/90 rounded-xl p-5 shadow-xs flex flex-col justify-between">
        <div>
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-sm font-bold text-slate-900">Últimas interações</h3>
            <button
              onClick={() => onNavigateTab('interacoes')}
              className="text-xs text-indigo-600 hover:text-indigo-700 font-medium"
            >
              Ver todas →
            </button>
          </div>

          {recentCalls.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead>
                  <tr className="text-[10px] font-bold text-slate-400 uppercase border-b border-slate-100">
                    <th className="pb-2 font-medium">HORA</th>
                    <th className="pb-2 font-medium">REMOTO</th>
                    <th className="pb-2 font-medium">DURAÇÃO</th>
                    <th className="pb-2 font-medium">FILA</th>
                    <th className="pb-2 font-medium">FINALIZAÇÃO</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {recentCalls.slice(0, 4).map((c, i) => (
                    <tr key={c.interactionId || i} className="hover:bg-slate-50/70 transition-colors">
                      <td className="py-2.5 font-mono text-slate-600">
                        {c.dateTime ? new Date(c.dateTime).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' }) : '—'}
                      </td>
                      <td className="py-2.5 font-mono text-slate-900 font-medium">
                        {c.ani || c.contactName || 'Desconhecido'}
                      </td>
                      <td className="py-2.5 text-slate-600">{formatDurationSec(c.talkSeconds)}</td>
                      <td className="py-2.5 text-slate-600">{c.queueName || '—'}</td>
                      <td className="py-2.5">
                        <span className="inline-block px-2 py-0.5 rounded-full text-[10px] font-medium bg-slate-100 text-slate-700 border border-slate-200">
                          {c.dispositionLabel || 'Sem tabulação'}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="py-8 text-center text-xs text-slate-400 italic">
              Nenhuma interação registrada no dia de hoje.
            </div>
          )}
        </div>
      </div>

      {/* ─── Card 4: Resumo da Avaliação (Bottom-Right) ─── */}
      <div className="bg-white border border-slate-200/90 rounded-xl p-5 shadow-xs flex flex-col justify-between">
        <div>
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-sm font-bold text-slate-900">Resumo da avaliação</h3>
            <span className="text-xs text-slate-400 font-medium">monitoria de qualidade</span>
          </div>

          <div className="py-6 text-center text-xs text-slate-400 italic space-y-2">
            <p>Acompanhe suas notas e critérios avaliados na aba de Qualidade.</p>
            <button
              onClick={() => onNavigateTab('qualidade')}
              className="px-3 py-1.5 bg-indigo-50 hover:bg-indigo-100 text-indigo-600 text-xs font-semibold rounded-lg transition-colors inline-block"
            >
              Acessar Painel de Qualidade
            </button>
          </div>
        </div>

        <div className="text-xs text-slate-600 pt-3 mt-2 border-t border-slate-100">
          Monitoria contínua de atendimento.
        </div>
      </div>
    </div>
  );
};
