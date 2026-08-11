import { Fragment, type ReactNode } from 'react';

// ─── Blocos de apresentação reutilizáveis pela página de Documentação ──────────
// Espelham os componentes visuais do antigo docs.html (Plataforma de Agentes),
// mas usando os tokens reais do design system do Telecom (App.css) em vez de
// uma paleta duplicada.

export function Section({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section className="docs-section" id={id}>
      <h2>{title}</h2>
      {children}
    </section>
  );
}

export function SubSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <>
      <h3>{title}</h3>
      {children}
    </>
  );
}

export function Card({ children }: { children: ReactNode }) {
  return <div className="docs-card">{children}</div>;
}

export function CardGrid({ children }: { children: ReactNode }) {
  return <div className="docs-card-grid">{children}</div>;
}

export function CardSm({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="docs-card-sm">
      <h4>{title}</h4>
      <p>{children}</p>
    </div>
  );
}

export function FieldTable({ headers, rows }: { headers: string[]; rows: ReactNode[][] }) {
  return (
    <table className="docs-field-table">
      <thead>
        <tr>{headers.map(h => <th key={h}>{h}</th>)}</tr>
      </thead>
      <tbody>
        {rows.map((row, i) => (
          <tr key={i}>{row.map((cell, j) => <td key={j}>{cell}</td>)}</tr>
        ))}
      </tbody>
    </table>
  );
}

export function FieldName({ children }: { children: ReactNode }) {
  return <span className="docs-field-name">{children}</span>;
}

export function FieldType({ children }: { children: ReactNode }) {
  return <span className="docs-field-type">{children}</span>;
}

export function Req() {
  return <span className="docs-req">obrigatório</span>;
}

export function Opt() {
  return <span className="docs-opt">opcional</span>;
}

type Tone = 'info' | 'ok' | 'warn' | 'err' | 'purple' | 'gray';

export function Badge({ tone, children }: { tone: Tone; children: ReactNode }) {
  return <span className={`docs-badge docs-badge-${tone}`}>{children}</span>;
}

export function Callout({ tone, children }: { tone: Tone; children: ReactNode }) {
  return <div className={`docs-callout docs-callout-${tone}`}>{children}</div>;
}

export function CodeBlock({ label, children }: { label?: string; children: ReactNode }) {
  return (
    <div className="docs-code-block">
      {label && <div className="docs-code-label">{label}</div>}
      <pre>{children}</pre>
    </div>
  );
}

// Spans de destaque de sintaxe dentro de um CodeBlock — mesma paleta do
// antigo docs.html (chave/string/número/booleano/comentário).
export function Key({ children }: { children: ReactNode }) { return <span className="docs-hl-key">{children}</span>; }
export function Str({ children }: { children: ReactNode }) { return <span className="docs-hl-str">{children}</span>; }
export function Num({ children }: { children: ReactNode }) { return <span className="docs-hl-num">{children}</span>; }
export function Bool({ children }: { children: ReactNode }) { return <span className="docs-hl-bool">{children}</span>; }
export function Cmt({ children }: { children: ReactNode }) { return <span className="docs-hl-cmt">{children}</span>; }

export function CheckCard({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="docs-check-card">
      <div className="docs-check-header">{title}</div>
      <div className="docs-check-body">{children}</div>
    </div>
  );
}

export function Steps({ children }: { children: ReactNode }) {
  return <div className="docs-steps">{children}</div>;
}

export function Step({ num, title, children }: { num: number; title: string; children: ReactNode }) {
  return (
    <div className="docs-step">
      <div className="docs-step-num">{num}</div>
      <div className="docs-step-body">
        <h4>{title}</h4>
        <p>{children}</p>
      </div>
    </div>
  );
}

type Method = 'GET' | 'POST' | 'PUT' | 'DELETE' | 'WS';

export function ApiEndpoint({ method, path, note }: { method: Method; path: string; note?: string }) {
  return (
    <div className="docs-api-endpoint">
      <span className={`docs-method docs-method-${method.toLowerCase()}`}>{method}</span>
      <span>{path}</span>
      {note && <span className="docs-api-note">{note}</span>}
    </div>
  );
}

export function Flow({ steps }: { steps: string[] }) {
  return (
    <div className="docs-flow">
      {steps.map((s, i) => (
        <Fragment key={s}>
          {i > 0 && <span className="docs-flow-arrow">→</span>}
          <div className="docs-flow-step">{s}</div>
        </Fragment>
      ))}
    </div>
  );
}
