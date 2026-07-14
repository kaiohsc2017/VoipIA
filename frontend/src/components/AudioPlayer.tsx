import { useState } from 'react';
import { AuthedAudio } from './AuthedAudio';

export function AudioPlayer({ callId }: { callId: number }) {
  const [show, setShow] = useState(false);
  if (!show) {
    return (
      <button
        className="btn btn-ghost btn-sm btn-icon"
        onClick={() => setShow(true)}
        title="Ouvir gravação"
      >▶️</button>
    );
  }
  return (
    <AuthedAudio
      path={`/calls/${callId}/audio`}
      autoPlay
      style={{ height: 28, minWidth: 180, maxWidth: 240 }}
      onError={() => setShow(false)}
    />
  );
}
