package com.asteriskia.integration.ad;

import java.util.List;

/** LdapUserAttributes — atributos consultáveis de um usuário do AD, já normalizados. */
public record LdapUserAttributes(
        String samAccountName,
        String displayName,
        String department,
        String office,
        String title,
        List<String> memberOf,
        String managerSam,
        String email,
        String telephoneNumber,
        String employeeId) {}
