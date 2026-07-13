package com.asteriskia.domain.user;

import com.asteriskia.domain.accessgroup.AccessGroup;
import com.asteriskia.domain.accessgroup.AccessGroupRepository;
import com.asteriskia.domain.audit.AuditService;
import com.asteriskia.domain.masterdata.BusinessUnit;
import com.asteriskia.domain.masterdata.BusinessUnitRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * UserController — CRUD de usuários do sistema AsteriskIA.
 *
 * <p>POST /api/v1/users → cria usuário + atribui próximo ramal disponível (a partir de 9001) GET
 * /api/v1/users → lista todos os usuários GET /api/v1/users/{id} → busca por ID PUT
 * /api/v1/users/{id} → atualiza nome/senha/status DELETE /api/v1/users/{id} → desativa usuário
 * (soft delete)
 *
 * <p>{@code @Transactional} em nível de classe: AppUser carrega businessUnits como coleção LAZY
 * (V26) e é serializado diretamente pelo Jackson — sem uma sessão Hibernate aberta durante a
 * serialização, o acesso lazy fora de transação lança LazyInitializationException
 * (spring.jpa.open-in-view=false neste projeto).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Transactional
public class UserController {

    private final AppUserRepository userRepo;
    private final AccessGroupRepository accessGroupRepo;
    private final BusinessUnitRepository businessUnitRepo;
    private final AuditService auditService;
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(10);

    // Ramal inicial — o primeiro usuário recebe 9001
    private static final int EXTENSION_START = 9001;

    // Regra de negócio: acesso com prazo determinado nunca passa de 60 dias.
    private static final int MAX_ACCESS_DAYS = 60;

    // Grupos de sistema seedados na V22 — usados enquanto o role legado
    // (ADMIN|USER) ainda pilota a UI de usuários (até a Fase 5 do RBAC granular).
    private static final int GROUP_ADMINISTRADORES = 1;
    private static final int GROUP_USUARIOS = 2;

