package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.ErrosDoFinanceiro;
import br.com.salao.financeiro.internal.infra.ConciliacaoJdbc;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.paginacao.Cursor;
import br.com.salao.shared.paginacao.Pagina;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-010 — a conferência: quanto bate, quanto bate com diferença, e o que só um dos lados tem.
 *
 * <p>Os dias são os do salão. Sem período, o mês corrente até hoje — a conciliação é um fechamento mensal.
 * No máximo {@value #DIAS_MAXIMOS} dias.
 */
public class ConsultarConciliacaoUseCase {

    public static final int DIAS_MAXIMOS = 92;
    static final int LIMITE_PADRAO = 50;
    static final int LIMITE_MAXIMO = 200;

    private final ConciliacaoJdbc conciliacao;
    private final EstabelecimentoApi estabelecimentos;
    private final Relogio relogio;

    public ConsultarConciliacaoUseCase(ConciliacaoJdbc conciliacao,
                                       EstabelecimentoApi estabelecimentos, Relogio relogio) {
        this.conciliacao = conciliacao;
        this.estabelecimentos = estabelecimentos;
        this.relogio = relogio;
    }

    /** As quatro perguntas da conferência, e os pares divididos entre as duas primeiras. */
    public enum Recorte { DIVERGENTES, SO_NA_MAQUININHA, SO_NO_SISTEMA, CONCILIADAS }

    public record Totais(int quantidade, BigDecimal valor) {
    }

    /**
     * @param semExtrato pagamentos no cartão em dias que nenhuma importação cobre — falta de arquivo, e
     *                   não divergência
     */
    public record Resumo(LocalDate de, LocalDate ate, Totais conciliadas, Totais divergentes,
                         Totais soNaMaquininha, Totais soNoSistema, Totais semExtrato) {
    }

    /**
     * Uma linha da conferência. Nos pares, os dois lados; nas sobras, só o lado que existe.
     *
     * @param instante a hora que ordena a lista: a da venda, ou a do registro quando só há pagamento
     */
    public record Item(UUID id, Instant instante, UUID vendaId, String idNaAdquirente,
                       Instant capturadaEm, Boolean horaInformada, BigDecimal valorVenda,
                       String produto, String bandeira, Integer parcelas, UUID pagamentoId,
                       Instant registradoEm, BigDecimal valorPagamento, String forma,
                       String criterio, List<String> divergencias) {
    }

    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_CONCILIAR + "')")
    @Transactional(readOnly = true)
    public Resumo resumir(LocalDate de, LocalDate ate) {
        var periodo = periodo(de, ate);
        var resumo = conciliacao.resumo(periodo.inicio(), periodo.fim(), periodo.fuso());
        return new Resumo(periodo.de(), periodo.ate(), resumo.conciliadas(), resumo.divergentes(),
                resumo.soNaMaquininha(), resumo.soNoSistema(), resumo.semExtrato());
    }

    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_CONCILIAR + "')")
    @Transactional(readOnly = true)
    public Pagina<Item> listar(Recorte lado, LocalDate de,
                                               LocalDate ate, String cursor, Integer limite) {
        var periodo = periodo(de, ate);
        int quantos = limite == null || limite < 1 ? LIMITE_PADRAO : Math.min(limite, LIMITE_MAXIMO);
        Instant antesDe = null;
        UUID antesDoId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] valores = Cursor.decodificar(cursor).valores();
                antesDe = Instant.parse(valores[0]);
                antesDoId = UUID.fromString(valores[1]);
            } catch (IllegalArgumentException | DateTimeParseException
                     | ArrayIndexOutOfBoundsException e) {
                throw new ErroDeDominio(ErrosDoFinanceiro.FILTRO_INVALIDO,
                        "cursor de paginação inválido");
            }
        }
        var itens = conciliacao.itens(lado == null ? Recorte.DIVERGENTES : lado,
                periodo.inicio(), periodo.fim(), periodo.fuso(), antesDe, antesDoId, quantos + 1);
        return Pagina.de(itens, quantos,
                item -> Cursor.de(item.instante().toString(), item.id().toString()));
    }

    private record Periodo(LocalDate de, LocalDate ate, Instant inicio, Instant fim, ZoneId fuso) {
    }

    private Periodo periodo(LocalDate de, LocalDate ate) {
        ZoneId fuso = estabelecimentos.fusoDe(TenantContext.obrigatorio());
        LocalDate hoje = relogio.hojeEm(fuso);
        LocalDate fimDoPeriodo = ate != null ? ate : hoje;
        LocalDate inicioDoPeriodo = de != null ? de : fimDoPeriodo.withDayOfMonth(1);
        if (inicioDoPeriodo.isAfter(fimDoPeriodo)) {
            throw new ErroDeDominio(ErrosDoFinanceiro.FILTRO_INVALIDO,
                    "o início do período vem depois do fim");
        }
        if (ChronoUnit.DAYS.between(inicioDoPeriodo, fimDoPeriodo) + 1 > DIAS_MAXIMOS) {
            throw new ErroDeDominio(ErrosDoFinanceiro.FILTRO_INVALIDO,
                    "o período pode ter no máximo " + DIAS_MAXIMOS + " dias");
        }
        return new Periodo(inicioDoPeriodo, fimDoPeriodo, inicioDoPeriodo.atStartOfDay(fuso).toInstant(),
                fimDoPeriodo.plusDays(1).atStartOfDay(fuso).toInstant(), fuso);
    }
}
