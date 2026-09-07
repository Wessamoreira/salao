package br.com.salao.financeiro.internal.application;

import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.time.LocalDate;

/**
 * RN-FIN-010 — a que dia pertence a linha que está sendo escrita.
 *
 * <h2>Uma regra, um lugar</h2>
 *
 * <p>A comanda fechada às 23h50 de 31 de março num salão em Manaus pertence a março, e o servidor
 * em UTC já está em 1º de abril. Errar isso joga faturamento para o mês seguinte, e só aparece
 * quando o dono compara com o caderno.
 *
 * <p>Existe como classe porque o vale (RT-FIN-005) precisa da mesma resposta que a apuração
 * (RT-FIN-003). Repetir a linha nos dois seria repetir uma regra que tem exatamente um enunciado —
 * e a segunda cópia é a que continua usando o fuso errado depois que a primeira for corrigida.
 *
 * <p>Não recebe data de fora, e é de propósito: um vale com competência escolhida por quem chama
 * poderia ser empurrado para dentro de um período já fechado (RT-FIN-006), depois de o repasse ter
 * sido pago.
 */
public class Competencia {

    private final EstabelecimentoApi estabelecimentos;
    private final Relogio relogio;

    public Competencia(EstabelecimentoApi estabelecimentos, Relogio relogio) {
        this.estabelecimentos = estabelecimentos;
        this.relogio = relogio;
    }

    /** O dia civil do salão do tenant atual. */
    public LocalDate hoje() {
        return relogio.hojeEm(estabelecimentos.fusoDe(TenantContext.obrigatorio()));
    }
}
