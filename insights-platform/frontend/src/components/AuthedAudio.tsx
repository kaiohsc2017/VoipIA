import { useEffect, useState } from 'react';
import api from '../api/client';

/**
 * Busca o áudio via api.get (anexa o JWT) e reproduz como blob — uma tag
 * <audio src="/api/..."> direta não funciona porque o endpoint exige
 * autenticação e o navegador não anexa o header Authorization nesse caso.
 */
export function AuthedAudio({ path, style, autoPlay, onError }: {
  path: string;
  style?: React.CSSProperties;
  autoPlay?: boolean;
  onError?: () => void;
}) {
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let objectUrl: string | null = null;
    api.get(path, { responseType: 'blob' })
      .then(res => {
        objectUrl = URL.createObjectURL(res.data);
        setSrc(objectUrl);
      })
      .catch(() => { setFailed(true); onError?.(); });
    return () => { if (objectUrl) URL.revokeObjectURL(objectUrl); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path]);

  if (failed) return <span style={{ fontSize: '.85rem', color: 'var(--text-muted)' }}>Erro ao carregar áudio</span>;
  if (!src) return <span className="spinner" style={{ width: 16, height: 16 }} />;
  return <audio controls autoPlay={autoPlay} src={src} style={style} />;
}
