package com.asteriskia.domain.pedido;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo esperado pelo endpoint de abertura de protocolo. A descrição vira o corpo de uma issue real
 * no Jira — limitada em tamanho para não gerar chamados absurdamente grandes.
 */
public record AbrirProtocoloRequest(
        @NotBlank(message = "Descrição é obrigatória")
                @Size(max = 5000, message = "Descrição não pode exceder 5000 caracteres")
                String descricao,
        String prioridade) {}
