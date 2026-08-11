package com.asteriskia.config;

/**
 * Lançada quando uma entidade buscada por ID/chave não existe. Mapeada para HTTP 404 pelo {@link
 * GlobalExceptionHandler} — distinta de {@link RuntimeException} genérica, que mapeia para 500.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
