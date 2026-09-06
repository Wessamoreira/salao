package br.com.salao.atendimento.internal.application;

import br.com.salao.atendimento.api.ErrosDoAtendimento;
import br.com.salao.atendimento.api.ComandaDetalhada;
import br.com.salao.atendimento.api.ComandaResumo;
import br.com.salao.atendimento.api.ItemDaComanda;
import br.com.salao.atendimento.internal.infra.ComandasJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.LimitesDoUsuarioApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-ATD-004 — desconto, com teto por quem o dá.
 *
 * <h2>O desconto é do item, e isso não é detalhe de modelagem</h2>
 *
 * <p>Um desconto no total da comanda teria de ser rateado entre os itens para chegar às comissões —
 * e o rateio decidiria, sozinho, <strong>de quem</strong> sai o desconto. Numa comanda com corte da
 * Ana e coloração da Bia, "vinte reais de desconto" tiraria dinheiro das duas sem ninguém escolher.
 *
 * <p>Aplicar no item obriga a escolha a ser feita por quem dá o desconto, que é quem sabe por que
 * está dando. É atrito de propósito, no único lugar onde ele evita uma conta errada no bolso de
 * outra pessoa.
 *
 * <h2>O teto é do usuário, e vem do banco</h2>
 *
 * <p>Quem pergunta é este caso de uso; quem responde é o {@code iam}, por
 * {@link LimitesDoUsuarioApi} — e de propósito ele não devolve o perfil, só o teto. Repetir aqui a
 * tabela de perfis seria uma segunda cópia da regra de alçada, e a segunda é a que continua
 * autorizando depois que a primeira mudar.
 *
 * <p>A permissão {@code comanda:discount} responde "pode dar desconto?"; o teto responde "de
 * quanto?". São perguntas diferentes: a recepção tem a permissão e para nos 10%.
 */
public class AplicarDescontoUseCase {

    private static final Logger log = LoggerFactory.getLogger(AplicarDescontoUseCase.class);

    private static final BigDecimal CEM = new BigDecimal("100");

    private final ComandasJdbc comandas;
    private final LimitesDoUsuarioApi limites;
    private final EstabelecimentoApi estabelecimentos;
    private final AuditoriaApi auditoria;

    public AplicarDescontoUseCase(ComandasJdbc comandas, LimitesDoUsuarioApi limites,
                                  EstabelecimentoApi estabelecimentos, AuditoriaApi auditoria) {
        this.comandas = comandas;
        this.limites = limites;
        this.estabelecimentos = estabelecimentos;
        this.auditoria = auditoria;
    }

    /**
     * @param valor      desconto em dinheiro, ou {@code null}
     * @param percentual desconto em pontos percentuais ({@code 10} é 10%), ou {@code null}
     *
     * <p>Um ou outro, nunca os dois: são duas formas de dizer a mesma coisa, e aceitar as duas
     * juntas deixaria a resposta para a ordem do {@code if}. Zero nos dois remove o desconto —
     * remover é a mesma operação, e por isso passa pelo mesmo teto e pela mesma trilha.
     */
    @PreAuthorize("hasAuthority('" + Permissao.COMANDA_DESCONTO + "')")
    @Transactional
    public ComandaDetalhada executar(UUID comandaId, UUID itemId, Money valor,
                                     BigDecimal percentual) {
        ComandaResumo comanda = ComandaEmEdicao.exigirAberta(comandas, comandaId);
        ItemDaComanda item = ComandaEmEdicao.exigirItem(comandas, comandaId, itemId);

        Money bruto = item.precoUnitario().multiplicar(item.quantidade());
        Money desconto = quantoDescontar(valor, percentual, bruto);
        conferirTeto(desconto, bruto);

        boolean afetaComissao = estabelecimentos
                .configuracao(TenantContext.obrigatorio())
                .orElseThrow()
                .descontoAfetaComissao();

        comandas.aplicarDescontoNoItem(itemId, desconto);
        comandas.recalcularComissoesDoItem(itemId, afetaComissao);
        comandas.recalcularTotais(comandaId);

        auditoria.registrar(RegistroDeAuditoria.alteracao("DESCONTO_APLICADO", "item_comanda",
                itemId, Map.of("desconto", item.desconto().toString()),
                Map.of("desconto", desconto.toString(), "comandaId", comandaId.toString())));
        log.info("Desconto de {} aplicado no item {} da comanda {}", desconto, itemId,
                comanda.numero());
        return new ComandaDetalhada(comandas.porId(comandaId).orElseThrow(),
                comandas.itensDe(comandaId));
    }

    /** Converte a forma pedida em dinheiro. O que é gravado é sempre valor. */
    private Money quantoDescontar(Money valor, BigDecimal percentual, Money bruto) {
        if ((valor == null) == (percentual == null)) {
            throw new ErroDeDominio(ErrosDoAtendimento.DESCONTO_INVALIDO,
                    "informe o desconto em valor ou em percentual, não os dois");
        }
        Money desconto = valor != null
                ? valor
                : bruto.percentual(percentual.divide(CEM, 6, RoundingMode.HALF_UP));
        if (desconto.ehNegativo()) {
            throw new ErroDeDominio(ErrosDoAtendimento.DESCONTO_INVALIDO, "desconto negativo é acréscimo");
        }
        if (desconto.maiorQue(bruto)) {
            // Passar do valor do item produziria total negativo — e um item que devolve dinheiro
            // é estorno, não desconto. O `check (valor_total >= 0)` também barraria, com uma
            // mensagem de constraint que não diz nada a quem chamou.
            throw new ErroDeDominio(ErrosDoAtendimento.DESCONTO_INVALIDO,
                    "o desconto passa do valor do item (" + bruto + ")");
        }
        return desconto;
    }

    /**
     * O teto é percentual, mesmo quando o desconto foi pedido em dinheiro.
     *
     * <p>Senão o limite seria contornável pela forma de digitar: "10%" recusado e "R$ 40,00" — que
     * são os mesmos 50% — aceito.
     */
    private void conferirTeto(Money desconto, Money bruto) {
        int teto = limites.descontoMaximoPercentual(UsuarioAtual.idOuNulo());
        if (bruto.ehZero()) {
            return;
        }
        BigDecimal pedido = desconto.valor()
                .multiply(CEM)
                .divide(bruto.valor(), 4, RoundingMode.HALF_UP);
        if (pedido.compareTo(new BigDecimal(teto)) > 0) {
            throw new ErroDeDominio(ErrosDoAtendimento.DESCONTO_ACIMA_DO_LIMITE,
                    "desconto de " + pedido.setScale(2, RoundingMode.HALF_UP)
                            + "% pedido; seu limite é " + teto + "%");
        }
    }
}
