import React from 'react';
import type { DesktopRankingView } from '../../api/types';

interface RankingPanelProps {
  ranking: DesktopRankingView | null;
  loading: boolean;
}

export const RankingPanel: React.FC<RankingPanelProps> = ({ ranking, loading }) => {
  if (loading) {
    return <div className="py-12 text-center text-xs text-slate-500">Carregando ranking...</div>;
  }

  return (
    <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
      <h3 className="text-base font-semibold text-slate-900 dark:text-slate-100 mb-1">
        Sua Posicionamento no Ranking
      </h3>
      <p className="text-xs text-slate-500 dark:text-slate-400 mb-6">
        Baseado no NPS e qualidade dos atendimentos do mês (dados anonimizados)
      </p>

      <div className="flex flex-col md:flex-row items-center gap-6 p-6 rounded-xl bg-slate-50 dark:bg-slate-800/40 border border-slate-200 dark:border-slate-800 mb-6">
        <div className="flex flex-col items-center">
          <span className="text-xs text-slate-500 font-medium">Sua Posição</span>
          <div className="text-4xl font-extrabold text-indigo-600 dark:text-indigo-400 mt-1">
            #{ranking?.position ?? '—'}
          </div>
          <span className="text-xs text-slate-400 mt-1">de {ranking?.totalAgents ?? 0} agentes</span>
        </div>

        <div className="w-full md:w-px h-px md:h-16 bg-slate-200 dark:bg-slate-700 my-2 md:my-0" />

        <div className="flex-1 space-y-1">
          <div className="flex items-center gap-2">
            <span className="text-sm font-semibold text-slate-900 dark:text-slate-100">
              Nível: {ranking?.tierLabel || 'Em Evolução'}
            </span>
          </div>
          <p className="text-xs text-slate-500 dark:text-slate-400">
            NPS Médio no Período: <strong className="text-slate-800 dark:text-slate-200">{ranking?.npsScore != null ? ranking.npsScore.toFixed(1) : '—'}</strong>
          </p>
        </div>
      </div>

      <h4 className="text-xs font-semibold text-slate-700 dark:text-slate-300 mb-3">
        Top Performers do Mês (Anonimizado)
      </h4>
      <div className="space-y-2">
        {ranking?.top3Anonymous && ranking.top3Anonymous.length > 0 ? (
          ranking.top3Anonymous.map((item) => (
            <div
              key={item.position}
              className="flex items-center justify-between p-3 rounded-lg border border-slate-100 dark:border-slate-800/60 bg-white dark:bg-slate-900"
            >
              <div className="flex items-center gap-3">
                <span className="w-6 h-6 rounded-full bg-amber-500/10 text-amber-600 font-bold text-xs flex items-center justify-center">
                  #{item.position}
                </span>
                <span className="text-xs font-medium text-slate-700 dark:text-slate-300">
                  {item.label}
                </span>
              </div>
              <span className="text-xs font-mono font-semibold text-slate-600 dark:text-slate-400">
                NPS {item.npsScore != null ? item.npsScore.toFixed(1) : '—'}
              </span>
            </div>
          ))
        ) : (
          <p className="text-xs text-slate-400 italic">Sem ranking disponível.</p>
        )}
      </div>
    </div>
  );
};
