package br.com.salao.atendimento.internal.application;

import br.com.salao.atendimento.api.ComandaDetalhada;
import br.com.salao.atendimento.api.ComandaReaberta;
import br.com.salao.atendimento.api.ComandaResumo;
import br.com.salao.atendimento.api.ErrosDoAtendimento;
import br.com.salao.atendimento.api.ItemDaComanda;
import br.com.salao.atendimento.api.StatusDaComanda;
import br.com.salao.atendimento.api.TipoDeItem;
import br.com.salao.atendimento.internal.infra.ComandasJdbc;
import br.com.salao.estoque.api.EstoqueApi;
import br.com.salao.financeiro.api.RazaoApi;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-ATD-007 — reabrir a comanda para corrigir.
 *
 * <h2>As unidades voltam ao lote de onde saíram</h2>
 *
 * <p>Não é "somar de volta a quantidade". Elas saíram de lotes específicos, com custos e validades
 * específicos — devolvê-las a um lote genérico faria o custo médio e o FEFO mentirem a partir dali.
 *
 * <p>E a devolução é <strong>movimento novo, de sinal contrário</strong>, nunca apagando o
 * movimento da baixa. É o que RN-EST-006 promete, e é aqui que a promessa é cobrada pela primeira
 * vez: o razão de estoque não é reescrito, ele ganha a linha que corrige.
 *
 * <h2>Os pagamentos ficam</h2>
 *
 * <p>O dinheiro foi recebido de verdade. Apagá-los faria a comanda reaberta parecer não paga, e a
 * recepção cobraria de novo.
 *
 * <p>O preço disso é um caso novo: tirar item de uma comanda reaberta pode deixar o pago
 * <em>acima</em> do total. Fechar assim registraria faturamento maior do que o atendimento, e o
 * troco devido ficaria sem rastro — por isso RN-ATD-019 passou a recusar também esse lado.
 *
 * <h2>Só quem pode reabrir</h2>
 *
 * <p>{@code comanda:reopen} não está no perfil de recepção. Desfazer um fechamento mexe em
 * faturamento, comissão e estoque de uma vez, e não é operação de balcão.
 */
public class ReabrirComandaUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReabrirComandaUseCase.class);

    private final ComandasJdbc comandas;
    private final EstoqueApi estoque;
    private final RazaoApi livroRazao;
    private final AuditoriaApi auditoria;
    private final ApplicationEventPublisher eventos;
    private final Relogio relogio;

    public ReabrirComandaUseCase(ComandasJdbc comandas, EstoqueApi estoque, RazaoApi livroRazao,
                                 AuditoriaApi auditoria, ApplicationEventPublisher eventos,
                                 Relogio relogio) {
        this.comandas = comandas;
        this.estoque = estoque;
        this.livroRazao = livroRazao;
        this.auditoria = auditoria;
        this.eventos = eventos;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.COMANDA_REABRIR + "')")
    @Transactional
    public ComandaDetalhada executar(UUID comandaId, String motivo) {
        String razao = MotivoDoAto.exigir(motivo);
        ComandaResumo comanda = comandas.porId(comandaId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoAtendimento.COMANDA_NAO_ENCONTRADA,
                        "comanda " + comandaId + " não encontrada"));
        if (comanda.status() != StatusDaComanda.FECHADA) {
            throw new ErroDeDominio(ErrosDoAtendimento.COMANDA_NAO_ESTA_FECHADA,
                    "a comanda " + comanda.numero() + " está " + comanda.status());
        }
        if (comandas.reabrir(comandaId) == 0) {
            throw new ErroDeDominio(ErrosDoAtendimento.COMANDA_NAO_ESTA_FECHADA,
                    "a comanda " + comanda.numero() + " acabou de mudar de estado");
        }

        BigDecimal devolvidas = devolverOEstoque(comandaId);
        comandas.limparCustoDosItens(comandaId);
        // O que foi apurado no fechamento deixa de valer — e é anulado por linha nova, nunca
        // apagado: a role da aplicação não tem `delete` no razão (RT-FIN-003).
        int anulados = livroRazao.estornarOrigem("COMANDA", comandaId, "reabertura: " + razao);

        Instant agora = relogio.agora();
        auditoria.registrar(RegistroDeAuditoria.alteracao("COMANDA_REABERTA", "comanda", comandaId,
                Map.of("status", "FECHADA"),
                Map.of("status", "ABERTA", "motivo", razao,
                        "unidadesDevolvidas", devolvidas.toPlainString(),
                        "lancamentosAnulados", anulados)));
        eventos.publishEvent(new ComandaReaberta(TenantContext.obrigatorio(), comandaId,
                comanda.numero(), agora));

        log.info("Comanda {} reaberta ({}); {} unidade(s) devolvidas", comanda.numero(), razao,
                devolvidas);
        return new ComandaDetalhada(comandas.porId(comandaId).orElseThrow(),
                comandas.itensDe(comandaId));
    }

    /** Devolve, por item, o que aquele item tirou. A origem do movimento é o próprio item. */
    private BigDecimal devolverOEstoque(UUID comandaId) {
        BigDecimal total = BigDecimal.ZERO;
        for (ItemDaComanda item : comandas.itensDe(comandaId)) {
            if (item.tipo() == TipoDeItem.PRODUTO) {
                total = total.add(estoque.devolver("ITEM_COMANDA", item.id()));
            }
        }
        return total;
    }
}
