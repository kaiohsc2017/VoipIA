package com.asteriskia.domain.call;

/** Resposta com o ID interno e a chave do issue Jira. */
public record RegisterCallResponse(Long id, String jiraIssueKey) {}
