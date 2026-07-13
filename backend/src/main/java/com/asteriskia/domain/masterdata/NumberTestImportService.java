package com.asteriskia.domain.masterdata;

import com.asteriskia.domain.connectivity.NumberTest;
import com.asteriskia.domain.connectivity.NumberTestRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * NumberTestImportService — importação em lote de Testes de Conectividade via CSV/XLSX, extraída de
 * MasterDataController (fase 5 da refatoração). Colunas esperadas: numero | business_unit | cliente
 * | operacao | segmento | horario_inicio | intervalo_minutos | quantidade | ativo.
 */
@Service
@RequiredArgsConstructor
public class NumberTestImportService {

    private final BusinessUnitRepository buRepo;
    private final ClientRepository clientRepo;
    private final OperationRepository opRepo;
    private final SegmentRepository segRepo;
    private final NumberTestRepository numberTestRepo;

    public record ImportResult(List<NumberTest> saved, List<Map<String, Object>> errors) {}

    /**
     * Faz o parsing linha a linha e persiste tudo de uma vez ao final (em vez de um save por linha)
     * — o endpoint é justamente para importação em lote. Lança IllegalArgumentException se o
     * arquivo não tiver cabeçalho; erros de parsing por linha são coletados em {@code errors} e não
     * interrompem a importação das demais.
     */
    @Transactional
    public ImportResult importFromCsv(MultipartFile file) throws IOException {
        Map<String, BusinessUnit> buMap =
                buRepo.findAll().stream()
                        .collect(
                                Collectors.toMap(
                                        b -> norm(b.getName()), Function.identity(), (a, b) -> a));
        Map<String, Client> cliMap =
                clientRepo.findAll().stream()
                        .collect(
                                Collectors.toMap(
                                        c -> norm(c.getName()), Function.identity(), (a, b) -> a));
        Map<String, Operation> opMap =
                opRepo.findAll().stream()
                        .collect(
                                Collectors.toMap(
                                        o -> norm(o.getName()), Function.identity(), (a, b) -> a));
        Map<String, Segment> segMap =
                segRepo.findAll().stream()
                        .collect(
                                Collectors.toMap(
                                        s -> norm(s.getName()), Function.identity(), (a, b) -> a));

        List<NumberTest> toSave = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();

        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IllegalArgumentException("Arquivo sem cabeçalho.");
            }

            int lineNumber = 1;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                String[] cols = line.split("[;,\\t]", -1);
                try {
                    String phone = col(cols, 0).replaceAll("[^+\\d]", "");
                    String buName = col(cols, 1);
                    String cliName = col(cols, 2);
                    String opName = col(cols, 3);
                    String segName = col(cols, 4);
                    String timeStr = col(cols, 5);
                    int interval = Integer.parseInt(col(cols, 6).replaceAll("[^\\d]", ""));
                    int quantity = Integer.parseInt(col(cols, 7).replaceAll("[^\\d]", ""));
                    String actStr = col(cols, 8);

                    if (phone.isBlank()) throw new IllegalArgumentException("Número vazio");
                    BusinessUnit bu = buMap.get(norm(buName));
                    Client cli = cliMap.get(norm(cliName));
                    Operation op = opMap.get(norm(opName));
                    Segment seg = segMap.get(norm(segName));
                    if (bu == null)
                        throw new IllegalArgumentException("BU não encontrada: '" + buName + "'");
                    if (cli == null)
                        throw new IllegalArgumentException(
                                "Cliente não encontrado: '" + cliName + "'");
                    if (op == null)
                        throw new IllegalArgumentException(
                                "Operação não encontrada: '" + opName + "'");
                    if (seg == null)
                        throw new IllegalArgumentException(
                                "Segmento não encontrado: '" + segName + "'");

                    String t = timeStr.trim();
                    if (t.matches("\\d{1,2}:\\d{2}")) t += ":00";
                    boolean active =
                            !"false".equalsIgnoreCase(actStr.trim())
                                    && !"nao".equals(norm(actStr.trim()))
                                    && !"0".equals(actStr.trim());

                    toSave.add(
                            NumberTest.builder()
                                    .phoneNumber(phone)
                                    .businessUnit(bu)
                                    .client(cli)
                                    .operation(op)
                                    .segment(seg)
                                    .startTime(LocalTime.parse(t))
                                    .intervalMinutes(interval)
                                    .quantity(quantity)
                                    .isActive(active)
                                    .build());

                } catch (Exception e) {
                    errors.add(
                            Map.of("linha", lineNumber, "conteudo", line, "erro", e.getMessage()));
                }
            }
        }

        List<NumberTest> saved = numberTestRepo.saveAll(toSave);
        return new ImportResult(saved, errors);
    }

    private static String col(String[] cols, int i) {
        return (i < cols.length) ? cols[i].trim().replaceAll("^\"|\"$", "") : "";
    }

    private static String norm(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s.trim().toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")
                .replaceAll("\\s+", " ");
    }
}
