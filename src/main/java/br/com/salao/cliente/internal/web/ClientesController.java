package br.com.salao.cliente.internal.web;

import br.com.salao.cliente.api.CanalDeConsentimento;
import br.com.salao.cliente.api.ClienteResumo;
import br.com.salao.cliente.api.EstadoDoConsentimento;
import br.com.salao.cliente.api.Finalidade;
import br.com.salao.cliente.internal.application.CadastrarClienteUseCase;
import br.com.salao.cliente.internal.application.AnonimizarClienteUseCase;
import br.com.salao.cliente.internal.application.BuscarClientesUseCase;
import br.com.salao.cliente.internal.application.ConsultarClienteUseCase;
import br.com.salao.cliente.internal.application.ConsultarConsentimentosUseCase;
import br.com.salao.cliente.internal.application.RegistrarConsentimentoUseCase;
import br.com.salao.cliente.internal.application.DadosDoCliente;
import br.com.salao.cliente.internal.application.DetectarDuplicadosUseCase;
import br.com.salao.cliente.internal.application.MesclarClientesUseCase;
import br.com.salao.cliente.internal.application.PossivelDuplicado;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-CLI-001 — cadastro e consulta de cliente.
 *
 * <p>Transporte apenas. A autorização vive nos casos de uso, com {@code @PreAuthorize} sobre
 * permissão — é o que faz a regra valer também para o bot da Fase 4, que não passa por aqui.
 *
 * <p>Não existe endpoint separado de "cadastro rápido": ele é este mesmo, com menos campos
 * preenchidos. Um segundo endpoint divergiria na primeira regra nova.
 */
@RestController
@RequestMapping("/api/v1/clientes")
public class ClientesController {

    private final CadastrarClienteUseCase cadastrar;
    private final ConsultarClienteUseCase consultar;
    private final BuscarClientesUseCase buscar;
    private final DetectarDuplicadosUseCase duplicados;
    private final MesclarClientesUseCase mesclar;
    private final RegistrarConsentimentoUseCase registrarConsentimento;
    private final ConsultarConsentimentosUseCase consultarConsentimentos;
    private final AnonimizarClienteUseCase anonimizar;

    public ClientesController(CadastrarClienteUseCase cadastrar,
                              ConsultarClienteUseCase consultar,
                              BuscarClientesUseCase buscar,
                              DetectarDuplicadosUseCase duplicados,
                              MesclarClientesUseCase mesclar,
                              RegistrarConsentimentoUseCase registrarConsentimento,
                              ConsultarConsentimentosUseCase consultarConsentimentos,
                              AnonimizarClienteUseCase anonimizar) {
        this.registrarConsentimento = registrarConsentimento;
        this.consultarConsentimentos = consultarConsentimentos;
        this.anonimizar = anonimizar;
        this.cadastrar = cadastrar;
        this.consultar = consultar;
        this.buscar = buscar;
        this.duplicados = duplicados;
        this.mesclar = mesclar;
    }

    /**
     * Só {@code nome} é {@code @NotBlank}.
     *
     * <p>O telefone não tem {@code @Pattern}: validar formato aqui recusaria
     * {@code "(11) 98765-4321"} e {@code "98765-4321"}, que são exatamente como as pessoas
     * digitam. Quem sabe o que é um telefone válido é o domínio, que também sabe normalizá-lo.
     */
    public record CadastrarClienteRequest(
            @NotBlank @Size(max = 200) String nome,
            @Size(max = 60) String apelido,
            @Size(max = 30) String telefone,
            @Email @Size(max = 320) String email,
            @Size(max = 2000) String observacao) {
    }

    @PostMapping
    public ResponseEntity<Void> cadastrar(@Valid @RequestBody CadastrarClienteRequest requisicao) {
        UUID id = cadastrar.executar(new DadosDoCliente(requisicao.nome(), requisicao.apelido(),
                requisicao.telefone(), requisicao.email(), requisicao.observacao()));
        return ResponseEntity.created(URI.create("/api/v1/clientes/" + id)).build();
    }

