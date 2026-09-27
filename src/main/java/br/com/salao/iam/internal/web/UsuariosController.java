package br.com.salao.iam.internal.web;

import org.springframework.web.bind.annotation.PutMapping;
import br.com.salao.iam.internal.application.DefinirTelefonesDoUsuarioUseCase;
import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.UsuarioResumo;
import br.com.salao.iam.internal.application.AlterarPerfilDoUsuarioUseCase;
import br.com.salao.iam.internal.application.CriarUsuarioUseCase;
import br.com.salao.iam.internal.application.DefinirAtivacaoDoUsuarioUseCase;
import br.com.salao.iam.internal.application.DefinirSenhaDoUsuarioUseCase;
import br.com.salao.iam.internal.application.EditarUsuarioUseCase;
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
    private final EditarUsuarioUseCase editar;
    private final AlterarPerfilDoUsuarioUseCase alterarPerfil;
    private final DefinirAtivacaoDoUsuarioUseCase definirAtivacao;
    private final DefinirSenhaDoUsuarioUseCase definirSenha;
    private final ResetarSegundoFatorUseCase resetarMfa;
    private final DefinirTelefonesDoUsuarioUseCase definirTelefones;

    public UsuariosController(CriarUsuarioUseCase criar, ListarUsuariosUseCase listar,
                              EditarUsuarioUseCase editar,
                              AlterarPerfilDoUsuarioUseCase alterarPerfil,
                              DefinirAtivacaoDoUsuarioUseCase definirAtivacao,
                              DefinirSenhaDoUsuarioUseCase definirSenha,
                              ResetarSegundoFatorUseCase resetarMfa,
                              DefinirTelefonesDoUsuarioUseCase definirTelefones) {
        this.criar = criar;
        this.listar = listar;
        this.editar = editar;
        this.alterarPerfil = alterarPerfil;
        this.definirAtivacao = definirAtivacao;
        this.definirSenha = definirSenha;
        this.resetarMfa = resetarMfa;
        this.definirTelefones = definirTelefones;
    }

    /**
     * RT-IAM-010 — a lista inteira, sempre. Vazia é o pedido de TIRAR os números da conta.
     *
     * <p>O teto de dois não está aqui: quem o garante é o banco (RN-IAM-026), e um {@code @Size}
     * duplicaria a regra num lugar que a requisição seguinte pode contornar.
     */
    public record TelefonesRequest(List<@Size(max = 40) String> telefones) {
    }

    public record CriarUsuarioRequest(
            @NotBlank @Size(max = 200) String nome,
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 200) String senha,
            @NotNull Perfil perfil) {
    }

    /** RN-IAM-024 — sem perfil e sem senha: os dois têm rota própria, com trava própria. */
    public record EditarUsuarioRequest(
            @NotBlank @Size(max = 200) String nome,
            @NotBlank @Email @Size(max = 320) String email) {
    }

    public record SenhaRequest(@NotBlank @Size(max = 200) String senha) {
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

    /** RN-IAM-024 — corrigir a ficha. Trocar o e-mail encerra as sessões da pessoa. */
    @PutMapping("/{id}")
    public ResponseEntity<Void> editar(@PathVariable UUID id,
                                       @Valid @RequestBody EditarUsuarioRequest requisicao) {
        editar.executar(id, requisicao.nome(), requisicao.email());
        return ResponseEntity.noContent().build();
    }

    /**
     * RN-IAM-025 — o administrador define a senha de outra pessoa.
     *
     * <p>{@code POST}, e não {@code PUT}: não é um recurso que se lê de volta — é um ato, com
     * efeito colateral (as sessões daquela pessoa caem).
     */
    @PostMapping("/{id}/senha")
    public ResponseEntity<Void> definirSenha(@PathVariable UUID id,
                                             @Valid @RequestBody SenhaRequest requisicao,
                                             @AuthenticationPrincipal Jwt jwt) {
        definirSenha.executar(id, requisicao.senha(), UUID.fromString(jwt.getSubject()));
        return ResponseEntity.noContent().build();
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

    /** RT-IAM-010 — os números pelos quais o agente reconhece esta pessoa. */
    @PutMapping("/{id}/telefones")
    public ResponseEntity<Void> definirTelefones(@PathVariable UUID id,
                                                 @Valid @RequestBody TelefonesRequest requisicao) {
        definirTelefones.executar(id, requisicao.telefones());
        return ResponseEntity.noContent().build();
    }
}
