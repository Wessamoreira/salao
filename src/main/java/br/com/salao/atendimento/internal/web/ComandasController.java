package br.com.salao.atendimento.internal.web;

import br.com.salao.atendimento.api.AberturaDeComanda;
import br.com.salao.atendimento.internal.application.AbrirComandaUseCase;
import br.com.salao.atendimento.internal.application.ListarComandasAbertasUseCase;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-ATD-001 — a porta da comanda.
 *
 * <h2>Reabrir a mesma comanda responde 200, não 201</h2>
 *
 * <p>201 significa "criei". Quando a cliente já tinha comanda aberta, nada foi criado — e é uma
 * diferença que a tela precisa ver, porque anunciar "comanda 42 aberta" para uma comanda que está
 * aberta desde as nove da manhã, com itens dentro, é mentir para quem está no balcão.
 */
@RestController
@RequestMapping("/api/v1/comandas")
public class ComandasController {

    private final AbrirComandaUseCase abrir;
    private final ListarComandasAbertasUseCase abertas;

    public ComandasController(AbrirComandaUseCase abrir, ListarComandasAbertasUseCase abertas) {
        this.abrir = abrir;
        this.abertas = abertas;
    }

    /**
     * Um campo ou o outro, nunca os dois.
     *
     * <p>Duas rotas seriam a alternativa, e o balcão faz uma coisa só — abrir a conta de quem
     * chegou. O que muda é o que a recepção tem na mão: o horário marcado, ou só a pessoa.
     */
    public record AbrirRequest(UUID agendamentoId, UUID clienteId) {

        @jakarta.validation.constraints.AssertTrue(
                message = "informe agendamentoId ou clienteId, não os dois")
        public boolean isExatamenteUm() {
            return (agendamentoId == null) != (clienteId == null);
        }
    }

    @PostMapping
    public ResponseEntity<AberturaDeComanda> abrir(@Valid @RequestBody AbrirRequest req) {
        AberturaDeComanda abertura = req.agendamentoId() != null
                ? abrir.doAgendamento(req.agendamentoId())
                : abrir.avulsa(req.clienteId());

        URI local = URI.create("/api/v1/comandas/" + abertura.comanda().id());
        return abertura.jaEstavaAberta()
                ? ResponseEntity.ok().location(local).body(abertura)
                : ResponseEntity.created(local).body(abertura);
    }

    /** A barra do balcão (R-UX-07). Sempre as abertas — não é listagem geral de comandas. */
    @GetMapping("/abertas")
    public List<ListarComandasAbertasUseCase.ComandaEmAberto> abertas() {
        return abertas.executar();
    }
}
