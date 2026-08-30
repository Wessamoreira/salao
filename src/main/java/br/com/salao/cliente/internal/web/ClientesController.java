package br.com.salao.cliente.internal.web;

import br.com.salao.cliente.api.ClienteResumo;
import br.com.salao.cliente.internal.application.CadastrarClienteUseCase;
import br.com.salao.cliente.internal.application.BuscarClientesUseCase;
import br.com.salao.cliente.internal.application.ConsultarClienteUseCase;
import br.com.salao.cliente.internal.application.DadosDoCliente;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    public ClientesController(CadastrarClienteUseCase cadastrar,
                              ConsultarClienteUseCase consultar,
                              BuscarClientesUseCase buscar) {
        this.cadastrar = cadastrar;
        this.consultar = consultar;
        this.buscar = buscar;
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

    @GetMapping("/{id}")
    public ClienteResumo porId(@PathVariable UUID id) {
        return consultar.executar(id);
    }
}
