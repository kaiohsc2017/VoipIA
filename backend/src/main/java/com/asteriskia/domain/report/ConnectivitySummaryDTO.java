package com.asteriskia.domain.report;

public class ConnectivitySummaryDTO {
    public String buName;
    public String clientName;
    public int total;
    public int sucesso;
    public int falha;
    public long taxaSucesso;

    ConnectivitySummaryDTO(String bu, String cli, int total, int sucesso, int falha) {
        this.buName = bu;
        this.clientName = cli;
        this.total = total;
        this.sucesso = sucesso;
        this.falha = falha;
    }
}
