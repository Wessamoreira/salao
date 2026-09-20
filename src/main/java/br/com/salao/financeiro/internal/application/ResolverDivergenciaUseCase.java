package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.ErrosDoFinanceiro;
import br.com.salao.financeiro.api.FormaDePagamento;
import br.com.salao.financeiro.api.RazaoApi;
import br.com.salao.financeiro.api.TipoDeLancamento;
import br.com.salao.financeiro.internal.domain.AcaoDaResolucao;
import br.com.salao.financeiro.internal.domain.AlvoDaResolucao;
import br.com.salao.financeiro.internal.domain.Correcao;
import br.com.salao.financeiro.internal.domain.EfeitoDaResolucao;
import br.com.salao.financeiro.internal.infra.LancamentosJdbc;
import br.com.salao.financeiro.internal.infra.ResolucoesJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-011 — fecha uma linha da conferência: o que foi decidido, por quê e por quem (RN-FIN-033).
 *
 * <h2>Corrigir troca classificação; dinheiro vira lançamento</h2>
 *
 * <p>Ver {@link Correcao}. Forma, bandeira e parcelas mudam no pagamento; diferença de valor vira
 * {@code AJUSTE} no razão, na competência de <strong>hoje</strong> — uma resolução de setembro feita em
 * novembro não pode entrar num período já fechado e pago (RT-FIN-006).
 *
 * <h2>O lançamento nasce apontando para a resolução</h2>
 *
 * <p>O id da resolução é sorteado aqui, antes de qualquer escrita, porque o lançamento já nasce com ele
 * como origem. É o que torna a reabertura idempotente de graça: {@code estornarOrigem} pergunta ao
 * próprio razão se ainda há o que estornar, sem marca nenhuma na resolução.
 *
 * <h2>Dois cliques</h2>
 *
 * <p>Quem garante que um alvo não é resolvido duas vezes é o índice parcial do banco (V60), e não uma
 * leitura antes de escrever: duas abas leem as duas "não resolvido". O conflito vira
 * {@code ER-FIN-JA_RESOLVIDA}.
 */
public class ResolverDivergenciaUseCase {

    private static final Logger log = LoggerFactory.getLogger(ResolverDivergenciaUseCase.class);

    /** A origem das linhas que esta rotina escreve no razão. */
    static final String ORIGEM = "CONCILIACAO";

    static final int MOTIVO_MINIMO = 3;
    static final int MOTIVO_MAXIMO = 500;

    private final ResolucoesJdbc resolucoes;
    private final LancamentosJdbc lancamentos;
    private final RazaoApi razao;
    private final Competencia competencia;
    private final Relogio relogio;
    private final AuditoriaApi auditoria;

    public ResolverDivergenciaUseCase(ResolucoesJdbc resolucoes, LancamentosJdbc lancamentos,
                                      RazaoApi razao, Competencia competencia, Relogio relogio,
                                      AuditoriaApi auditoria) {
        this.resolucoes = resolucoes;
        this.lancamentos = lancamentos;
        this.razao = razao;
        this.competencia = competencia;
        this.relogio = relogio;
        this.auditoria = auditoria;
    }

    /**
     * @param forma só com {@code alvo = PAGAMENTO}: a forma verdadeira, que não é de cartão
     */
    public record Comando(AlvoDaResolucao alvo, UUID id, AcaoDaResolucao acao, String motivo,
                          FormaDePagamento forma) {
    }

