package com.asteriskia.domain.callcenter.flow.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.asteriskia.domain.masterdata.BusinessUnitRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * CallCenterAudioServiceTest — upload da biblioteca de áudios do Flow Builder (Fase 5c). Usa o
 * {@code ffmpeg}/{@code ffprobe} reais instalados no ambiente (mesmo binário usado em produção,
 * ver {@code InsightsController}) em vez de mockar o processo — é o único jeito de garantir que a
 * transcodificação realmente acontece e que um arquivo corrompido é rejeitado de verdade.
 */
class CallCenterAudioServiceTest {

    @TempDir Path libraryDir;

    private CcAudioFileRepository audioFileRepository;
    private CallCenterAudioService service;

    @BeforeEach
    void setUp() {
        audioFileRepository = mock(CcAudioFileRepository.class);
        BusinessUnitRepository businessUnitRepository = mock(BusinessUnitRepository.class);
        when(audioFileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new CallCenterAudioService(audioFileRepository, businessUnitRepository);
        ReflectionTestUtils.setField(service, "audioLibraryPath", libraryDir.toAbsolutePath().toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var files = Files.list(libraryDir)) {
            for (Path f : files.toList()) {
                Files.deleteIfExists(f);
            }
        }
    }

    /** WAV mínimo válido (header + alguns frames de silêncio 44.1kHz/16-bit stereo) — ffmpeg
     * precisa reconhecer o container pra converter de verdade. */
    private byte[] buildMinimalWav() {
        int sampleRate = 44100;
        int numSamples = 4410; // 0.1s
        int byteRate = sampleRate * 2 * 2;
        int dataSize = numSamples * 2 * 2;
        var buf = java.nio.ByteBuffer.allocate(44 + dataSize).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes());
        buf.putInt(36 + dataSize);
        buf.put("WAVE".getBytes());
        buf.put("fmt ".getBytes());
        buf.putInt(16);
        buf.putShort((short) 1); // PCM
        buf.putShort((short) 2); // stereo
        buf.putInt(sampleRate);
        buf.putInt(byteRate);
        buf.putShort((short) 4); // block align
        buf.putShort((short) 16); // bits per sample
        buf.put("data".getBytes());
        buf.putInt(dataSize);
        buf.position(44 + dataSize);
        return buf.array();
    }

    @Test
    @DisplayName("extensão não permitida é rejeitada antes de tocar em disco")
    void extensaoNaoPermitida_rejeitada() {
        var file = new MockMultipartFile("file", "malicioso.exe", "application/octet-stream", new byte[] {1, 2, 3});

        assertThatThrownBy(() -> service.upload(file, "teste", null, "kaio"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("não permitida");
    }

    @Test
    @DisplayName("upload de áudio válido é transcodificado para PCM 8kHz mono e salvo na biblioteca")
    void uploadValido_transcodificaESalva() throws IOException {
        var file = new MockMultipartFile("file", "boas-vindas.wav", "audio/wav", buildMinimalWav());

        var result = service.upload(file, "Boas-vindas", null, "kaio");

        assertThat(result.name()).isEqualTo("Boas-vindas");
        assertThat(result.fileName()).startsWith("audio-");
        Path converted = libraryDir.resolve(result.fileName() + ".wav");
        assertThat(Files.exists(converted)).isTrue();
        assertThat(Files.size(converted)).isGreaterThan(0);
    }

    @Test
    @DisplayName("upload de arquivo corrompido (não é áudio de verdade) não deixa nada em disco")
    void uploadCorrompido_naoDeixaNadaEmDisco() throws IOException {
        var file = new MockMultipartFile("file", "corrompido.wav", "audio/wav", "isto não é um áudio".getBytes());

        assertThatThrownBy(() -> service.upload(file, "teste", null, "kaio"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("converter");

        try (var files = Files.list(libraryDir)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    @DisplayName("resolveSoundPath devolve vazio para id inexistente, sem lançar exceção")
    void resolveSoundPath_idInexistente_retornaVazio() {
        when(audioFileRepository.findById(999L)).thenReturn(Optional.empty());

        assertThat(service.resolveSoundPath(999L)).isEmpty();
    }
}
