"""
prosody.py — Tom acústico por segmento, a partir de prosódia (pitch/energia).

Complementa o tom semântico (inferido pelo LLM a partir do texto) com um sinal
independente baseado no sinal de áudio puro: pitch (F0), energia (RMS) e sua
variação dentro de cada turno diarizado. Como o áudio é mono (sem canal
separado por locutor — ver Fase 0), o recorte por locutor vem dos timestamps
de início/fim de cada turno já diarizado, não de canal de áudio.

Os limiares abaixo são heurísticos e aproximados — foram calibrados por
inspeção, não por dataset rotulado. Rotular como "tom estimado (indicativo)"
na UI, nunca como critério único de decisão. Ajustar conforme volume real de
chamadas processadas revelar desvios sistemáticos.
"""

from __future__ import annotations

import logging

import librosa
import numpy as np

from src.stt_diarize import TranscriptSegment

logger = logging.getLogger("asteriskia.insights.prosody")

_MIN_SEGMENT_SAMPLES = 800  # ~100ms a 8kHz — abaixo disso não há sinal confiável para pitch
_FMIN_HZ = librosa.note_to_hz("C2")  # ~65Hz — piso da voz humana adulta
_FMAX_HZ = librosa.note_to_hz("C6")  # ~1047Hz — teto confortável para fala (evita picos espúrios)

# Limiares heurísticos de classificação (ver docstring do módulo)
_RMS_SILENCE_THRESHOLD = 0.01
_PITCH_STD_HIGH_RATIO = 0.20  # desvio-padrão do pitch relativo à média
_RMS_HIGH_THRESHOLD = 0.08


def _pcm_to_float(pcm: bytes) -> np.ndarray:
    return np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768.0


def _classify(pitch_mean: float, pitch_std: float, rms_mean: float) -> str:
    if rms_mean < _RMS_SILENCE_THRESHOLD:
        return "calmo"

    pitch_variability = (pitch_std / pitch_mean) if pitch_mean > 0 else 0.0

    if pitch_variability > _PITCH_STD_HIGH_RATIO and rms_mean > _RMS_HIGH_THRESHOLD:
        return "irritado" if pitch_mean > _FMIN_HZ * 1.5 else "tenso"
    if rms_mean > _RMS_HIGH_THRESHOLD and pitch_variability > _PITCH_STD_HIGH_RATIO * 0.6:
        return "empolgado"
    return "neutro"


def _segment_tone(y: np.ndarray, sr: int) -> str:
    if len(y) < _MIN_SEGMENT_SAMPLES:
        return "neutro"

    rms = librosa.feature.rms(y=y)[0]
    rms_mean = float(np.mean(rms)) if rms.size else 0.0

    try:
        f0, voiced_flag, _ = librosa.pyin(y, fmin=_FMIN_HZ, fmax=_FMAX_HZ, sr=sr)
    except Exception as e:
        logger.debug("librosa.pyin falhou no segmento (%d amostras): %s", len(y), e)
        return "neutro"

    voiced = f0[voiced_flag] if voiced_flag is not None else f0[~np.isnan(f0)]
    if voiced is None or len(voiced) == 0:
        return "neutro" if rms_mean < _RMS_HIGH_THRESHOLD else "calmo"

    pitch_mean = float(np.mean(voiced))
    pitch_std = float(np.std(voiced))
    return _classify(pitch_mean, pitch_std, rms_mean)


def compute_acoustic_tones(pcm: bytes, segments: list[TranscriptSegment], sr: int = 8000) -> list[str]:
    """Retorna o rótulo de tom acústico para cada segmento, na mesma ordem de `segments`."""
    samples = _pcm_to_float(pcm)
    tones: list[str] = []

    for seg in segments:
        start_idx = max(0, int(seg.start_ms / 1000 * sr))
        end_idx = min(len(samples), int(seg.end_ms / 1000 * sr))
        if end_idx <= start_idx:
            tones.append("neutro")
            continue
        tones.append(_segment_tone(samples[start_idx:end_idx], sr))

    return tones
