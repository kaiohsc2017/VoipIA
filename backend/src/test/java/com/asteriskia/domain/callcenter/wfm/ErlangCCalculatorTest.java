package com.asteriskia.domain.callcenter.wfm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ErlangCCalculatorTest {

    private ErlangCCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new ErlangCCalculator();
    }

    @Test
    void testCalculateTrafficIntensity() {
        // 60 chamadas/hora com AHT de 180s = (60 * 180) / 3600 = 3.0 Erlangs
        double intensity = calculator.calculateTrafficIntensity(60.0, 180.0);
        assertEquals(3.0, intensity, 0.001);

        // Casos de borda
        assertEquals(0.0, calculator.calculateTrafficIntensity(0.0, 180.0));
        assertEquals(0.0, calculator.calculateTrafficIntensity(60.0, 0.0));
    }

    @Test
    void testCalculateWaitProbability() {
        // Carga de 3 Erlangs com 5 agentes
        double pw = calculator.calculateWaitProbability(5, 3.0);
        assertTrue(pw > 0.0 && pw < 1.0, "Probabilidade de espera deve estar entre 0 e 1");

        // Sistema sobrecarregado (agentes <= carga)
        assertEquals(1.0, calculator.calculateWaitProbability(3, 3.0));
        assertEquals(1.0, calculator.calculateWaitProbability(2, 3.0));
    }

    @Test
    void testCalculateServiceLevel() {
        // Carga de 3 Erlangs com 6 agentes e AHT 180s, alvo 20s
        double sla = calculator.calculateServiceLevel(6, 3.0, 180.0, 20.0);
        assertTrue(sla >= 80.0, "Com 6 agentes para 3 Erlangs o SLA deve ser elevado (>80%)");

        // Sem tráfego -> SLA 100%
        assertEquals(100.0, calculator.calculateServiceLevel(5, 0.0, 180.0, 20.0));

        // Subdimensionado -> SLA 0%
        assertEquals(0.0, calculator.calculateServiceLevel(3, 3.0, 180.0, 20.0));
    }

    @Test
    void testCalculateRequiredAgents() {
        // 60 chamadas/hora, 180s AHT (3 Erlangs), alvo 80% em 20s
        int required = calculator.calculateRequiredAgents(60.0, 180.0, 80.0, 20.0);
        assertTrue(required >= 4, "Necessário pelo menos 4 ou 5 agentes para atender com SLA 80%");
    }
}
