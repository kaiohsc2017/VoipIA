package com.asteriskia.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocketConfig — Configura WebSockets com STOMP.
 * Usado para enviar atualizações em tempo real para o Frontend (React).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Habilita um broker em memória simples.
        // Tópicos com prefixo "/topic" (broadcast genérico).
        config.enableSimpleBroker("/topic");
        // Prefixo para mensagens enviadas DO frontend PARA o backend (se necessário).
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Endpoint que o React (via sockjs) vai conectar.
        // setAllowedOriginPatterns para evitar CORS em dev.
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
