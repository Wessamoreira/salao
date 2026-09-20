package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.ErrosDoFinanceiro;
import br.com.salao.financeiro.internal.infra.ConciliacaoJdbc;
import br.com.salao.financeiro.internal.infra.ResolucoesJdbc;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.NomesDeUsuariosApi;
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
import java.util.Objects;
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
    private final ResolucoesJdbc resolucoes;
    private final NomesDeUsuariosApi nomes;
    private final EstabelecimentoApi estabelecimentos;
    private final Relogio relogio;

    public ConsultarConciliacaoUseCase(ConciliacaoJdbc conciliacao, ResolucoesJdbc resolucoes,
                                       NomesDeUsuariosApi nomes,
                                       EstabelecimentoApi estabelecimentos, Relogio relogio) {
        this.conciliacao = conciliacao;
        this.resolucoes = resolucoes;
        this.nomes = nomes;
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
     * @param resolvidas RT-FIN-011 — o que saiu da conferência por decisão de alguém. Conta à parte:
     *                   "conciliadas" responde o que bate sozinho, e um par aceito com forma trocada
     *                   não bate sozinho
     */
    public record Resumo(LocalDate de, LocalDate ate, Totais conciliadas, Totais divergentes,
                         Totais soNaMaquininha, Totais soNoSistema, Totais semExtrato,
                         Totais resolvidas) {
    }

    /**
     * Uma linha da conferência. Nos pares, os dois lados; nas sobras, só o lado que existe.
     *
     * @param instante       a hora que ordena a lista: a da venda, ou a do registro quando só há
     *                       pagamento
     * @param possivelRefeita RT-FIN-011 — há venda cancelada de mesmo valor no dia, já casada com um
     *                       pagamento. Então este valor pode já estar no caixa, e lançá-lo contaria
     *                       duas vezes (RN-FIN-035). Só em "só na maquininha"
     */
    public record Item(UUID id, Instant instante, UUID vendaId, String idNaAdquirente,
                       Instant capturadaEm, Boolean horaInformada, BigDecimal valorVenda,
                       String produto, String bandeira, Integer parcelas, UUID pagamentoId,
                       Instant registradoEm, BigDecimal valorPagamento, String forma,
                       String criterio, List<String> divergencias, boolean possivelRefeita) {
    }

    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_CONCILIAR + "')")
    @Transactional(readOnly = true)
    public Resumo resumir(LocalDate de, LocalDate ate) {
        var periodo = periodo(de, ate);
        var resumo = conciliacao.resumo(periodo.inicio(), periodo.fim(), periodo.fuso());
        return new Resumo(periodo.de(), periodo.ate(), resumo.conciliadas(), resumo.divergentes(),
                resumo.soNaMaquininha(), resumo.soNoSistema(), resumo.semExtrato(),
                resolucoes.contarResolvidas(periodo.inicio(), periodo.fim()));
    }

    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_CONCILIAR + "')")
    @Transactional(readOnly = true)
    public Pagina<Item> listar(Recorte lado, LocalDate de,
                                               LocalDate ate, String cursor, Integer limite) {
        var periodo = periodo(de, ate);
        int quantos = quantos(limite);
        var chave = chave(cursor);
        var itens = conciliacao.itens(lado == null ? Recorte.DIVERGENTES : lado,
                periodo.inicio(), periodo.fim(), periodo.fuso(), chave.antesDe(), chave.antesDoId(),
                quantos + 1);
        return Pagina.de(itens, quantos,
                item -> Cursor.de(item.instante().toString(), item.id().toString()));
    }

    /**
     * RT-FIN-011 — a trilha: o que já foi resolvido à mão no período, e por quem.
     *
     * @param quemResolveu o nome de hoje; nulo quando a reabertura ou a resolução foram do sistema
     * @param instante     a hora da linha conferida — a venda, ou o pagamento quando não há venda
     * @param formaDe      a forma que o pagamento tinha antes, quando a resolução mexeu nela
     * @param lancado      quanto foi escrito no razão, quando foi
     */
    public record ItemResolvido(UUID id, Instant resolvidaEm, String quemResolveu, String acao,
                                String motivo, String alvo, Instant instante, BigDecimal valor,
                                String idNaAdquirente, List<String> divergencias, String formaDe,
                                String formaPara, BigDecimal lancado) {
    }

    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_CONCILIAR + "')")
    @Transactional(readOnly = true)
    public Pagina<ItemResolvido> listarResolvidas(LocalDate de, LocalDate ate, String cursor,
                                                  Integer limite) {
        var periodo = periodo(de, ate);
        int quantos = quantos(limite);
        var chave = chave(cursor);
        var linhas = resolucoes.listar(periodo.inicio(), periodo.fim(), chave.antesDe(),
                chave.antesDoId(), quantos + 1);
        var nomesDaPagina = nomes.nomesDe(linhas.stream()
                .map(ResolucoesJdbc.LinhaDaTrilha::resolvidaPor).filter(Objects::nonNull)
                .distinct().toList());
        return Pagina.de(linhas, quantos,
                        linha -> Cursor.de(linha.resolvidaEm().toString(), linha.id().toString()))
                .mapear(linha -> new ItemResolvido(linha.id(), linha.resolvidaEm(),
                        linha.resolvidaPor() == null ? null
                                : nomesDaPagina.getOrDefault(linha.resolvidaPor(), "Usuário removido"),
                        linha.acao().name(), linha.motivo(), linha.alvo().name(), linha.instante(),
                        linha.valor(), linha.idNaAdquirente(), linha.divergencias(),
                        linha.efeito().formaDe(), linha.efeito().formaPara(),
                        linha.efeito().lancado()));
    }

    private int quantos(Integer limite) {
        return limite == null || limite < 1 ? LIMITE_PADRAO : Math.min(limite, LIMITE_MAXIMO);
    }

    private record Chave(Instant antesDe, UUID antesDoId) {
    }

    private Chave chave(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new Chave(null, null);
        }
        try {
            String[] valores = Cursor.decodificar(cursor).valores();
            return new Chave(Instant.parse(valores[0]), UUID.fromString(valores[1]));
        } catch (IllegalArgumentException | DateTimeParseException
                 | ArrayIndexOutOfBoundsException e) {
            throw new ErroDeDominio(ErrosDoFinanceiro.FILTRO_INVALIDO,
                    "cursor de paginação inválido");
        }
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