    private AccessGroup resolveGroupForRole(String role) {
        int groupId = "ADMIN".equals(role) ? GROUP_ADMINISTRADORES : GROUP_USUARIOS;
        return accessGroupRepo
                .findById(groupId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Grupo de acesso seed ausente: id=" + groupId));
    }

    /** Resolve os IDs de BU informados, validando que todos existem. */
    private Set<BusinessUnit> resolveBusinessUnits(List<Integer> ids) {
        List<BusinessUnit> found = businessUnitRepo.findAllById(ids);
        if (found.size() != Set.copyOf(ids).size()) {
            throw new IllegalArgumentException(
                    "Uma ou mais Unidades de Negócio informadas não existem.");
        }
        return new HashSet<>(found);
    }

    /**
     * Valida a regra "expiração de acesso XOR indeterminado": exatamente um dos dois deve estar
     * preenchido — indeterminado=true com data ausente, ou uma data futura de até 60 dias com
     * indeterminado=false.
     */
    private void validateAccessWindow(LocalDate accessExpiresAt, boolean accessIndeterminate) {
        if (accessIndeterminate) {
            if (accessExpiresAt != null) {
                throw new IllegalArgumentException(
                        "Acesso indeterminado não pode ter data de expiração.");
            }
            return;
        }
        if (accessExpiresAt == null) {
            throw new IllegalArgumentException(
                    "Informe a data de expiração do acesso ou marque acesso indeterminado.");
        }
        LocalDate today = LocalDate.now();
        if (accessExpiresAt.isBefore(today)) {
            throw new IllegalArgumentException("A data de expiração não pode estar no passado.");
        }
        if (accessExpiresAt.isAfter(today.plusDays(MAX_ACCESS_DAYS))) {
            throw new IllegalArgumentException(
                    "A data de expiração não pode passar de "
                            + MAX_ACCESS_DAYS
                            + " dias a partir de hoje.");
        }
    }

    // -----------------------------------------------------------------------
    // CRUD
    // -----------------------------------------------------------------------

    @GetMapping
    public ResponseEntity<List<UserResponse>> listUsers() {
        return ResponseEntity.ok(userRepo.findAll().stream().map(UserResponse::from).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUser(@PathVariable Integer id) {
        return userRepo.findById(id)
                .map(u -> ResponseEntity.ok(UserResponse.from(u)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Achado de segurança: GET /users devolvia extensionPassword em claro pra todos os ramais de
     * uma vez (o botão "revelar" do frontend só escondia visualmente — o valor já estava na memória
     * do componente desde o carregamento da lista). Endpoint dedicado: só busca sob demanda, ao
     * clicar "revelar" em um usuário específico.
     */
    @GetMapping("/{id}/extension-password")
    public ResponseEntity<?> getExtensionPassword(@PathVariable Integer id) {
        return userRepo.findById(id)
                .map(u -> ResponseEntity.ok(new ExtensionPasswordResponse(extensionPasswordFor(u))))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> createUser(
            @Valid @RequestBody CreateUserRequest req, HttpServletRequest httpRequest) {
        if (userRepo.findByUsername(req.username()).isPresent()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Username já existe: " + req.username()));
        }

        boolean accessIndeterminate = Boolean.TRUE.equals(req.accessIndeterminate());
        Set<BusinessUnit> businessUnits;
        try {
            validateAccessWindow(req.accessExpiresAt(), accessIndeterminate);
            businessUnits = resolveBusinessUnits(req.businessUnitIds());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }

        int extension = userRepo.findNextExtension(EXTENSION_START);
        String role = req.role() != null ? req.role() : "USER";

        AppUser user =
                AppUser.builder()
                        .username(req.username())
                        .passwordHash(ENCODER.encode(req.password()))
                        .displayName(req.displayName())
                        .extension(extension)
                        .isActive(true)
                        .role(role)
                        .accessGroup(resolveGroupForRole(role))
                        .businessUnits(businessUnits)
                        .accessExpiresAt(accessIndeterminate ? null : req.accessExpiresAt())
                        .accessIndeterminate(accessIndeterminate)
                        .firstLoginCompleted(false)
                        .build();

        AppUser saved = userRepo.save(user);
        log.info("Usuário criado: {} → ramal {}", saved.getUsername(), saved.getExtension());
        auditService.log(
                httpRequest,
                "USER_CREATE",
                "Usuário criado: "
                        + saved.getUsername()
                        + " (ramal "
                        + saved.getExtension()
                        + ", perfil "
                        + saved.getRole()
                        + ")",
                true);

        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateUser(
            @PathVariable Integer id,
            @Valid @RequestBody UpdateUserRequest req,
            HttpServletRequest httpRequest) {
        var userOpt = userRepo.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        AppUser user = userOpt.get();

        try {
            if (req.businessUnitIds() != null) {
                if (req.businessUnitIds().isEmpty()) {
                    return ResponseEntity.badRequest()
                            .body(new ErrorResponse("O usuário precisa de ao menos uma BU."));
                }
                user.setBusinessUnits(resolveBusinessUnits(req.businessUnitIds()));
            }
            if (req.accessIndeterminate() != null || req.accessExpiresAt() != null) {
                boolean indeterminate =
                        req.accessIndeterminate() != null
                                ? req.accessIndeterminate()
                                : Boolean.TRUE.equals(user.getAccessIndeterminate());
                LocalDate expiresAt = indeterminate ? null : req.accessExpiresAt();
                validateAccessWindow(expiresAt, indeterminate);
                user.setAccessIndeterminate(indeterminate);
                user.setAccessExpiresAt(expiresAt);
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }

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
            user.setAccessGroup(resolveGroupForRole(req.role()));
        }
        if (req.businessUnitIds() != null) {
            changes.append("bus=").append(req.businessUnitIds()).append(" ");
        }
        AppUser updated = userRepo.save(user);
        auditService.log(
                httpRequest,
                "USER_UPDATE",
                "Usuário '" + user.getUsername() + "' atualizado: " + changes.toString().trim(),
                true);
        return ResponseEntity.ok(UserResponse.from(updated));
    }

    /**
     * Reset de MFA pelo administrador — usado quando o usuário perde acesso ao app TOTP ou esquece
     * a senha e não consegue completar o 2FA sozinho. Diferente de TotpController.disable
     * (self-service, exige código válido), este endpoint não exige nenhuma prova do usuário-alvo —
     * só ADMIN pode chamar (SecurityConfig restringe /users/** de escrita a ROLE_ADMIN ou
     * PERM_WRITE_telecom.users).
     */
    @PostMapping("/{id}/totp/reset")
    public ResponseEntity<?> resetTotp(@PathVariable Integer id, HttpServletRequest httpRequest) {
        return userRepo.findById(id)
                .map(
                        user -> {
                            user.setTotpSecret(null);
                            user.setTotpEnabled(false);
                            userRepo.save(user);
                            log.info(
                                    "MFA resetado pelo admin para usuário '{}'",
                                    user.getUsername());
                            auditService.log(
                                    httpRequest,
                                    "USER_TOTP_RESET",
                                    "MFA resetado pelo admin para o usuário '"
                                            + user.getUsername()
                                            + "'",
                                    true);
                            return ResponseEntity.ok(UserResponse.from(user));
                        })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivateUser(
            @PathVariable Integer id, HttpServletRequest httpRequest) {
        return userRepo.findById(id)
                .map(
                        user -> {
                            user.setIsActive(false);
                            userRepo.save(user);
                            log.info(
                                    "Usuário desativado: {} (ramal {})",
                                    user.getUsername(),
                                    user.getExtension());
                            auditService.log(
                                    httpRequest,
                                    "USER_DELETE",
                                    "Usuário '"
                                            + user.getUsername()
                                            + "' desativado (ramal "
                                            + user.getExtension()
                                            + ")",
                                    true);
                            return ResponseEntity.noContent().<Void>build();
                        })
                .orElse(ResponseEntity.notFound().build());
    }

    // -----------------------------------------------------------------------
    // DTOs
    // -----------------------------------------------------------------------

    public record CreateUserRequest(
            @NotBlank(message = "Username obrigatório") String username,
            @NotBlank(message = "Senha obrigatória")
                    @Size(min = 6, message = "Senha mínima: 6 caracteres")
                    String password,
            @NotBlank(message = "Nome de exibição obrigatório") String displayName,
            String role,
            @NotEmpty(message = "Selecione ao menos uma Unidade de Negócio (BU)")
                    List<Integer> businessUnitIds,
            LocalDate accessExpiresAt,
            Boolean accessIndeterminate) {}

    public record UpdateUserRequest(
            String displayName,
            String password,
            Boolean isActive,
            String role,
            List<Integer> businessUnitIds,
            LocalDate accessExpiresAt,
            Boolean accessIndeterminate) {}

    public record UserResponse(
            Integer id,
            String username,
            String displayName,
            Integer extension,
            String extensionPassword,
            Boolean isActive,
            String role,
            String createdAt,
            List<Integer> businessUnitIds,
            String accessExpiresAt,
            Boolean accessIndeterminate,
            Boolean totpEnabled) {
        static UserResponse from(AppUser u) {
            return new UserResponse(
                    u.getId(),
                    u.getUsername(),
                    u.getDisplayName(),
                    u.getExtension(),
                    // Achado de segurança: não gravar mais a senha em claro aqui —
                    // só disponível sob demanda via GET /{id}/extension-password.
                    null,
                    u.getIsActive(),
                    u.getRole(),
                    u.getCreatedAt() != null ? u.getCreatedAt().toString() : null,
                    u.getBusinessUnits().stream().map(BusinessUnit::getId).toList(),
                    u.getAccessExpiresAt() != null ? u.getAccessExpiresAt().toString() : null,
                    u.getAccessIndeterminate(),
                    u.getTotpEnabled());
        }
    }

    public record ErrorResponse(String message) {}

    public record ExtensionPasswordResponse(String extensionPassword) {}

    private String extensionPasswordFor(AppUser u) {
        return "webrtc" + u.getExtension() + "pass";
    }
}
