package com.asteriskia.domain.callcenter.flow;

/**
 * FlowStatus — estado de uma versão do fluxo (Fase 5a). Só uma DRAFT por fluxo; publicar arquiva a
 * PUBLISHED anterior; rollback troca qual versão ARCHIVED volta a PUBLISHED, sem editar o grafo.
 */
public enum FlowStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED
}
