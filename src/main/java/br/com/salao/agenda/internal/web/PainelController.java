package br.com.salao.agenda.internal.web;

import br.com.salao.agenda.internal.application.PainelDoBalcaoUseCase;
import br.com.salao.shared.tenant.TenantContext;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RT-AGD-012 — o painel do balcão.
 *
 * <p>Duas rotas e a divisão entre elas é a rotina inteira: {@code /painel} devolve o retrato, e
 * {@code /painel/eventos} avisa que ele mudou. O aviso <strong>não carrega o dado</strong> — o
 * painel volta a pedir o retrato.
 *
 * <p>Parece um passo a mais e evita a classe de bug mais chata deste tipo de tela: um stream que
 * manda deltas precisa que o cliente aplique todos, na ordem, sem perder nenhum — e quando ele
 * perde um (reconexão, aba em segundo plano, rede oscilando), a tela fica errada em silêncio, para
 * sempre, num painel pendurado na parede que ninguém está olhando de perto.
 *
 * <p>Pedir o retrato de novo é uma consulta barata e sempre correta.
 */
@RestController
@RequestMapping("/api/v1/painel")
public class PainelController {

    /**
     * Tempo de vida da conexão.
     *
     * <p>Trinta minutos e não infinito: proxy e balanceador derrubam conexão ociosa sem avisar, e
     * um cliente que acha que está conectado para de atualizar sem dar sinal. Expirando de
     * propósito, a reconexão do navegador é o caminho normal e não a exceção.
     */
    private static final Duration VIDA_DA_CONEXAO = Duration.ofMinutes(30);

    private final PainelDoBalcaoUseCase painel;
    private final PainelDeEventos eventos;

    public PainelController(PainelDoBalcaoUseCase painel, PainelDeEventos eventos) {
        this.painel = painel;
        this.eventos = eventos;
    }

    @GetMapping
    public PainelDoBalcaoUseCase.Painel retrato() {
        return painel.executar();
    }

    /**
     * O aviso de que algo mudou.
     *
     * <p>O tenant é fixado <strong>aqui</strong>, na abertura, e não a cada evento: quem abriu a
     * conexão é quem manda. Sem isso, o painel de um salão receberia o movimento do outro — o
     * vazamento mais direto possível, porque apareceria numa tela pendurada na parede.
     */
    @GetMapping(path = "/eventos", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter eventos() {
        // Lido antes de entregar o emissor: depois que o Spring devolve a resposta, a thread da
        // requisição some e com ela o escopo do tenant.
        var tenant = TenantContext.obrigatorio();
        return eventos.conectar(tenant, new SseEmitter(VIDA_DA_CONEXAO.toMillis()));
    }
}
