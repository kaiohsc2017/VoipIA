package com.asteriskia.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${app.jwt.refresh-expiration-days:7}")
    private int refreshExpirationDays;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional
    public String generateRefreshToken(String username) {
        // Exclui os tokens antigos do usuário (opcional, pode manter se quiser sessões simultâneas
        // limitadas)
        // refreshTokenRepository.deleteByUsername(username);

        // Gera token limpo (seguro aleatório)
        String rawToken = UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString();

        // Hash do token
        String tokenHash = hashToken(rawToken);

        RefreshToken refreshToken =
                RefreshToken.builder()
                        .username(username)
                        .tokenHash(tokenHash)
                        .expiresAt(LocalDateTime.now().plusDays(refreshExpirationDays))
                        .build();

        refreshTokenRepository.save(refreshToken);

        return rawToken;
    }

    @Transactional
    public Optional<RefreshToken> validateRefreshToken(String rawToken) {
        String tokenHash = hashToken(rawToken);
        Optional<RefreshToken> optToken = refreshTokenRepository.findByTokenHash(tokenHash);

        if (optToken.isPresent()) {
            RefreshToken token = optToken.get();
            if (token.getRevoked() || token.getExpiresAt().isBefore(LocalDateTime.now())) {
                return Optional.empty();
            }
            return Optional.of(token);
        }
        return Optional.empty();
    }

    @Transactional
    public void revokeRefreshToken(String rawToken) {
        String tokenHash = hashToken(rawToken);
        refreshTokenRepository
                .findByTokenHash(tokenHash)
                .ifPresent(
                        token -> {
                            token.setRevoked(true);
                            refreshTokenRepository.save(token);
                        });
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(encodedhash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Falha ao configurar SHA-256", e);
        }
    }

    private String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (int i = 0; i < hash.length; i++) {
            String hex = Integer.toHexString(0xff & hash[i]);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    @Scheduled(cron = "0 0 3 * * ?") // Roda todo dia às 3 da manhã
    @Transactional
    public void deleteExpiredTokens() {
        refreshTokenRepository.deleteExpired(LocalDateTime.now());
    }
}
