package com.asteriskia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AsteriskIaApplication — Ponto de entrada da aplicação.
 *
 * Estende SpringBootServletInitializer para permitir deploy
 * como WAR no Tomcat 11 externo, além de poder ser executado
 * standalone durante o desenvolvimento.
 *
 * @EnableScheduling: habilita os jobs agendados do Spring
 * usados pelo Módulo 3 (polling Zabbix).
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class AsteriskIaApplication extends SpringBootServletInitializer {

    /**
     * Entry point para execução standalone (desenvolvimento).
     */
    public static void main(String[] args) {
        SpringApplication.run(AsteriskIaApplication.class, args);
    }

    /**
     * Entry point para deploy em Tomcat 11 externo (produção).
     * O Tomcat chama configure() ao inicializar o WAR.
     */
    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(AsteriskIaApplication.class);
    }
}
