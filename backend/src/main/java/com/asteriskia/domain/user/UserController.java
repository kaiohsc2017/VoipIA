package com.asteriskia.domain.user;

import com.asteriskia.domain.audit.AuditService;
import com.asteriskia.domain.callcenter.CallCenterAgentProvisioningService;
import com.asteriskia.domain.masterdata.BusinessUnit;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * UserController — CRUD de usuários do sistema VoipIA.
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
    private final AuditService auditService;
    private final UserService userService;
    private final CallCenterAgentProvisioningService agentProvisioningService;
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(10);

    /** Fase 12.1 — checagem explícita de autoridade, em complemento ao matcher de rota
     * (PERM_WRITE_telecom.users), exigida só quando o request tenta vincular filas ao agente. */
    private boolean hasCallCenterQueueWriteAccess() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null
                && auth.getAuthorities().stream()
                        .anyMatch(
                                a ->
                                        "ROLE_ADMIN".equals(a.getAuthority())
                                                || "PERM_WRITE_callcenter.filas".equals(a.getAuthority()));
    }

    /**
     * Achado de segurança (security-reviewer): a rota é protegida também por
     * PERM_WRITE_telecom.users (não só ROLE_ADMIN) — sem esta checagem, um grupo customizado
     * com essa permissão (mas sem ser ADMIN) conseguiria se auto-promover ou promover qualquer
     * outro usuário atribuindo o grupo "Administradores" (id=1) via accessGroupId, ou role="ADMIN"
     * — escalada de privilégio vertical. Atribuir grupo de acesso/papel ADMIN é operação de gestão
     * de RBAC, tratada como ROLE_ADMIN puro em todo o resto do sistema (ex: /access-groups/**).
     */
    private boolean isAdminCaller() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null
                && auth.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    // Ramal inicial — o primeiro usuário recebe 9001
    private static final int EXTENSION_START = 9001;

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
        // Achado de segurança (security-reviewer, Fase 12): a rota é protegida só por
        // PERM_WRITE_telecom.users — sem esta checagem, um grupo customizado com essa permissão
        // (mas sem PERM_WRITE_callcenter.filas) conseguiria vincular agentes a qualquer fila só
        // por passar queueMemberships aqui, exercendo uma permissão que não lhe foi concedida.
        if (req.queueMemberships() != null && !req.queueMemberships().isEmpty() && !hasCallCenterQueueWriteAccess()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("Vincular atendente a filas requer permissão de escrita em Filas do Call Center."));
        }
        boolean requestedAdmin = "ADMIN".equals(req.role());
        if ((req.accessGroupId() != null || requestedAdmin) && !isAdminCaller()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("Atribuir grupo de acesso customizado ou perfil ADMIN requer ROLE_ADMIN."));
        }
        if (userRepo.findByUsername(req.username()).isPresent()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Username já existe: " + req.username()));
        }

        boolean accessIndeterminate = Boolean.TRUE.equals(req.accessIndeterminate());
        Set<BusinessUnit> businessUnits;
        com.asteriskia.domain.accessgroup.AccessGroup accessGroup;
        int extension = userRepo.findNextExtension(EXTENSION_START);
        String role = req.role() != null ? req.role() : "USER";
        try {
            userService.validateAccessWindow(req.accessExpiresAt(), accessIndeterminate);
            businessUnits = userService.resolveBusinessUnits(req.businessUnitIds());
            accessGroup = userService.resolveAccessGroup(req.accessGroupId(), role);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }

        AppUser user =
                AppUser.builder()
                        .username(req.username())
                        .passwordHash(ENCODER.encode(req.password()))
                        .displayName(req.displayName())
                        .extension(extension)
                        .isActive(true)
                        .role(role)
                        .accessGroup(accessGroup)
                        .businessUnits(businessUnits)
                        .accessExpiresAt(accessIndeterminate ? null : req.accessExpiresAt())
                        .accessIndeterminate(accessIndeterminate)
                        .firstLoginCompleted(false)
                        .build();

        AppUser saved = userRepo.save(user);
        log.info("Usuário criado: {} → ramal {}", saved.getUsername(), saved.getExtension());

        // Fase 12.1 — provisiona o agente do Call Center na MESMA transação (classe é
        // @Transactional): se a alocação de ramal ou o vínculo às filas falhar, o rollback
        // desfaz também a criação do AppUser — nunca fica "usuário criado, agente não".
        if (Boolean.TRUE.equals(req.callCenterAgent())) {
            var businessUnitId = req.businessUnitIds() == null || req.businessUnitIds().isEmpty()
                    ? null
                    : req.businessUnitIds().get(0);
            agentProvisioningService.provisionForUser(
                    saved.getId(), saved.getDisplayName(), businessUnitId, req.queueMemberships());
        }

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
        boolean requestedAdmin = "ADMIN".equals(req.role());
        if ((req.accessGroupId() != null || requestedAdmin) && !isAdminCaller()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("Atribuir grupo de acesso customizado ou perfil ADMIN requer ROLE_ADMIN."));
        }
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
                user.setBusinessUnits(userService.resolveBusinessUnits(req.businessUnitIds()));
            }
            if (req.accessIndeterminate() != null || req.accessExpiresAt() != null) {
                boolean indeterminate =
                        req.accessIndeterminate() != null
                                ? req.accessIndeterminate()
                                : Boolean.TRUE.equals(user.getAccessIndeterminate());
                LocalDate expiresAt = indeterminate ? null : req.accessExpiresAt();
                userService.validateAccessWindow(expiresAt, indeterminate);
                user.setAccessIndeterminate(indeterminate);
                user.setAccessExpiresAt(expiresAt);
            }
            if (req.accessGroupId() != null) {
                user.setAccessGroup(userService.resolveAccessGroup(req.accessGroupId(), req.role()));
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
            // accessGroupId explícito (tratado acima) tem precedência — só recai no fallback
            // binário pelo role legado quando nenhum grupo customizado foi selecionado.
            if (req.accessGroupId() == null) {
                user.setAccessGroup(userService.resolveGroupForRole(req.role()));
            }
        }
        if (req.accessGroupId() != null) {
            changes.append("accessGroupId=").append(req.accessGroupId()).append(" ");
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
                            // Fase 12.1 — se o usuário tiver um agente do Call Center vinculado,
                            // remove das filas ARA (senão o Asterisk continua tocando um ramal
                            // desligado) e desativa o CcAgent, preservando a linha para histórico
                            // de relatórios (Fase 9). Sem-op se não houver agente.
                            agentProvisioningService.deactivateForUser(user.getId());
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

    private String extensionPasswordFor(AppUser u) {
        return "webrtc" + u.getExtension() + "pass";
    }
}
