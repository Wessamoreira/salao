package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.ApuracaoDaComanda;
import br.com.salao.financeiro.api.Lancamento;
import br.com.salao.financeiro.api.RazaoApi;
import br.com.salao.financeiro.api.TipoDeLancamento;
import br.com.salao.financeiro.internal.infra.LancamentosJdbc;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-003 — o livro-razão do salão.
 *
 * <h2>Receita entra positiva; comissão, negativa</h2>
 *
 * <p>O sinal é o que define débito e crédito, e é o que permite que "quanto sobrou" seja
 * {@code sum(valor)} sem lista de tipos. Uma comanda de R$ 200,00 com 30% de comissão vira duas
 * linhas: +200 de receita e −60 de comissão, e o salão fica com 140 sem ninguém precisar saber
 * quais tipos somam.
 *
 * <h2>A competência é o dia do salão, não o do servidor</h2>
 *
 * <p>A comanda fechada às 23h50 de 31 de março, num salão em Manaus, pertence a março — e o
 * servidor em UTC já está em 1º de abril. Errar isso joga faturamento para o mês seguinte, e é o
 * tipo de erro que só aparece quando o dono compara com o caderno.
 *
 * <h2>Sem {@code @PreAuthorize}</h2>
 *
 * <p>Escrever no razão não é ação de usuário: é consequência de fechar comanda. Quem autoriza é
 * quem tem a ação — a mesma forma de {@code Pagamentos} e de {@code BaixaFefo}.
 */
public class Razao implements RazaoApi {

    private static final Logger log = LoggerFactory.getLogger(Razao.class);

    private static final String ORIGEM_COMANDA = "COMANDA";

    private final LancamentosJdbc lancamentos;
    private final EstabelecimentoApi estabelecimentos;
    private final Relogio relogio;

    public Razao(LancamentosJdbc lancamentos, EstabelecimentoApi estabelecimentos,
                 Relogio relogio) {
        this.lancamentos = lancamentos;
        this.estabelecimentos = estabelecimentos;
        this.relogio = relogio;
    }

    @Override
    @Transactional
    public int apurar(ApuracaoDaComanda apuracao) {
        UUID tenant = TenantContext.obrigatorio();
        if (!lancamentos.pendentesDeEstorno(ORIGEM_COMANDA, apuracao.comandaId()).isEmpty()) {
            // Já apurada. Fechar é transacional e acontece uma vez — mas uma apuração que dobre
            // valores é o tipo de erro que só aparece no repasse do mês.
            log.info("Comanda {} já tinha lançamentos; nada a apurar", apuracao.numero());
            return 0;
        }

        LocalDate competencia = relogio.hojeEm(estabelecimentos.fusoDe(tenant));
        Instant agora = relogio.agora();
        UUID por = UsuarioAtual.idOuNulo();
        int escritas = 0;

        if (!apuracao.receitaDeServico().ehZero()) {
            lancamentos.inserir(tenant, competencia, TipoDeLancamento.RECEITA_SERVICO,
                    ORIGEM_COMANDA, apuracao.comandaId(), "SALAO", null,
                    apuracao.receitaDeServico(),
                    "Serviços da comanda " + apuracao.numero(), null, agora, por);
            escritas++;
        }
        if (!apuracao.receitaDeProduto().ehZero()) {
            lancamentos.inserir(tenant, competencia, TipoDeLancamento.RECEITA_PRODUTO,
                    ORIGEM_COMANDA, apuracao.comandaId(), "SALAO", null,
                    apuracao.receitaDeProduto(),
                    "Produtos da comanda " + apuracao.numero(), null, agora, por);
            escritas++;
        }

        for (ApuracaoDaComanda.ComissaoAApurar comissao : apuracao.comissoes()) {
            if (comissao.valor().ehZero()) {
                // Comissão de zero não é linha: ela não move saldo nenhum e polui o extrato de
                // quem trabalha por salário fixo. Por que foi zero está no executante, com a
                // origem que a cascata gravou.
                continue;
            }
            lancamentos.inserir(tenant, competencia, TipoDeLancamento.COMISSAO, ORIGEM_COMANDA,
                    apuracao.comandaId(), "PROFISSIONAL", comissao.profissionalId(),
                    comissao.valor().negado(),
                    // A descrição carrega o porquê: é o que faz o extrato responder "por que 30%?"
                    // sem consultar regra nenhuma, inclusive depois que a regra deixar de existir.
                    comissao.descricaoDoItem() + " — " + comissao.percentual() + "% ("
                            + comissao.origem() + ")",
                    null, agora, por);
            escritas++;
        }

        log.info("Comanda {} apurada em {} lançamento(s), competência {}", apuracao.numero(),
                escritas, competencia);
        return escritas;
    }

    @Override
    @Transactional
    public int estornarOrigem(String origemTipo, UUID origemId, String motivo) {
        UUID tenant = TenantContext.obrigatorio();
        LocalDate competencia = relogio.hojeEm(estabelecimentos.fusoDe(tenant));
        Instant agora = relogio.agora();
        UUID por = UsuarioAtual.idOuNulo();

        List<Lancamento> pendentes = lancamentos.pendentesDeEstorno(origemTipo, origemId);
        for (Lancamento original : pendentes) {
            lancamentos.inserir(tenant, competencia, TipoDeLancamento.ESTORNO, origemTipo,
                    origemId, original.contraparteTipo(), original.contraparteId(),
                    original.valor().negado(),
                    "Estorno: " + original.descricao() + " — " + motivo,
                    original.id(), agora, por);
        }
        if (!pendentes.isEmpty()) {
            log.warn("{} lançamento(s) estornados de {} {}: {}", pendentes.size(), origemTipo,
                    origemId, motivo);
        }
        return pendentes.size();
    }

    @Override
    public List<Lancamento> daOrigem(String origemTipo, UUID origemId) {
        return lancamentos.daOrigem(origemTipo, origemId);
    }
}