    public record Resultado(UUID id, AcaoDaResolucao acao, String formaDe, String formaPara,
                            BigDecimal lancado, UUID lancamentoId) {
    }

    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_CONCILIAR + "')")
    @Transactional
    public Resultado executar(Comando comando) {
        String motivo = motivo(comando.motivo());
        UUID resolucaoId = UUID.randomUUID();
        Instant agora = relogio.agora();
        UUID por = UsuarioAtual.idOuNulo();

        Efeitos efeitos = switch (comando.alvo()) {
            case PAR -> par(comando, resolucaoId, motivo);
            case VENDA -> venda(comando, resolucaoId, motivo);
            case PAGAMENTO -> pagamento(comando);
        };

        try {
            resolucoes.gravar(resolucaoId, comando.alvo(), comando.id(), comando.acao(), motivo,
                    efeitos.efeito(), efeitos.lancamentoId(), agora, por);
        } catch (ResolucoesJdbc.JaResolvidoException e) {
            throw new ErroDeDominio(ErrosDoFinanceiro.JA_RESOLVIDA,
                    "esta linha já foi resolvida; recarregue a conferência");
        }

        auditoria.registrar(RegistroDeAuditoria.alteracao("CONCILIACAO_RESOLVIDA",
                "resolucao_de_conciliacao", resolucaoId,
                Map.of("alvo", comando.alvo().name(), "alvoId", comando.id().toString()),
                detalhes(comando, motivo, efeitos)));

        log.info("Conciliação: {} de {} {} — {}{}", comando.acao(), comando.alvo(), comando.id(),
                motivo, efeitos.efeito().lancado() == null ? ""
                        : " (lançado " + efeitos.efeito().lancado() + " no razão)");

        return new Resultado(resolucaoId, comando.acao(), efeitos.efeito().formaDe(),
                efeitos.efeito().formaPara(), efeitos.efeito().lancado(), efeitos.lancamentoId());
    }

    /** RN-FIN-036 — a resolução não se reescreve: reabre, desfazendo o que fez, e as duas ficam. */
    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_CONCILIAR + "')")
    @Transactional
    public void reabrir(UUID resolucaoId, String motivoDaReabertura) {
        String motivo = motivo(motivoDaReabertura);
        var resolucao = resolucoes.porId(resolucaoId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoFinanceiro.RESOLUCAO_NAO_ENCONTRADA,
                        "resolução " + resolucaoId + " não encontrada"));
        if (resolucao.reabertaEm() != null) {
            throw new ErroDeDominio(ErrosDoFinanceiro.JA_RESOLVIDA,
                    "esta resolução já foi reaberta");
        }

        // Primeiro marcar: se outra reabertura chegou antes, nada foi desfeito duas vezes.
        if (!resolucoes.reabrir(resolucaoId, motivo, relogio.agora(), UsuarioAtual.idOuNulo())) {
            throw new ErroDeDominio(ErrosDoFinanceiro.JA_RESOLVIDA,
                    "esta resolução já foi reaberta");
        }

        var efeito = resolucao.efeito();
        if (efeito.mudouPagamento()) {
            UUID pagamentoId = resolucao.pagamentoId() != null ? resolucao.pagamentoId()
                    : resolucoes.par(resolucao.conciliacaoId())
                            .map(ResolucoesJdbc.ParACorrigir::pagamentoId).orElse(null);
            if (pagamentoId != null) {
                resolucoes.classificarPagamento(pagamentoId,
                        FormaDePagamento.valueOf(efeito.formaDe()), efeito.bandeiraDe(),
                        efeito.parcelasDe(), efeito.nsuDe(), efeito.autorizacaoDe(),
                        efeito.statusDe());
            }
        }
        // Idempotente pelo próprio razão: uma linha já anulada tem outra apontando para si.
        razao.estornarOrigem(ORIGEM, resolucaoId, motivo);

        auditoria.registrar(RegistroDeAuditoria.alteracao("CONCILIACAO_REABERTA",
                "resolucao_de_conciliacao", resolucaoId,
                Map.of("acao", resolucao.acao().name()), Map.of("motivo", motivo)));
        log.warn("Conciliação: resolução {} ({}) reaberta — {}", resolucaoId, resolucao.acao(),
                motivo);
    }

    // ------------------------------------------------------------------ um alvo de cada vez

    private record Efeitos(EfeitoDaResolucao efeito, UUID lancamentoId) {
    }

    private Efeitos par(Comando comando, UUID resolucaoId, String motivo) {
        var par = resolucoes.par(comando.id())
                .orElseThrow(() -> new ErroDeDominio(ErrosDoFinanceiro.RESOLUCAO_NAO_ENCONTRADA,
                        "par " + comando.id() + " não encontrado"));
        if (par.divergencias().isEmpty()) {
            throw new ErroDeDominio(ErrosDoFinanceiro.RESOLUCAO_NAO_APLICAVEL,
                    "este par não tem divergência para resolver");
        }
        if (comando.acao() == AcaoDaResolucao.LANCAR) {
            throw new ErroDeDominio(ErrosDoFinanceiro.RESOLUCAO_NAO_APLICAVEL,
                    "um par casado não se lança no caixa; corrija ou aceite como está");
        }
        if (comando.acao() == AcaoDaResolucao.ACEITAR) {
            return new Efeitos(EfeitoDaResolucao.NENHUM, null);
        }

        var correcao = Correcao.calcular(par.divergencias(), par.produto(), par.bandeiraDaVenda(),
                par.parcelasDaVenda(), par.valorDaVenda(), par.valorDoPagamento());
        if (!correcao.mudaAlgo()) {
            throw new ErroDeDominio(ErrosDoFinanceiro.RESOLUCAO_NAO_APLICAVEL,
                    "não há o que corrigir neste par: a venda foi cancelada na adquirente. "
                            + "Aceite como está, com motivo");
        }

        FormaDePagamento forma = correcao.forma() != null ? correcao.forma() : par.forma();
        String bandeira = correcao.bandeira() != null ? correcao.bandeira() : par.bandeira();
        int parcelas = correcao.parcelas() != null ? correcao.parcelas() : par.parcelas();
        // O que a correção não resolve segue valendo: um par com a venda cancelada continua divergente
        // mesmo depois de a forma ser acertada.
        String status = correcao.restantes().isEmpty() ? "CONCILIADO" : "DIVERGENTE";

        resolucoes.classificarPagamento(par.pagamentoId(), forma, bandeira, parcelas, par.nsu(),
                par.autorizacao(), status);

        UUID lancamentoId = correcao.diferenca() == null ? null
                : lancar(resolucaoId, correcao.diferenca(),
                        "Conciliação: diferença de valor da venda " + comando.id() + " — " + motivo);

        return new Efeitos(new EfeitoDaResolucao(par.forma().name(), forma.name(), par.bandeira(),
                bandeira, par.parcelas(), parcelas, par.nsu(), par.autorizacao(), par.status(),
                status, correcao.diferenca()), lancamentoId);
    }

    private Efeitos venda(Comando comando, UUID resolucaoId, String motivo) {
        var venda = resolucoes.venda(comando.id())
                .orElseThrow(() -> new ErroDeDominio(ErrosDoFinanceiro.RESOLUCAO_NAO_ENCONTRADA,
                        "venda " + comando.id() + " não encontrada"));
        if (comando.acao() != AcaoDaResolucao.LANCAR) {
            throw new ErroDeDominio(ErrosDoFinanceiro.RESOLUCAO_NAO_APLICAVEL,
                    "uma venda sem pagamento no sistema só se lança no caixa");
        }
        if (venda.temPar()) {
            throw new ErroDeDominio(ErrosDoFinanceiro.RESOLUCAO_NAO_APLICAVEL,
                    "esta venda já casou com um pagamento do sistema");
        }
        if (!"APROVADA".equals(venda.situacao())) {
            throw new ErroDeDominio(ErrosDoFinanceiro.RESOLUCAO_NAO_APLICAVEL,
                    "venda cancelada na adquirente não é dinheiro que entrou");
        }
        UUID lancamentoId = lancar(resolucaoId, venda.valor(),
                "Conciliação: venda na maquininha sem comanda — " + motivo);
        return new Efeitos(EfeitoDaResolucao.lancamento(venda.valor()), lancamentoId);
    }

    private Efeitos pagamento(Comando comando) {
        var pagamento = resolucoes.pagamento(comando.id())
                .orElseThrow(() -> new ErroDeDominio(ErrosDoFinanceiro.RESOLUCAO_NAO_ENCONTRADA,
                        "pagamento " + comando.id() + " não encontrado"));
        if (comando.acao() != AcaoDaResolucao.CORRIGIR) {
            throw new ErroDeDominio(ErrosDoFinanceiro.RESOLUCAO_NAO_APLICAVEL,
                    "um pagamento sem venda na maquininha só tem a forma corrigida");
        }
        if (pagamento.temPar()) {
            throw new ErroDeDominio(ErrosDoFinanceiro.RESOLUCAO_NAO_APLICAVEL,
                    "este pagamento já casou com uma venda da maquininha");
        }
        if (!pagamento.forma().ehCartao()) {
            throw new ErroDeDominio(ErrosDoFinanceiro.RESOLUCAO_NAO_APLICAVEL,
                    "este pagamento não é de cartão, e não está na conferência");
        }
        FormaDePagamento nova = comando.forma();
        if (nova == null || nova.ehCartao() || !nova.traDinheiro()) {
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "informe a forma verdadeira: dinheiro, Pix ou transferência");
        }

        // Sem cartão, os dados de cartão não descrevem mais nada: guardá-los faria a conferência de
        // amanhã procurar um NSU que nunca existiu na maquininha.
        resolucoes.classificarPagamento(pagamento.id(), nova, null, 1, null, null, "NAO_APLICAVEL");

        return new Efeitos(new EfeitoDaResolucao(pagamento.forma().name(), nova.name(),
                pagamento.bandeira(), null, pagamento.parcelas(), 1, pagamento.nsu(),
                pagamento.autorizacao(), pagamento.status(), "NAO_APLICAVEL", null), null);
    }

    // ------------------------------------------------------------------ apoio

    private UUID lancar(UUID resolucaoId, BigDecimal valor, String descricao) {
        return lancamentos.inserir(TenantContext.obrigatorio(), competencia.hoje(),
                TipoDeLancamento.AJUSTE, ORIGEM, resolucaoId, "SALAO", null, new Money(valor),
                descricao, null, relogio.agora(), UsuarioAtual.idOuNulo());
    }

    private String motivo(String texto) {
        String limpo = texto == null ? "" : texto.strip();
        if (limpo.length() < MOTIVO_MINIMO || limpo.length() > MOTIVO_MAXIMO) {
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "escreva o motivo, entre " + MOTIVO_MINIMO + " e " + MOTIVO_MAXIMO
                            + " caracteres");
        }
        return limpo;
    }

    private static Map<String, Object> detalhes(Comando comando, String motivo, Efeitos efeitos) {
        Map<String, Object> detalhes = new HashMap<>();
        detalhes.put("acao", comando.acao().name());
        detalhes.put("motivo", motivo);
        if (efeitos.efeito().formaPara() != null) {
            detalhes.put("forma", efeitos.efeito().formaDe() + " → " + efeitos.efeito().formaPara());
        }
        if (efeitos.efeito().lancado() != null) {
            detalhes.put("lancado", efeitos.efeito().lancado().toPlainString());
        }
        return Map.copyOf(detalhes);
    }
}
