package br.com.salao.agenda.internal.web;

import br.com.salao.agenda.api.AgendaEmpurrada;
import br.com.salao.agenda.api.AgendamentoCancelado;
import br.com.salao.agenda.api.AgendamentoConfirmado;
import br.com.salao.agenda.api.AgendamentoCriado;
import br.com.salao.agenda.api.AgendamentoReagendado;
import br.com.salao.agenda.api.NoShowRegistrado;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RT-AGD-012 — quem está conectado ao painel, e o que empurrar para cada um.
 *
 * <h2>Em processo, e NÃO pelo outbox</h2>
 *
 * <p>É a decisão que define a rotina. Os mesmos eventos de domínio existem em
 * {@code event_publication} e podem ser reenviados quando um consumidor falha — que é exatamente o
 * que <strong>não</strong> se quer aqui: um painel que, ao reconectar, replayasse os cancelamentos
 * de ontem mostraria a cliente errada na parede da recepção.
 *
 * <p>O painel quer o <em>agora</em>. Perder um evento porque o navegador estava desconectado é o
 * comportamento correto — quando ele volta, pede o retrato inteiro de novo. Daí
 * {@code @TransactionalEventListener} em processo, e não {@code @ApplicationModuleListener}.
 *
 * <p>{@code AFTER_COMMIT} porque empurrar antes do commit anunciaria um agendamento que o rollback
 * pode desfazer — e a tela mostraria uma cliente que não está marcada.
 *
 * <h2>O tenant separa as conexões</h2>
 *
 * <p>Cada conexão é registrada sob o estabelecimento de quem abriu, e o evento carrega o seu
 * (RN-INF-009). Sem esse casamento, o painel de um salão receberia o movimento do outro — o
 * vazamento mais direto que este sistema poderia ter, porque apareceria numa tela pendurada na
 * parede.
 */
@Component
public class PainelDeEventos {

    private static final Logger log = LoggerFactory.getLogger(PainelDeEventos.class);

    private final Map<UUID, List<SseEmitter>> conectados = new ConcurrentHashMap<>();

    /**
     * Registra uma conexão.
     *
     * <p>A limpeza é obrigatória nos três caminhos — fim normal, tempo esgotado e erro. Um emissor
     * que fica na lista depois de o navegador ir embora vira uma escrita que falha a cada evento,
     * e o vazamento só aparece quando o salão está cheio.
     */
    public SseEmitter conectar(UUID estabelecimentoId, SseEmitter emissor) {
        conectados.computeIfAbsent(estabelecimentoId, id -> new CopyOnWriteArrayList<>())
                .add(emissor);

        emissor.onCompletion(() -> desconectar(estabelecimentoId, emissor));
        emissor.onTimeout(() -> desconectar(estabelecimentoId, emissor));
        emissor.onError(erro -> desconectar(estabelecimentoId, emissor));
        return emissor;
    }

    /**
     * Tira a conexão da lista, e some com o salão quando foi a última.
     *
     * <p>Visível para o teste: o disparo de {@code onCompletion} e {@code onTimeout} é contrato do
     * Spring com o container e não acontece num emissor solto, mas a limpeza em si é lógica deste
     * código — e é ela que impede o mapa de crescer para sempre.
     */
    void desconectar(UUID estabelecimentoId, SseEmitter emissor) {
        List<SseEmitter> lista = conectados.get(estabelecimentoId);
        if (lista != null) {
            lista.remove(emissor);
            if (lista.isEmpty()) {
                conectados.remove(estabelecimentoId);
            }
        }
    }

    /** Quantos salões têm painel aberto — o número que denuncia vazamento de conexão. */
    int salõesConectados() {
        return conectados.size();
    }

    public int conexoesDe(UUID estabelecimentoId) {
        return conectados.getOrDefault(estabelecimentoId, List.of()).size();
    }

    // ------------------------------------------------------------------ os avisos

    /**
     * Um aviso só, com o tipo dentro.
     *
     * <p>Seis nomes de evento SSE fariam o painel ter seis tratadores que fazem a mesma coisa —
     * pedir o retrato de novo. O que ele precisa saber é "mudou alguma coisa", e o tipo serve para
     * o rótulo de atividade, não para lógica.
     */
    public record Aviso(String tipo, UUID agendamentoId) {
    }

    @TransactionalEventListener
    void aoCriar(AgendamentoCriado evento) {
        empurrar(evento.estabelecimentoId(), new Aviso("CRIADO", evento.agendamentoId()));
    }

    @TransactionalEventListener
    void aoConfirmar(AgendamentoConfirmado evento) {
        empurrar(evento.estabelecimentoId(), new Aviso("CONFIRMADO", evento.agendamentoId()));
    }

    @TransactionalEventListener
    void aoCancelar(AgendamentoCancelado evento) {
        empurrar(evento.estabelecimentoId(), new Aviso("CANCELADO", evento.agendamentoId()));
    }

    @TransactionalEventListener
    void aoReagendar(AgendamentoReagendado evento) {
        empurrar(evento.estabelecimentoId(), new Aviso("REAGENDADO", evento.agendamentoId()));
    }

    @TransactionalEventListener
    void aoRegistrarFalta(NoShowRegistrado evento) {
        empurrar(evento.estabelecimentoId(), new Aviso("NO_SHOW", evento.agendamentoId()));
    }

    @TransactionalEventListener
    void aoEmpurrar(AgendaEmpurrada evento) {
        empurrar(evento.estabelecimentoId(), new Aviso("EMPURRAO", null));
    }

    /** Visível para o teste, que precisa empurrar sem montar uma transação inteira. */
    void empurrar(UUID estabelecimentoId, Aviso aviso) {
        List<SseEmitter> lista = conectados.get(estabelecimentoId);
        if (lista == null || lista.isEmpty()) {
            return;
        }
        for (SseEmitter emissor : lista) {
            try {
                emissor.send(SseEmitter.event().name("agenda").data(aviso));
            } catch (IOException | IllegalStateException e) {
                // Navegador fechado no meio da escrita. Não é erro do salão: tira da lista e
                // segue, senão um painel desligado derrubaria o aviso dos outros.
                desconectar(estabelecimentoId, emissor);
                log.debug("Painel desconectado durante o envio: {}", e.getMessage());
            }
        }
    }
}
