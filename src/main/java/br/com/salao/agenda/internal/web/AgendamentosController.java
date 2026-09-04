package br.com.salao.agenda.internal.web;

import br.com.salao.agenda.api.AgendamentoResumo;
import br.com.salao.agenda.api.BlocoDoAgendamento;
import br.com.salao.agenda.api.OrigemDoAgendamento;
import br.com.salao.agenda.internal.application.ConfirmarAgendamentoUseCase;
import br.com.salao.agenda.internal.application.ConsultarAgendamentoUseCase;
import br.com.salao.agenda.internal.application.CriarAgendamentoUseCase;
import br.com.salao.agenda.internal.application.NovoAgendamento;
import br.com.salao.agenda.internal.application.PedidoDeReagendamento;
import br.com.salao.agenda.internal.application.ReagendarUseCase;
import br.com.salao.cliente.api.ClienteApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-AGD-002 — marcar.
 *
 * <p>Transporte apenas. Nenhum objeto de domínio é construído aqui: {@code Janela} e
 * {@code SequenciaDeBlocos} recusam entrada inválida com {@code IllegalArgumentException}, que
 * daqui viraria 500.
 */
@RestController
@RequestMapping("/api/v1/agendamentos")
public class AgendamentosController {

    private final CriarAgendamentoUseCase criar;
    private final ConsultarAgendamentoUseCase consultar;
    private final ReagendarUseCase reagendar;
    private final ConfirmarAgendamentoUseCase confirmar;
    private final ClienteApi clientes;

    public AgendamentosController(CriarAgendamentoUseCase criar,
                                  ConsultarAgendamentoUseCase consultar,
                                  ReagendarUseCase reagendar,
                                  ConfirmarAgendamentoUseCase confirmar, ClienteApi clientes) {
        this.criar = criar;
        this.consultar = consultar;
        this.reagendar = reagendar;
        this.confirmar = confirmar;
        this.clientes = clientes;
    }

    public record CriarAgendamentoRequest(
            @NotNull UUID clienteId,
            @NotNull UUID profissionalId,
            @NotNull UUID servicoId,
            @NotNull Instant inicio,
            @NotNull OrigemDoAgendamento origem,
            @Size(max = 1000) String observacao,
            Integer duracaoMinutos,
            boolean forcarForaJornada,
            boolean forcarPassado,
            @Size(max = 500) String justificativa) {
    }

    /**
     * A resposta acrescenta o nome da cliente ao resumo.
     *
     * <p>O nome não está em {@code AgendamentoResumo} de propósito — carregá-lo em toda leitura
     * faria dado pessoal trafegar até para quem só quer saber se o horário está ocupado. Aqui ele
     * é buscado uma vez, para quem acabou de digitá-lo.
     */
    public record AgendamentoResponse(
            UUID id,
            String status,
            Instant inicio,
            Instant fim,
            UUID clienteId,
            String clienteNome,
            UUID profissionalId,
            UUID servicoId,
            String origem,
            String observacao,
            List<BlocoDoAgendamento> blocos,
            int minutosDoProfissional,
            int versao) {
    }

    private AgendamentoResponse comNome(AgendamentoResumo resumo) {
        String nome = clientes.porId(resumo.clienteId())
                .map(br.com.salao.cliente.api.ClienteResumo::nome)
                .orElse(null);
        return new AgendamentoResponse(resumo.id(), resumo.status().name(), resumo.inicio(),
                resumo.fim(), resumo.clienteId(), nome, resumo.profissionalId(),
                resumo.servicoId(), resumo.origem().name(), resumo.observacao(), resumo.blocos(),
                resumo.minutosDoProfissional(), resumo.versao());
    }

    @PostMapping
    public ResponseEntity<AgendamentoResponse> criar(
            @Valid @RequestBody CriarAgendamentoRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String chaveIdempotencia) {
        AgendamentoResumo criado = criar.executar(new NovoAgendamento(req.clienteId(),
                req.profissionalId(), req.servicoId(), req.inicio(), req.origem(),
                req.observacao(), req.duracaoMinutos(), req.forcarForaJornada(),
                req.forcarPassado(), req.justificativa(), chaveIdempotencia));
        return ResponseEntity.created(URI.create("/api/v1/agendamentos/" + criado.id()))
                .body(comNome(criado));
    }

    @GetMapping("/{id}")
    public AgendamentoResponse porId(@PathVariable UUID id) {
        return comNome(consultar.porId(id));
    }

    // ------------------------------------------------------------------ RT-AGD-003

    /**
     * RT-AGD-003 — arrastar na grade.
     *
     * <p>{@code profissionalId} nulo mantém quem já atendia: arrastar para cima e para baixo na
     * mesma coluna é o movimento mais comum, e obrigar a repetir o profissional faria o front
     * mandar o dado que ele acabou de receber.
     */
    public record ReagendarRequest(
            @NotNull Instant inicio,
            UUID profissionalId,
            Integer duracaoMinutos,
            @NotNull Integer versao,
            boolean forcarForaJornada,
            boolean forcarPassado,
            @Size(max = 500) String justificativa) {
    }

    @PutMapping("/{id}/horario")
    public AgendamentoResponse reagendar(@PathVariable UUID id,
                                         @Valid @RequestBody ReagendarRequest req) {
        return comNome(reagendar.executar(new PedidoDeReagendamento(id, req.inicio(),
                req.profissionalId(), req.duracaoMinutos(), req.versao(),
                req.forcarForaJornada(), req.forcarPassado(), req.justificativa())));
    }

    // ------------------------------------------------------------------ RT-AGD-004

    /**
     * {@code pelaCliente} distingue quem confirmou: a própria cliente pelo link do lembrete, ou o
     * salão por ela. Vai para o evento, porque mandar "obrigado por confirmar" para quem não
     * confirmou nada é pior do que não mandar nada.
     */
    public record ConfirmacaoRequest(@NotNull Integer versao, boolean pelaCliente) {
    }

    @PatchMapping("/{id}/confirmacao")
    public AgendamentoResponse confirmar(@PathVariable UUID id,
                                         @Valid @RequestBody ConfirmacaoRequest req) {
        return comNome(confirmar.executar(id, req.versao(), req.pelaCliente()));
    }
}
