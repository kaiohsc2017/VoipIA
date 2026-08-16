import React from 'react';
import type { DesktopQualityView } from '../../api/types';

interface QualityPanelProps {
  quality: DesktopQualityView | null;
  loading: boolean;
}

export const QualityPanel: React.FC<QualityPanelProps> = ({ quality, loading }) => {
  if (loading) {
    return <div className="py-12 text-center text-xs text-slate-500">Carregando qualidade...</div>;
  }

  return (
    <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
      <h3 className="text-base font-semibold text-slate-900 dark:text-slate-100 mb-1">
        Avaliação de Qualidade
      </h3>
      <p className="text-xs text-slate-500 dark:text-slate-400 mb-6">
        Resultado consolidado de monitoria e feedbacks automatizados
      </p>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        <div className="flex items-center gap-4 p-4 rounded-xl bg-indigo-50/50 dark:bg-indigo-950/20 border border-indigo-100 dark:border-indigo-900/40">
          <div className="w-16 h-16 rounded-full bg-indigo-600 text-white font-bold text-xl flex items-center justify-center">
            {quality?.avgScore != null ? quality.avgScore.toFixed(1) : '—'}
          </div>
          <div>
            <p className="text-xs font-medium text-slate-500 dark:text-slate-400">Nota Média Consolidada</p>
            <p className="text-sm font-semibold text-slate-900 dark:text-slate-100 mt-0.5">
              Baseado em {quality?.totalEvaluations ?? 0} avaliações
            </p>
          </div>
        </div>

        <div className="space-y-4">
          <div>
            <h4 className="text-xs font-semibold text-emerald-600 dark:text-emerald-400 mb-2 flex items-center gap-1.5">
              ✓ Pontos Fortes
            </h4>
            {quality?.strongPoints && quality.strongPoints.length > 0 ? (
              <ul className="space-y-1 text-xs text-slate-600 dark:text-slate-300">
                {quality.strongPoints.map((pt, i) => (
                  <li key={i} className="flex items-start gap-1.5">
                    <span>•</span> <span>{pt}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-xs text-slate-400 italic">Nenhum ponto registrado.</p>
            )}
          </div>

          <div>
            <h4 className="text-xs font-semibold text-amber-600 dark:text-amber-400 mb-2 flex items-center gap-1.5">
              ⚠ Oportunidades de Melhoria
            </h4>
            {quality?.improvementPoints && quality.improvementPoints.length > 0 ? (
              <ul className="space-y-1 text-xs text-slate-600 dark:text-slate-300">
                {quality.improvementPoints.map((pt, i) => (
                  <li key={i} className="flex items-start gap-1.5">
                    <span>•</span> <span>{pt}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-xs text-slate-400 italic">Nenhum ponto de melhoria pendente.</p>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};
