package com.asteriskia.domain.user;

import com.asteriskia.domain.audit.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    private final AuditService      auditService;
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
    public ResponseEntity<?> createUser(@Valid @RequestBody CreateUserRequest req,
                                        HttpServletRequest httpRequest) {
        if (userRepo.findByUsername(req.username()).isPresent()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Username já existe: " + req.username()));
        }

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
        auditService.log(httpRequest, "USER_CREATE",
                "Usuário criado: " + saved.getUsername() + " (ramal " + saved.getExtension() + ", perfil " + saved.getRole() + ")", true);

        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(saved));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Atualiza nome, senha e/ou status do usuário")
    public ResponseEntity<?> updateUser(@PathVariable Integer id,
                                        @Valid @RequestBody UpdateUserRequest req,
                                        HttpServletRequest httpRequest) {
        return userRepo.findById(id)
                .map(user -> {
                    StringBuilder changes = new StringBuilder();
                    if (req.displayName() != null) {
                        changes.append("nome='").append(req.displayName()).append("' ");
                        user.setDisplayName(req.displayName());
                    }
                    if (req.password() != null && !req.password().isBlank()) {
                        changes.append("senha-alterada ");
                        user.setPasswordHash(ENCODER.encode(req.password()));
                    }
                    if (req.isActive() != null) {
                        changes.append("ativo=").append(req.isActive()).append(" ");
                        user.setIsActive(req.isActive());
                    }
                    if (req.role() != null) {
                        changes.append("role=").append(req.role()).append(" ");
                        user.setRole(req.role());
                    }
                    AppUser updated = userRepo.save(user);
                    auditService.log(httpRequest, "USER_UPDATE",
                            "Usuário '" + user.getUsername() + "' atualizado: " + changes.toString().trim(), true);
                    return ResponseEntity.ok(UserResponse.from(updated));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Desativa usuário (soft delete — preserva o ramal)")
    public ResponseEntity<Void> deactivateUser(@PathVariable Integer id,
                                               HttpServletRequest httpRequest) {
        return userRepo.findById(id)
                .map(user -> {
                    user.setIsActive(false);
                    userRepo.save(user);
                    log.info("Usuário desativado: {} (ramal {})", user.getUsername(), user.getExtension());
                    auditService.log(httpRequest, "USER_DELETE",
                            "Usuário '" + user.getUsername() + "' desativado (ramal " + user.getExtension() + ")", true);
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