    /**
     * RT-CLI-002 — busca por nome, apelido ou dígitos do telefone.
     *
     * <p>Um parâmetro só. Obrigar a escolher "buscar por nome" ou "buscar por telefone" seria
     * pedir uma decisão a quem está com o telefone tocando na outra mão — o domínio entende
     * sozinho pelo que foi digitado.
     *
     * <p>Termo curto devolve lista vazia, nunca erro: 422 a cada tecla faria a tela piscar.
     */
    @GetMapping
    public List<ClienteResumo> buscar(@RequestParam(name = "q", required = false) String termo,
                                      @RequestParam(required = false) Integer limite) {
        return buscar.executar(termo, limite);
    }

    /**
     * RT-CLI-003 — quem já existe e talvez seja esta pessoa.
     *
     * <p>Rota separada do cadastro, consultada <strong>enquanto se digita</strong>. Embutir a
     * checagem no {@code POST} obrigaria a escolher entre bloquear e ignorar; consultando antes,
     * quem decide é quem está olhando para a cliente.
     */
    @GetMapping("/possiveis-duplicados")
    public List<PossivelDuplicado> possiveisDuplicados(
            @RequestParam(required = false) String nome,
            @RequestParam(required = false) String telefone,
            @RequestParam(required = false) UUID ignorar) {
        return duplicados.executar(nome, telefone, ignorar);
    }

    public record MesclarRequest(@NotNull UUID absorvidaId) {
    }

    /**
     * RT-CLI-003 — absorve {@code absorvidaId} na ficha do caminho.
     *
     * <p>A ficha do caminho é a que <strong>fica</strong>. É a ordem natural do gesto na tela:
     * abre-se a ficha correta e traz-se a duplicada para dentro dela.
     */
    @PostMapping("/{id}/mesclar")
    public ClienteResumo mesclar(@PathVariable UUID id, @Valid @RequestBody MesclarRequest corpo) {
        return consultar.executar(mesclar.executar(id, corpo.absorvidaId()));
    }

    @GetMapping("/{id}")
    public ClienteResumo porId(@PathVariable UUID id) {
        return consultar.executar(id);
    }

    /**
     * RT-CLI-005 — apaga o dado pessoal a pedido do titular (LGPD art. 18, VI).
     *
     * <p>{@code DELETE} é o verbo certo do ponto de vista de quem chama: para o mundo lá fora, a
     * pessoa deixa de existir no sistema. Que a linha permaneça, sem dado pessoal, para sustentar
     * o histórico financeiro que a legislação fiscal exige, é assunto do servidor.
     *
     * <p>204 e não 200: não há o que devolver, e devolver a ficha vazia seria estranho logo depois
     * de apagá-la.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void anonimizar(@PathVariable UUID id) {
        anonimizar.executar(id);
    }

    /** RT-CLI-004 — o que vale hoje, inclusive as finalidades que ninguém perguntou ainda. */
    @GetMapping("/{id}/consentimentos")
    public List<EstadoDoConsentimento> consentimentos(@PathVariable UUID id) {
        return consultarConsentimentos.executar(id);
    }

    public record ConsentimentoRequest(
            @NotNull Boolean concedido,
            @NotNull CanalDeConsentimento canal) {
    }

    /**
     * RT-CLI-004 — registra a decisão da cliente sobre uma finalidade.
     *
     * <p>Conceder e revogar são <strong>a mesma rota</strong>, mudando só {@code concedido}. A
     * LGPD exige que revogar seja tão fácil quanto consentir (art. 8 §5), e a forma confiável de
     * cumprir isso não é lembrar de facilitar o "não" — é não ter dois caminhos, porque o segundo
     * caminho seria sempre o mais estreito.
     *
     * <p>{@code PUT} e não {@code POST}: do ponto de vista de quem chama, a operação define o
     * estado atual daquela finalidade. Que por baixo isso vire uma linha nova no histórico é
     * assunto do servidor.
     */
    @PutMapping("/{id}/consentimentos/{finalidade}")
    public List<EstadoDoConsentimento> registrarConsentimento(
            @PathVariable UUID id,
            @PathVariable Finalidade finalidade,
            @Valid @RequestBody ConsentimentoRequest corpo) {
        registrarConsentimento.executar(id, finalidade, corpo.concedido(), corpo.canal());
        return consultarConsentimentos.executar(id);
    }
}
