package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.ErrosDoFinanceiro;
import br.com.salao.financeiro.internal.domain.Recebimento;
import br.com.salao.financeiro.internal.infra.PagamentosJdbc;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-012 — quanto cai, e em que dia (RN-FIN-038).
 *
 * <p>O cronograma é remontado a partir do que foi <strong>fotografado</strong> em cada pagamento —
 * valor, parcelas, taxa e prazo. Renegociar a taxa não muda a previsão do que já foi vendido.
 *
 * <p>Sem período, de hoje até {@value #DIAS_PADRAO} dias; no máximo {@value #DIAS_MAXIMOS}.
 */
public class PreverRecebiveisUseCase {

    public static final int DIAS_MAXIMOS = 180;
    static final int DIAS_PADRAO = 90;

    /** A janela de "vendas recentes sem previsão" — ver {@link PagamentosJdbc#semPrevisaoDesde}. */
    static final int DIAS_SEM_PREVISAO = 90;

    private final PagamentosJdbc pagamentos;
    private final EstabelecimentoApi estabelecimentos;
    private final Relogio relogio;

    public PreverRecebiveisUseCase(PagamentosJdbc pagamentos, EstabelecimentoApi estabelecimentos,
                                   Relogio relogio) {
        this.pagamentos = pagamentos;
        this.estabelecimentos = estabelecimentos;
        this.relogio = relogio;
    }

    public record Dia(LocalDate dia, int parcelas, BigDecimal bruto, BigDecimal taxa,
                      BigDecimal liquido) {
    }

    /**
     * @param semPrevisao vendas no cartão dos últimos {@value #DIAS_SEM_PREVISAO} dias que ficaram sem
     *                    previsão — faixa sem contrato cadastrado na hora do pagamento
     */
    public record Previsao(LocalDate de, LocalDate ate, BigDecimal bruto, BigDecimal taxa,
                           BigDecimal liquido, int semPrevisao, List<Dia> dias) {
    }

    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_LER_TODOS + "')")
    @Transactional(readOnly = true)
    public Previsao executar(LocalDate de, LocalDate ate) {
        ZoneId fuso = estabelecimentos.fusoDe(TenantContext.obrigatorio());
        LocalDate hoje = relogio.hojeEm(fuso);
        LocalDate inicio = de != null ? de : hoje;
        LocalDate fim = ate != null ? ate : inicio.plusDays(DIAS_PADRAO);
        conferir(inicio, fim);

        // Soma por dia; TreeMap porque a tela desenha um calendário, e calendário é em ordem.
        Map<LocalDate, Dia> porDia = new TreeMap<>();
        Money bruto = Money.ZERO;
        Money taxa = Money.ZERO;

        for (var pagamento : pagamentos.comPrevisaoAte(inicio, fim)) {
            // A primeira parcela guardada já é dia útil; o cronograma parte do dia da venda, que é
            // ela menos o prazo contratado — é assim que a segunda parcela não herda o empurrão da
            // primeira (ver Recebimento).
            LocalDate diaDaVenda = pagamento.primeiraParcela()
                    .minusDays(pagamento.diasParaReceber());
            for (var parcela : Recebimento.cronograma(pagamento.valor(), pagamento.taxaPrevista(),
                    pagamento.parcelas(), diaDaVenda, pagamento.diasParaReceber())) {
                if (parcela.quando().isBefore(inicio) || parcela.quando().isAfter(fim)) {
                    continue;
                }
                Dia atual = porDia.get(parcela.quando());
                porDia.put(parcela.quando(), somar(atual, parcela));
                bruto = bruto.somar(parcela.bruto());
                taxa = taxa.somar(parcela.taxa());
            }
        }

        return new Previsao(inicio, fim, bruto.valor(), taxa.valor(),
                bruto.subtrair(taxa).valor(),
                pagamentos.semPrevisaoDesde(hoje.minusDays(DIAS_SEM_PREVISAO)
                        .atStartOfDay(fuso).toInstant()),
                List.copyOf(new ArrayList<>(porDia.values())));
    }

    private static Dia somar(Dia atual, Recebimento.Parcela parcela) {
        if (atual == null) {
            return new Dia(parcela.quando(), 1, parcela.bruto().valor(), parcela.taxa().valor(),
                    parcela.liquido().valor());
        }
        return new Dia(atual.dia(), atual.parcelas() + 1,
                atual.bruto().add(parcela.bruto().valor()),
                atual.taxa().add(parcela.taxa().valor()),
                atual.liquido().add(parcela.liquido().valor()));
    }

    private static void conferir(LocalDate de, LocalDate ate) {
        if (ate.isBefore(de)) {
            throw new ErroDeDominio(ErrosDoFinanceiro.FILTRO_INVALIDO,
                    "o início do período vem depois do fim");
        }
        if (ChronoUnit.DAYS.between(de, ate) + 1 > DIAS_MAXIMOS) {
            throw new ErroDeDominio(ErrosDoFinanceiro.FILTRO_INVALIDO,
                    "o período pode ter no máximo " + DIAS_MAXIMOS + " dias");
        }
    }
}
