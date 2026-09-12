package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.ErrosDoFinanceiro;
import br.com.salao.financeiro.api.Lancamento;
import br.com.salao.financeiro.api.TipoDeLancamento;
import br.com.salao.financeiro.internal.infra.LancamentosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-007 — anular <strong>uma</strong> linha do razão.
 *
 * <h2>Por que existe, se o estorno por origem já existia</h2>
 *
 * <p>Até aqui só era possível anular uma origem inteira: todas as linhas de uma comanda, todas as
 * de um vale. Uma comissão lançada para a profissional errada obrigava a reabrir a comanda inteira
 * — desfazendo receita, baixa de estoque e a comissão de quem estava certo — para consertar uma
 * linha.
 *
 * <h2>Um estorno não se estorna</h2>
 *
 * <p>Anular uma anulação é reaplicar o valor original, e reaplicar dinheiro não pode acontecer por
 * engano de clique. A recusa é explícita ({@code ER-FIN-ESTORNO_DE_ESTORNO}) em vez de aceita em
 * silêncio, porque o pedido é ambíguo: quem quer o valor de volta faz um lançamento novo, com
 * motivo e rastro próprio.
 *
 * <h2>A anulação nasce na competência da linha anulada</h2>
 *
 * <p>Ver {@link Anulacao}. Dizer que uma linha nunca deveria ter existido e jogar a correção em
 * outro mês deixaria o mês do erro errado e contaminaria um mês inocente.
 */
public class EstornarLancamentoUseCase {

    private static final Logger log = LoggerFactory.getLogger(EstornarLancamentoUseCase.class);

    private final LancamentosJdbc lancamentos;
    private final Anulacao anulacao;
    private final AuditoriaApi auditoria;

    public EstornarLancamentoUseCase(LancamentosJdbc lancamentos, Anulacao anulacao,
                                     AuditoriaApi auditoria) {
        this.lancamentos = lancamentos;
        this.anulacao = anulacao;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_FECHAR + "')")
    @Transactional
    public int executar(UUID lancamentoId, String motivo) {
        if (motivo == null || motivo.isBlank()) {
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "informe o motivo do estorno");
        }
        UUID tenant = TenantContext.obrigatorio();

        Lancamento original = lancamentos.porId(lancamentoId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoFinanceiro.LANCAMENTO_NAO_ENCONTRADO,
                        "lançamento " + lancamentoId + " não encontrado"));

        if (original.tipo() == TipoDeLancamento.ESTORNO) {
            throw new ErroDeDominio(ErrosDoFinanceiro.ESTORNO_DE_ESTORNO,
                    "este lançamento já é um estorno; para reaplicar o valor, lance de novo");
        }
        // Idempotente, e sem marca na linha original: quem já foi anulado tem uma linha
        // apontando para si.
        if (lancamentos.jaEstornado(lancamentoId)) {
            return 0;
        }

        anulacao.deLancamento(tenant, original, motivo);

        auditoria.registrar(RegistroDeAuditoria.alteracao("LANCAMENTO_ESTORNADO", "lancamento",
                lancamentoId,
                Map.of("tipo", original.tipo().name(),
                        "valor", original.valor().toString(),
                        "competencia", original.dataCompetencia().toString()),
                Map.of("motivo", motivo)));

        log.warn("Lançamento {} ({} de {}) estornado na competência {}: {}", lancamentoId,
                original.tipo(), original.valor(), original.dataCompetencia(), motivo);
        return 1;
    }
}
