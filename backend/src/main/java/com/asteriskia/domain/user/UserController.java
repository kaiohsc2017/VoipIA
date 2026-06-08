package com.asteriskia.domain.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * UserController — CRUD de usuários do sistema AsteriskIA.
 *
 * POST  /api/v1/users          → cria usuário + atribui próximo ramal disponível (a partir de 9001)
 * GET   /api/v1/users          → lista todos os usuários
 * GET   /api/v1/users/{id}     → busca por ID
 * PUT   /api/v1/users/{id}     → atualiza nome/senha/status
 * DELETE /api/v1/users/{id}    → desativa usuário (soft delete)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "Gerenciamento de usuários e ramais SIP WebRTC")
public class UserController {

    private final AppUserRepository userRepo;
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(10);

    // Ramal inicial — o primeiro usuário recebe 9001
    private static final int EXTENSION_START = 9001;

    // -----------------------------------------------------------------------
    // CRUD
    // -----------------------------------------------------------------------

    @GetMapping
    @Operation(summary = "Lista todos os usuários")
    public ResponseEntity<List<UserResponse>> listUsers() {
        return ResponseEntity.ok(
                userRepo.findAll().stream().map(UserResponse::from).toList()
        );
    }

    @GetMapping("/{id}")
    @Operation(summary = "Busca usuário por ID")
    public ResponseEntity<UserResponse> getUser(@PathVariable Integer id) {
        return userRepo.findById(id)
                .map(u -> ResponseEntity.ok(UserResponse.from(u)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Operation(summary = "Cria usuário — atribui próximo ramal disponível automaticamente")
    public ResponseEntity<?> createUser(@Valid @RequestBody CreateUserRequest req) {
        // Valida username único
        if (userRepo.findByUsername(req.username()).isPresent()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Username já existe: " + req.username()));
        }

        // Próximo ramal disponível (9001, 9002, ...)
        int extension = userRepo.findNextExtension(EXTENSION_START);

        AppUser user = AppUser.builder()
                .username(req.username())
                .passwordHash(ENCODER.encode(req.password()))
                .displayName(req.displayName())
                .extension(extension)
                .isActive(true)
                .role(req.role() != null ? req.role() : "USER")
                .build();

        AppUser saved = userRepo.save(user);
        log.info("Usuário criado: {} → ramal {}", saved.getUsername(), saved.getExtension());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(saved));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Atualiza nome, senha e/ou status do usuário")
    public ResponseEntity<?> updateUser(@PathVariable Integer id, @Valid @RequestBody UpdateUserRequest req) {
        return userRepo.findById(id)
                .map(user -> {
                    if (req.displayName() != null) user.setDisplayName(req.displayName());
                    if (req.password() != null && !req.password().isBlank()) {
                        user.setPasswordHash(ENCODER.encode(req.password()));
                    }
                    if (req.isActive() != null) user.setIsActive(req.isActive());
                    if (req.role() != null) user.setRole(req.role());
                    return ResponseEntity.ok(UserResponse.from(userRepo.save(user)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Desativa usuário (soft delete — preserva o ramal)")
    public ResponseEntity<Void> deactivateUser(@PathVariable Integer id) {
        return userRepo.findById(id)
                .map(user -> {
                    user.setIsActive(false);
                    userRepo.save(user);
                    log.info("Usuário desativado: {} (ramal {})", user.getUsername(), user.getExtension());
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // -----------------------------------------------------------------------
    // DTOs
    // -----------------------------------------------------------------------

    public record CreateUserRequest(
            @NotBlank(message = "Username obrigatório") String username,
            @NotBlank(message = "Senha obrigatória") @Size(min = 6, message = "Senha mínima: 6 caracteres") String password,
            @NotBlank(message = "Nome de exibição obrigatório") String displayName,
            String role
    ) {}

    public record UpdateUserRequest(
            String displayName,
            String password,
            Boolean isActive,
            String role
    ) {}

    public record UserResponse(
            Integer id,
            String username,
            String displayName,
            Integer extension,
            String extensionPassword,
            Boolean isActive,
            String role,
            String createdAt
    ) {
        static UserResponse from(AppUser u) {
            return new UserResponse(
                    u.getId(),
                    u.getUsername(),
                    u.getDisplayName(),
                    u.getExtension(),
                    "webrtc" + u.getExtension() + "pass",
                    u.getIsActive(),
                    u.getRole(),
                    u.getCreatedAt() != null ? u.getCreatedAt().toString() : null
            );
        }
    }

    public record ErrorResponse(String message) {}
}

// ---------------------------------------------------------------------------
// Repository
// ---------------------------------------------------------------------------

@Repository
interface AppUserRepository extends JpaRepository<AppUser, Integer> {

    Optional<AppUser> findByUsername(String username);

    Optional<AppUser> findByUsernameAndIsActiveTrue(String username);

    /**
     * Retorna o próximo ramal disponível a partir de {@code start}.
     * Se todos estiverem ocupados consecutivamente, usa MAX + 1.
     */
    @Query(value = """
        SELECT COALESCE(
            (SELECT s.ext FROM generate_series(:start, 9099) AS s(ext)
             WHERE s.ext NOT IN (SELECT extension FROM app_users) LIMIT 1),
            (SELECT MAX(extension) + 1 FROM app_users WHERE extension >= :start)
        )
        """, nativeQuery = true)
    int findNextExtension(int start);
}
