import React from 'react';
import type {
  DesktopQualityView,
  DesktopEvaluationDetailView,
  CoachingPlanView,
} from '../../api/types';

interface QualityPanelProps {
  quality?: DesktopQualityView | null;
  evaluations: DesktopEvaluationDetailView[];
  coachingPlans?: CoachingPlanView[];
  loading?: boolean;
  onOpenAppeal?: (evalDetail: DesktopEvaluationDetailView) => void;
  onUpdateCoachingStatus?: (planId: number, newStatus: 'EM_ANDAMENTO' | 'CONCLUIDO') => void;
}

export const QualityPanel: React.FC<QualityPanelProps> = ({
  quality,
  evaluations,
  loading = false,
}) => {
  const evalRows = evaluations.map((e) => ({
    chamada: e.callDateTime
      ? new Date(e.callDateTime).toLocaleString('pt-BR', {
          day: '2-digit',
          month: '2-digit',
          hour: '2-digit',
          minute: '2-digit',
        })
      : '—',
    ficha: e.scorecardName || 'Ficha de Monitoria',
    nota: e.notaTotal != null ? e.notaTotal.toFixed(1).replace('.', ',') : '—',
    avaliadoEm: e.callDateTime
      ? new Date(e.callDateTime).toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' })
      : '—',
  }));

  const strongPoints = quality?.strongPoints ?? [];
  const improvementPoints = quality?.improvementPoints ?? [];

  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
      {/* ─── 1. Card Esquerdo: Avaliações Recebidas ─── */}
      <div className="bg-white border border-slate-200/90 rounded-xl p-5 shadow-xs flex flex-col justify-between">
        <div>
          <h3 className="text-sm font-bold text-slate-900 mb-4">Avaliações recebidas</h3>

          {loading ? (
            <div className="py-12 text-center text-xs text-slate-400">Carregando avaliações...</div>
          ) : evalRows.length === 0 ? (
            <div className="py-12 text-center text-xs text-slate-400 italic">
              Nenhuma avaliação de qualidade registrada no período.
            </div>
          ) : (
            <table className="w-full text-left text-xs">
              <thead>
                <tr className="text-[10px] font-bold text-slate-400 uppercase border-b border-slate-100">
                  <th className="pb-2.5 px-2 font-medium">CHAMADA</th>
                  <th className="pb-2.5 px-2 font-medium">FICHA</th>
                  <th className="pb-2.5 px-2 font-medium text-center">NOTA</th>
                  <th className="pb-2.5 px-2 font-medium text-right">AVALIADO EM</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 font-mono">
                {evalRows.map((row, idx) => (
                  <tr key={idx} className="hover:bg-slate-50/70 transition-colors">
                    <td className="py-2.5 px-2 text-slate-900">{row.chamada}</td>
                    <td className="py-2.5 px-2 font-sans text-slate-700">{row.ficha}</td>
                    <td className="py-2.5 px-2 text-center font-bold text-slate-900">{row.nota}</td>
                    <td className="py-2.5 px-2 text-right text-slate-600">{row.avaliadoEm}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      {/* ─── 2. Card Direito: Pontos Fortes e de Melhoria ─── */}
      <div className="bg-white border border-slate-200/90 rounded-xl p-5 shadow-xs flex flex-col justify-between">
        <div>
          <h3 className="text-sm font-bold text-slate-900 mb-4">Pontos fortes e de melhoria</h3>

          <div className="space-y-4 text-xs">
            {/* Pontos fortes */}
            <div>
              <h4 className="font-bold text-emerald-600 mb-2">Pontos fortes</h4>
              {strongPoints.length > 0 ? (
                <ul className="space-y-1.5 text-slate-700">
                  {strongPoints.map((pt, i) => (
                    <li key={i} className="flex items-start gap-2">
                      <span className="text-slate-400">•</span>
                      <span>{pt}</span>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="text-slate-400 italic">Nenhum ponto forte consolidado ainda.</p>
              )}
            </div>

            {/* Pontos de melhoria */}
            <div>
              <h4 className="font-bold text-amber-600 mb-2">Pontos de melhoria</h4>
              {improvementPoints.length > 0 ? (
                <ul className="space-y-1.5 text-slate-700">
                  {improvementPoints.map((pt, i) => (
                    <li key={i} className="flex items-start gap-2">
                      <span className="text-slate-400">•</span>
                      <span>{pt}</span>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="text-slate-400 italic">Nenhum ponto de melhoria identificado.</p>
              )}
            </div>
          </div>
        </div>

        <div className="pt-4 mt-3 border-t border-slate-100 text-[11px] text-slate-400 leading-relaxed">
          Calculado a partir das avaliações e fichas de monitoria registradas no sistema.
        </div>
      </div>
    </div>
  );
};
