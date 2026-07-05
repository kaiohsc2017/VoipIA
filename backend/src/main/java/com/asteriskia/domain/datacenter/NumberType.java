package com.asteriskia.domain.datacenter;

/**
 * NumberType — tipo do número cadastrado no DATACENTER.
 *
 * Determina o roteamento no Módulo Conectividade: DDR e ZERO_OITO_ZERO_ZERO
 * geram teste automático de conectividade (guias "DDR" e "0800"); WHATSAPP
 * nunca gera teste automático (não é uma chamada SIP).
 */
public enum NumberType {
    DDR,
    ZERO_OITO_ZERO_ZERO,
    WHATSAPP
}
