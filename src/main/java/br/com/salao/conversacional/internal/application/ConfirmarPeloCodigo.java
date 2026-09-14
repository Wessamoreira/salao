package br.com.salao.conversacional.internal.application;

import br.com.salao.conversacional.internal.application.EfetivarIntencao.Decisao;
import br.com.salao.conversacional.internal.infra.IntencoesJdbc;
import br.com.salao.iam.api.ErrosDoIam;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;

/**
 * RT-CNV-005 — a mensagem é "SIM 4821": decidir sem o modelo, e responder com texto do servidor.
 *
 * <p>Chamado pelo processador ANTES de qualquer chamada ao modelo, quando
 * {@code CodigoDeConfirmacao.codigoEm} reconhece a mensagem. A confirmação nunca é interpretada — é
 * reconhecida —, e a resposta à pessoa também não passa pelo modelo: não há custo, e não há o que um
 * modelo comprometido possa dizer em nome do sistema.
 *
 * <p>Procura a intenção MAIS RECENTE deste telefone com este código, de qualquer situação, para
 * responder a verdade: "já estava confirmado" e "foi trocado por um mais novo" são respostas
 * diferentes de "não encontrei".
 */
public class ConfirmarPeloCodigo {

    private static final Logger log = LoggerFactory.getLogger(ConfirmarPeloCodigo.class);

    private final IntencoesJdbc intencoes;
    private final EfetivarIntencao efetivar;
    private final Relogio relogio;

    public ConfirmarPeloCodigo(IntencoesJdbc intencoes, EfetivarIntencao efetivar,
                               Relogio relogio) {
        this.intencoes = intencoes;
        this.efetivar = efetivar;
        this.relogio = relogio;
    }

    public enum Desfecho {
        CONFIRMADA,
        /** RT-CNV-006 — confirmada por quem pediu; o pedido foi a quem vai atender. */
        AGUARDANDO_ACEITE,
        RECUSADA,
        VENCIDA,
        JA_DECIDIDA,
        SEM_PEDIDO
    }

    public record Resultado(Desfecho desfecho, String resposta) {
    }

    public Resultado executar(String telefone, UUID usuarioId, UUID execucaoId, String codigo) {
        // De outra pessoa é "não encontrei": o número mudou de dono, e a intenção não é dela.
        var alvo = intencoes.maisRecente(telefone, codigo)
                .filter(intencao -> intencao.usuarioEfetivoId().equals(usuarioId));
        if (alvo.isEmpty()) {
            return semPedido(codigo);
        }
        UUID id = alvo.get().id();
        try {
            return switch (efetivar.executar(id, telefone, usuarioId, execucaoId)) {
                case Decisao.Confirmada confirmada ->
                        new Resultado(Desfecho.CONFIRMADA, confirmada.resposta());
                case Decisao.AguardandoAceite aguardando ->
                        new Resultado(Desfecho.AGUARDANDO_ACEITE, aguardando.resposta());
                case Decisao.Vencida vencida -> vencida(codigo);
                case Decisao.JaDecidida decidida -> jaDecidida(codigo, decidida.status());
                case Decisao.NaoEncontrada nao -> semPedido(codigo);
            };
        } catch (AccessDeniedException negado) {
            return recusar(id, execucaoId, "SEM_PERMISSAO",
                    "Você não tem permissão para isso por aqui. Nada foi gravado.");
        } catch (ErroDeDominio erro) {
            if (erro.codigo() == ErrosDoIam.CONTATO_NAO_IDENTIFICADO) {
                return recusar(id, execucaoId, "SEM_PERMISSAO",
                        "Você não tem permissão para isso por aqui. Nada foi gravado.");
            }
            return recusar(id, execucaoId, erro.codigo().codigo(),
                    "Não deu para confirmar: " + erro.getMessage() + " Nada foi gravado.");
        }
    }

    private Resultado recusar(UUID intencaoId, UUID execucaoId, String motivo, String resposta) {
        intencoes.recusar(intencaoId, execucaoId, motivo, resposta, relogio.agora());
        log.info("Intenção {} recusada na confirmação: {}", intencaoId, motivo);
        return new Resultado(Desfecho.RECUSADA, resposta);
    }

    private static Resultado semPedido(String codigo) {
        return new Resultado(Desfecho.SEM_PEDIDO,
                "Não encontrei pedido com o código %s. Nada foi gravado.".formatted(codigo));
    }

    private static Resultado vencida(String codigo) {
        return new Resultado(Desfecho.VENCIDA,
                "O pedido %s venceu, e nada foi gravado. Se ainda quiser, peça de novo."
                        .formatted(codigo));
    }

    private static Resultado jaDecidida(String codigo, IntencoesJdbc.Status status) {
        return switch (status) {
            case CONFIRMADA -> new Resultado(Desfecho.JA_DECIDIDA,
                    "O pedido %s já estava confirmado.".formatted(codigo));
            case AGUARDANDO_ACEITE -> new Resultado(Desfecho.JA_DECIDIDA,
                    "O pedido %s já foi confirmado e espera o aceite de quem vai atender."
                            .formatted(codigo));
            case SUBSTITUIDA -> new Resultado(Desfecho.JA_DECIDIDA,
                    "O pedido %s foi trocado por um mais novo, e nada foi gravado com ele. "
                            .formatted(codigo) + "Confira o último resumo.");
            case RECUSADA -> new Resultado(Desfecho.JA_DECIDIDA,
                    "O pedido %s não pôde ser confirmado. Se ainda quiser, peça de novo."
                            .formatted(codigo));
            case EXPIRADA -> vencida(codigo);
            case PENDENTE -> throw new IllegalStateException("pendente não é decisão");
        };
    }
}
