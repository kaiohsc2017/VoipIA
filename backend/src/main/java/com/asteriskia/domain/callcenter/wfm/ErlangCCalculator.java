package com.asteriskia.domain.callcenter.wfm;

import org.springframework.stereotype.Component;

/**
 * ErlangCCalculator — Motor de cálculo matemático da fórmula de Erlang C para Call Centers.
 * Calcula intensidade de tráfego, probabilidade de espera (Pw), SLA previsto (%) e dimensionamento
 * de agentes necessários para cumprir a meta de nível de serviço (ex.: 80/20).
 */
@Component
public class ErlangCCalculator {

    /**
     * Calcula a intensidade de tráfego (Erlangs) = (taxa de chamadas/hora * AHT em segundos) / 3600.
     */
    public double calculateTrafficIntensity(double callsPerHour, double ahtSeconds) {
        if (callsPerHour <= 0 || ahtSeconds <= 0) {
            return 0.0;
        }
        return (callsPerHour * ahtSeconds) / 3600.0;
    }

    /**
     * Calcula a probabilidade de uma chamada ter que esperar na fila (fórmula de Erlang C).
     * C(m, A) = ( (A^m / m!) * (m / (m - A)) ) / ( sum_{k=0}^{m-1} (A^k / k!) + (A^m / m!) * (m / (m - A)) )
     */
    public double calculateWaitProbability(int agents, double trafficIntensity) {
        if (agents <= 0 || trafficIntensity <= 0) {
            return 0.0;
        }
        if (agents <= trafficIntensity) {
            return 1.0; // Sistema sobrecarregado
        }

        double sum = 0.0;
        for (int k = 0; k < agents; k++) {
            sum += Math.pow(trafficIntensity, k) / factorial(k);
        }

        double lastTerm = (Math.pow(trafficIntensity, agents) / factorial(agents)) * (agents / (agents - trafficIntensity));
        return lastTerm / (sum + lastTerm);
    }

    /**
     * Calcula o Nível de Serviço previsto (SLA % atendido dentro do tempo alvo em segundos).
     * SLA = 1 - Pw * exp(-(m - A) * (targetTime / AHT))
     */
    public double calculateServiceLevel(int agents, double trafficIntensity, double ahtSeconds, double targetTimeSeconds) {
        if (trafficIntensity <= 0) {
            return 100.0;
        }
        if (agents <= trafficIntensity) {
            return 0.0;
        }
        double pw = calculateWaitProbability(agents, trafficIntensity);
        double exponent = -(agents - trafficIntensity) * (targetTimeSeconds / ahtSeconds);
        double sla = 1.0 - (pw * Math.exp(exponent));
        return Math.max(0.0, Math.min(100.0, sla * 100.0));
    }

    /**
     * Dimensiona o número mínimo de agentes necessários para cumprir a meta de SLA (ex: 80% em 20s).
     */
    public int calculateRequiredAgents(double callsPerHour, double ahtSeconds, double targetSlaPercent, double targetTimeSeconds) {
        double intensity = calculateTrafficIntensity(callsPerHour, ahtSeconds);
        if (intensity <= 0) {
            return 1;
        }
        int minAgents = (int) Math.floor(intensity) + 1;
        for (int m = minAgents; m <= minAgents + 100; m++) {
            double sla = calculateServiceLevel(m, intensity, ahtSeconds, targetTimeSeconds);
            if (sla >= targetSlaPercent) {
                return m;
            }
        }
        return minAgents + 10;
    }

    private double factorial(int n) {
        if (n <= 1) return 1.0;
        double result = 1.0;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }
}
