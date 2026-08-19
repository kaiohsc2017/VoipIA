import React from 'react';
import type { DesktopSummaryView } from '../../api/types';

interface KpiStripProps {
  summary: DesktopSummaryView | null;
}

export const KpiStrip: React.FC<KpiStripProps> = ({ summary }) => {
  const formatSeconds = (sec: number | null | undefined) => {
    if (sec === null || sec === undefined || isNaN(sec) || sec === 0) return '0:00';
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m}:${s.toString().padStart(2, '0')}`;
  };

  const answered = summary?.callsAnsweredToday ?? 0;
  const tmaStr = formatSeconds(summary?.avgTalkSeconds);
  const occupancyStr = summary?.occupancyPct != null ? `${summary.occupancyPct.toFixed(0)}%` : '0%';
  const adherenceStr = summary?.adherencePct != null ? `${summary.adherencePct.toFixed(0)}%` : '—';
  const npsStr = summary?.avgNpsScore != null ? summary.avgNpsScore.toFixed(1).replace('.', ',') : '—';
  const pauseMinutes = summary?.pauseSeconds ? Math.round(summary.pauseSeconds / 60) : 0;

  return (
    <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3.5 mb-6">
      {/* 1. Atendidas Hoje */}
      <div className="bg-white border border-slate-200/90 rounded-xl p-3.5 shadow-xs flex flex-col justify-between hover:border-slate-300 transition-colors">
        <div>
          <span className="text-[10px] font-bold tracking-wider text-slate-500 uppercase">
            ATENDIDAS HOJE
          </span>
          <div className="text-2xl font-extrabold text-slate-900 mt-1">{answered}</div>
          <div className="text-[11px] font-medium text-slate-500 flex items-center gap-0.5 mt-0.5">
            <span>concluídas hoje</span>
          </div>
        </div>
        <div className="pt-2">
          <svg className="w-full h-5 overflow-visible" viewBox="0 0 100 20" preserveAspectRatio="none">
            <path
              d="M0,16 Q25,18 50,12 T100,6"
              fill="none"
              stroke="#2563eb"
              strokeWidth="2"
              strokeLinecap="round"
            />
          </svg>
        </div>
      </div>

      {/* 2. TMA */}
      <div className="bg-white border border-slate-200/90 rounded-xl p-3.5 shadow-xs flex flex-col justify-between hover:border-slate-300 transition-colors">
        <div>
          <span className="text-[10px] font-bold tracking-wider text-slate-500 uppercase">
            TMA
          </span>
          <div className="text-2xl font-extrabold text-slate-900 mt-1">{tmaStr}</div>
          <div className="text-[11px] font-medium text-slate-500 flex items-center gap-0.5 mt-0.5">
            <span>
              {summary?.comparedTo7dAvgTalkPct != null
                ? `${summary.comparedTo7dAvgTalkPct > 0 ? '▲ +' : '▼ '}${summary.comparedTo7dAvgTalkPct.toFixed(0)}% vs 7d`
                : 'tempo médio falado'}
            </span>
          </div>
        </div>
        <div className="pt-2">
          <svg className="w-full h-5 overflow-visible" viewBox="0 0 100 20" preserveAspectRatio="none">
            <path
              d="M0,8 Q30,12 60,16 T100,18"
              fill="none"
              stroke="#ea580c"
              strokeWidth="2"
              strokeLinecap="round"
            />
          </svg>
        </div>
      </div>

      {/* 3. Ocupação */}
      <div className="bg-white border border-slate-200/90 rounded-xl p-3.5 shadow-xs flex flex-col justify-between hover:border-slate-300 transition-colors">
        <div>
          <span className="text-[10px] font-bold tracking-wider text-slate-500 uppercase">
            OCUPAÇÃO
          </span>
          <div className="text-2xl font-extrabold text-slate-900 mt-1">{occupancyStr}</div>
          <div className="text-[11px] font-medium text-slate-500 flex items-center gap-0.5 mt-0.5">
            <span>tempo em atendimento</span>
          </div>
        </div>
        <div className="pt-2">
          <svg className="w-full h-5 overflow-visible" viewBox="0 0 100 20" preserveAspectRatio="none">
            <path
              d="M0,17 Q35,16 70,12 T100,8"
              fill="none"
              stroke="#10b981"
              strokeWidth="2"
              strokeLinecap="round"
            />
          </svg>
        </div>
      </div>

      {/* 4. Aderência à Escala */}
      <div className="bg-white border border-slate-200/90 rounded-xl p-3.5 shadow-xs flex flex-col justify-between hover:border-slate-300 transition-colors">
        <div>
          <span className="text-[10px] font-bold tracking-wider text-slate-500 uppercase">
            ADERÊNCIA À ESCALA
          </span>
          <div className="text-2xl font-extrabold text-slate-900 mt-1">{adherenceStr}</div>
          <div className="text-[11px] font-medium text-slate-500 flex items-center gap-0.5 mt-0.5">
            <span>cumprimento da escala</span>
          </div>
        </div>
        <div className="pt-2">
          <svg className="w-full h-5 overflow-visible" viewBox="0 0 100 20" preserveAspectRatio="none">
            <path
              d="M0,10 Q40,11 75,10 T100,9"
              fill="none"
              stroke="#2563eb"
              strokeWidth="2"
              strokeLinecap="round"
            />
          </svg>
        </div>
      </div>

      {/* 5. NPS Médio */}
      <div className="bg-white border border-slate-200/90 rounded-xl p-3.5 shadow-xs flex flex-col justify-between hover:border-slate-300 transition-colors">
        <div>
          <span className="text-[10px] font-bold tracking-wider text-slate-500 uppercase">
            NPS MÉDIO
          </span>
          <div className="text-2xl font-extrabold text-slate-900 mt-1">{npsStr}</div>
          <div className="text-[11px] font-medium text-slate-500 flex items-center gap-0.5 mt-0.5">
            <span>avaliação do cliente</span>
          </div>
        </div>
        <div className="pt-2">
          <svg className="w-full h-5 overflow-visible" viewBox="0 0 100 20" preserveAspectRatio="none">
            <path
              d="M0,16 Q30,15 65,11 T100,6"
              fill="none"
              stroke="#10b981"
              strokeWidth="2"
              strokeLinecap="round"
            />
          </svg>
        </div>
      </div>

      {/* 6. Tempo em Pausa */}
      <div className="bg-white border border-slate-200/90 rounded-xl p-3.5 shadow-xs flex flex-col justify-between hover:border-slate-300 transition-colors">
        <div>
          <span className="text-[10px] font-bold tracking-wider text-slate-500 uppercase">
            TEMPO EM PAUSA
          </span>
          <div className="text-2xl font-extrabold text-slate-900 mt-1">{pauseMinutes} min</div>
          <div className="text-[11px] font-medium text-slate-400 flex items-center gap-0.5 mt-0.5">
            <span>total em pausas hoje</span>
          </div>
        </div>
        <div className="pt-2">
          <svg className="w-full h-5 overflow-visible" viewBox="0 0 100 20" preserveAspectRatio="none">
            <path
              d="M0,12 Q30,14 65,12 T100,13"
              fill="none"
              stroke="#6b7280"
              strokeWidth="2"
              strokeLinecap="round"
            />
          </svg>
        </div>
      </div>
    </div>
  );
};
