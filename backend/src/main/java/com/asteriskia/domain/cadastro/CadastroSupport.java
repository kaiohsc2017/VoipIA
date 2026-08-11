package com.asteriskia.domain.cadastro;

import com.asteriskia.domain.masterdata.BusinessUnit;
import com.asteriskia.domain.masterdata.BusinessUnitRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * CadastroSupport — resolução de Unidades de Negócio informadas por ID e envelope de download XLSX,
 * compartilhados entre {@link Numero0800Controller} e {@link LinhaController}, extraído de
 * CadastroController (fase 14 da refatoração). O filtro de escopo por BU em si já vivia em {@link
 * com.asteriskia.domain.masterdata.MasterDataScopeFilter} (fase 10) — ambos os controllers passam a
 * reusá-lo em vez de manter uma cópia própria.
 */
public final class CadastroSupport {

    private CadastroSupport() {}

    /** Resolve os IDs de BusinessUnit informados; vazio se algum ID não existir. */
    public static Optional<Set<BusinessUnit>> resolveBusinessUnits(
            BusinessUnitRepository buRepo, List<Integer> businessUnitIds) {
        List<Integer> ids = businessUnitIds == null ? List.of() : businessUnitIds;
        List<BusinessUnit> found = buRepo.findAllById(ids);
        if (found.size() != Set.copyOf(ids).size()) {
            return Optional.empty();
        }
        return Optional.of(new HashSet<>(found));
    }

    public static ResponseEntity<byte[]> xlsxResponse(byte[] bytes, String filename) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .contentType(
                        MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }
}
