import React from 'react';
import type { DesktopRankingView } from '../../api/types';

interface RankingPanelProps {
  ranking: DesktopRankingView | null;
  loading?: boolean;
}

export const RankingPanel: React.FC<RankingPanelProps> = ({
  ranking,
  loading = false,
}) => {
  const position = ranking?.position ?? '—';
  const totalAgents = ranking?.totalAgents ?? '—';
  const npsScore = ranking?.npsScore != null ? ranking.npsScore.toFixed(1).replace('.', ',') : '—';
  const tierLabel = ranking?.tierLabel || 'Classificação Geral';
  const top3 = ranking?.top3Anonymous ?? [];

  return (
    <div className="bg-white border border-slate-200/90 rounded-xl p-5 shadow-xs space-y-5">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-bold text-slate-900">Minha posição no ranking</h3>
        <span className="text-xs text-slate-400 font-medium">classificação mensal</span>
      </div>

      {loading ? (
        <div className="py-12 text-center text-xs text-slate-400">Carregando ranking...</div>
      ) : (
        <>
          {/* ─── Hero Banner ─── */}
          <div className="p-4 rounded-xl bg-indigo-50/70 border border-indigo-100/80 flex items-center gap-4">
            <div className="text-3xl font-extrabold text-indigo-700 font-sans tracking-tight">
              {position !== '—' ? `${position}º` : '—'}
            </div>
            <div>
              <h4 className="text-xs font-bold text-slate-900">
                {totalAgents !== '—' ? `entre ${totalAgents} agentes elegíveis` : tierLabel}
              </h4>
              <p className="text-[11px] text-slate-500 mt-0.5">
                NPS individual: <strong>{npsScore}</strong> · {tierLabel}
              </p>
            </div>
          </div>

          {/* ─── Ranking Bars ─── */}
          {top3.length > 0 ? (
            <div className="space-y-3.5 pt-2">
              {top3.map((item, idx) => {
                const isYou = item.position === ranking?.position;
                const score = item.npsScore != null ? item.npsScore.toFixed(1).replace('.', ',') : '—';
                const pct = item.npsScore != null ? `${(item.npsScore / 10) * 100}%` : '50%';
                return (
                  <div key={idx} className="flex items-center gap-4 text-xs">
                    <span
                      className={`w-24 font-medium ${
                        isYou ? 'font-bold text-indigo-600' : 'text-slate-700'
                      }`}
                    >
                      {item.label || `${item.position}º lugar`}
                    </span>
                    <div className="flex-1 h-3 rounded-full bg-slate-100 overflow-hidden relative">
                      <div
                        style={{ width: pct }}
                        className={`h-full rounded-full ${
                          isYou ? 'bg-indigo-600' : 'bg-slate-300'
                        }`}
                      />
                    </div>
                    <span
                      className={`w-8 font-mono text-right ${
                        isYou ? 'font-bold text-indigo-600' : 'font-medium text-slate-700'
                      }`}
                    >
                      {score}
                    </span>
                  </div>
                );
              })}
            </div>
          ) : (
            <div className="py-8 text-center text-xs text-slate-400 italic">
              Posições da fila serão calculadas ao final do ciclo de atendimento do mês.
            </div>
          )}

          {/* ─── Footnote ─── */}
          <div className="pt-4 border-t border-slate-100 text-[11px] text-slate-400 leading-relaxed">
            Gamificação e ranking com anonimização dos colegas por padrão.
          </div>
        </>
      )}
    </div>
  );
};
