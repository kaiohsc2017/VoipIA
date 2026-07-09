import { useEffect, useRef, useState, type ReactNode } from 'react';
import { TOC } from './toc';
import './Documentacao.css';

interface DocsLayoutProps {
  children: ReactNode;
}

export default function DocsLayout({ children }: DocsLayoutProps) {
  const [activeId, setActiveId] = useState<string>(TOC[0].items[0].id);
  const contentRef = useRef<HTMLDivElement>(null);

  // Destaca o item do sumário correspondente à seção visível — substitui o
  // listener de scroll do docs.html original por IntersectionObserver.
  useEffect(() => {
    const root = contentRef.current;
    if (!root) return;
    const sections = root.querySelectorAll<HTMLElement>('.docs-section');
    const observer = new IntersectionObserver(
      entries => {
        const visible = entries.filter(e => e.isIntersecting);
        if (visible.length > 0) {
          setActiveId(visible[0].target.id);
        }
      },
      // root = docs-content: observer detecta seções visíveis no scroll do conteúdo
      { root, rootMargin: '-5% 0px -60% 0px', threshold: 0 }
    );
    sections.forEach(s => observer.observe(s));
    return () => observer.disconnect();
  }, [children]);

  const handleNavigate = (id: string) => {
    const el = document.getElementById(id);
    if (!el || !contentRef.current) return;
    // Scroll dentro do docs-content (que tem overflow-y: auto)
    // em vez do window/body, para que o TOC fixo não se mova
    contentRef.current.scrollTo({
      top: el.offsetTop - 24,
      behavior: 'smooth',
    });
  };

  return (
    <>
      <div className="page-header">
        <h1>Documentação</h1>
        <p>Guia completo do sistema Telecom e da Plataforma de Agentes.</p>
      </div>

      <div className="docs-layout">
      <nav className="docs-toc">
        {TOC.map(group => (
          <div key={group.label}>
            <div className="docs-toc-group">{group.label}</div>
            {group.items.map(item => (
              <a
                key={item.id}
                className={`docs-toc-link${activeId === item.id ? ' active' : ''}`}
                onClick={() => handleNavigate(item.id)}
                role="button"
                tabIndex={0}
                onKeyDown={e => e.key === 'Enter' && handleNavigate(item.id)}
              >
                {item.label}
              </a>
            ))}
          </div>
        ))}
      </nav>

      <div className="docs-content" ref={contentRef}>
        <div className="docs-hero">
          <p>
            Guia completo do sistema Telecom (URA, Conectividade, Alertas, RBAC) e da Plataforma de
            Agentes (monitoramento autônomo e automação de infraestrutura).
          </p>
          <div className="docs-hero-meta">
            <div className="docs-hero-badge">Telecom <b>Spring Boot 3.3</b></div>
            <div className="docs-hero-badge">Agentes <b>FastAPI</b></div>
            <div className="docs-hero-badge">Banco <b>PostgreSQL 16</b></div>
            <div className="docs-hero-badge">Frontend <b>React 18/19</b></div>
          </div>
        </div>

        {children}

        <div className="docs-footer">
          <strong>AsteriskIA</strong> · Documentação Oficial<br />
          Telecom + Plataforma de Agentes · <code>https://app.voiphash.com.br</code>
        </div>
      </div>
      </div>
    </>
  );
}
