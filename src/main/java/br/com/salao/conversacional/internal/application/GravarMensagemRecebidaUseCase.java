package br.com.salao.conversacional.internal.application;

import br.com.salao.conversacional.api.MensagemDeClienteRecebida;
import br.com.salao.conversacional.internal.domain.CargaDoWhatsApp;
import br.com.salao.conversacional.internal.infra.MensagensRecebidasJdbc;
import br.com.salao.shared.tempo.Relogio;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CNV-001 — grava UMA mensagem e anuncia que ela é inédita.
 *
 * <h2>Por que é uma classe separada do laço</h2>
 *
 * <p>Não é organização: é a única forma de a transação existir. O laço do lote roda
 * <strong>sem tenant</strong> — é ele que descobre o tenant de cada mensagem — e abre o escopo
 * antes de chamar este caso de uso. Se os dois morassem na mesma classe, a chamada seria interna,
 * o proxy do Spring não a interceptaria e {@code @Transactional} não valeria nada: a inserção
 * aconteceria em autocommit e o evento cairia fora da transação.
 *
 * <p>É a mesma lição de {@code FechamentosJdbc.travarEstabelecimento} (RT-FIN-006): anotação de
 * transação só vale onde o Spring consegue interceptar.
 *
 * <h2>O evento vai DENTRO da transação, de propósito</h2>
 *
 * <p>{@code @ApplicationModuleListener} grava a publicação em {@code event_publication} na mesma
 * transação de quem publicou (RT-INF-006). Publicar depois do commit — do lado de fora — traria
 * de volta exatamente a janela que o outbox existe para fechar: mensagem gravada e ninguém
 * avisado, sem nada para reenviar.
 */
public class GravarMensagemRecebidaUseCase {

    private final MensagensRecebidasJdbc mensagens;
    private final ApplicationEventPublisher eventos;
    private final Relogio relogio;

    public GravarMensagemRecebidaUseCase(MensagensRecebidasJdbc mensagens,
                                         ApplicationEventPublisher eventos,
                                         Relogio relogio) {
        this.mensagens = mensagens;
        this.eventos = eventos;
        this.relogio = relogio;
    }

    /**
     * @return o id gravado, ou vazio quando a mensagem já havia chegado antes
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Optional<UUID> executar(UUID estabelecimentoId, CargaDoWhatsApp.Entrante entrante) {
        Optional<UUID> gravada = mensagens.inserir(
                estabelecimentoId,
                entrante.provedorId(),
                entrante.deTelefone(),
                entrante.tipo(),
                entrante.texto(),
                entrante.midiaId(),
                entrante.midiaMime(),
                entrante.enviadaEm(),
                relogio.agora());

        // Nada de evento no reenvio. É este `ifPresent` que impede "marca quarta 10h" de virar
        // dois agendamentos quando a Meta manda o lote de novo.
        gravada.ifPresent(id -> eventos.publishEvent(
                new MensagemDeClienteRecebida(estabelecimentoId, id, entrante.tipo())));

        return gravada;
    }
}
