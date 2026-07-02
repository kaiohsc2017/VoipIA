package com.asteriskia.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.messaging.simp.config.ChannelRegistration;

import java.util.List;

/**
 * WebSocketConfig — WebSocket STOMP para atualizações em tempo real ao frontend.
 *
 * Segurança:
 *  - Origem restrita às origens configuradas (app.cors.allowed-origins),
 *    não mais "*" — impede conexões cross-origin de sites arbitrários.
 *  - Autenticação obrigatória no frame CONNECT via JWT (header Authorization),
 *    impedindo que qualquer cliente não autenticado assine /topic/** e receba
 *    dados de chamadas (PII/transcrições).
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtService jwtService;

    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = allowedOrigins.split("\\s*,\\s*");
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins)
                .withSockJS();
    }

    /**
     * Valida o JWT enviado nos headers do frame STOMP CONNECT. Sem token válido,
     * a conexão é rejeitada antes de qualquer SUBSCRIBE.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String auth = accessor.getFirstNativeHeader("Authorization");
                    String token = (auth != null && auth.startsWith("Bearer "))
                            ? auth.substring(7) : null;
                    if (token == null || !jwtService.isValid(token)) {
                        throw new org.springframework.messaging.MessagingException(
                                "WebSocket sem autenticação válida");
                    }
                    String username = jwtService.extractUsername(token);
                    accessor.setUser(new UsernamePasswordAuthenticationToken(username, null, List.of()));
                }
                return message;
            }
        });
    }
}
