package br.com.salao.iam.internal.web;

import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.UsuarioResumo;
import br.com.salao.iam.internal.application.AlterarPerfilDoUsuarioUseCase;
import br.com.salao.iam.internal.application.CriarUsuarioUseCase;
import br.com.salao.iam.internal.application.DefinirAtivacaoDoUsuarioUseCase;
import br.com.salao.iam.internal.application.ListarUsuariosUseCase;
import br.com.salao.iam.internal.application.ResetarSegundoFatorUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-IAM-007 — gestão de usuários.
 *
 * <p>Transporte apenas. A autorização vive nos casos de uso, com {@code @PreAuthorize} sobre
 * permissão — é o que faz a regra valer também para o bot da Fase 4, que não passa por aqui.
 */
@RestController
@RequestMapping("/api/v1/usuarios")
public class UsuariosController {

    private final CriarUsuarioUseCase criar;
    private final ListarUsuariosUseCase listar;
    private final AlterarPerfilDoUsuarioUseCase alterarPerfil;
    private final DefinirAtivacaoDoUsuarioUseCase definirAtivacao;
    private final ResetarSegundoFatorUseCase resetarMfa;

    public UsuariosController(CriarUsuarioUseCase criar, ListarUsuariosUseCase listar,
                              AlterarPerfilDoUsuarioUseCase alterarPerfil,
                              DefinirAtivacaoDoUsuarioUseCase definirAtivacao,
                              ResetarSegundoFatorUseCase resetarMfa) {
        this.criar = criar;
        this.listar = listar;
        this.alterarPerfil = alterarPerfil;
        this.definirAtivacao = definirAtivacao;
        this.resetarMfa = resetarMfa;
    }

    public record CriarUsuarioRequest(
            @NotBlank @Size(max = 200) String nome,
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 200) String senha,
            @NotNull Perfil perfil) {
    }

    public record PerfilRequest(@NotNull Perfil perfil) {
    }

    public record AtivacaoRequest(@NotNull Boolean ativo) {
    }

    @GetMapping
    public List<UsuarioResumo> listar() {
        return listar.executar();
    }

    @PostMapping
    public ResponseEntity<Void> criar(@Valid @RequestBody CriarUsuarioRequest requisicao) {
        UUID id = criar.executar(requisicao.nome(), requisicao.email(), requisicao.senha(),
                requisicao.perfil());
        return ResponseEntity.created(URI.create("/api/v1/usuarios/" + id)).build();
    }

    @PatchMapping("/{id}/perfil")
    public ResponseEntity<Void> alterarPerfil(@PathVariable UUID id,
                                              @Valid @RequestBody PerfilRequest requisicao,
                                              @AuthenticationPrincipal Jwt jwt) {
        alterarPerfil.executar(id, requisicao.perfil(), UUID.fromString(jwt.getSubject()));
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/ativacao")
    public ResponseEntity<Void> definirAtivacao(@PathVariable UUID id,
                                                @Valid @RequestBody AtivacaoRequest requisicao,
                                                @AuthenticationPrincipal Jwt jwt) {
        definirAtivacao.executar(id, requisicao.ativo(), UUID.fromString(jwt.getSubject()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/resetar-mfa")
    public ResponseEntity<Void> resetarMfa(@PathVariable UUID id,
                                           @AuthenticationPrincipal Jwt jwt) {
        resetarMfa.executar(id, UUID.fromString(jwt.getSubject()));
        return ResponseEntity.noContent().build();
    }
}
