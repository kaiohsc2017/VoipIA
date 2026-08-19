import React from 'react';
import type { DesktopTrendPoint } from '../../api/types';

interface ProductivityPanelProps {
  trends?: DesktopTrendPoint[];
  loading?: boolean;
  onDaysChange?: (days: number) => void;
}

export const ProductivityPanel: React.FC<ProductivityPanelProps> = ({
  trends = [],
}) => {
  const maxVal = Math.max(...trends.map((t) => t.answeredCount), 1);

  return (
    <div className="space-y-5">
      {/* ─── 1. Card Superior: Evolução — últimos 14 dias ─── */}
      <div className="bg-white border border-slate-200/90 rounded-xl p-5 shadow-xs">
        <h3 className="text-sm font-bold text-slate-900 mb-4">Evolução — últimos 14 dias</h3>

        {/* SVG Chart */}
        {trends.length > 0 ? (
          <div className="w-full h-56 pt-2 pb-6 relative">
            <svg className="w-full h-full overflow-visible" viewBox="0 0 700 180" preserveAspectRatio="none">
              <line x1="0" y1="30" x2="700" y2="30" stroke="#f1f5f9" strokeWidth="1" />
              <line x1="0" y1="80" x2="700" y2="80" stroke="#f1f5f9" strokeWidth="1" />
              <line x1="0" y1="130" x2="700" y2="130" stroke="#f1f5f9" strokeWidth="1" />

              {trends.map((t, idx) => {
                const barWidth = 24;
                const spacing = 700 / Math.max(1, trends.length);
                const x = idx * spacing + spacing / 2 - barWidth / 2;
                const h = (t.answeredCount / maxVal) * 120;
                return (
                  <rect
                    key={idx}
                    x={x}
                    y={140 - h}
                    width={barWidth}
                    height={Math.max(4, h)}
                    rx="4"
                    fill="#e0e7ff"
                    className="hover:fill-indigo-300 transition-colors"
                  >
                    <title>{`${t.date}: ${t.answeredCount} chamadas`}</title>
                  </rect>
                );
              })}
            </svg>

            {/* X Axis Labels */}
            <div className="flex justify-between text-xs font-medium text-slate-500 mt-2 px-4">
              {trends.map((t, i) => (
                <span key={i}>{t.date.slice(5)}</span>
              ))}
            </div>
          </div>
        ) : (
          <div className="py-16 text-center text-xs text-slate-400 italic">
            Sem dados de produtividade acumulados nos últimos 14 dias.
          </div>
        )}

        {/* Legend */}
        <div className="flex items-center gap-6 text-xs text-slate-600 pt-3 border-t border-slate-100">
          <div className="flex items-center gap-2">
            <span className="w-3.5 h-3.5 rounded-xs bg-[#e0e7ff] border border-indigo-200" />
            <span>Chamadas atendidas</span>
          </div>
        </div>
      </div>

      {/* ─── 2. Grid Inferior: Distribuição do Tempo & Minhas Pausas ─── */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
        <div className="bg-white border border-slate-200/90 rounded-xl p-5 shadow-xs flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-sm font-bold text-slate-900">Distribuição do tempo</h3>
              <span className="text-xs text-slate-400 font-medium">hoje</span>
            </div>

            <div className="py-8 text-center text-xs text-slate-400 italic">
              O tempo por estado será calculado conforme a jornada de atendimento avança no dia.
            </div>
          </div>
        </div>

        <div className="bg-white border border-slate-200/90 rounded-xl p-5 shadow-xs flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-sm font-bold text-slate-900">Minhas pausas</h3>
            </div>

            <div className="py-8 text-center text-xs text-slate-400 italic">
              Nenhuma pausa registrada no dia de hoje.
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
