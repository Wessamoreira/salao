package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.PreviaDoFechamento;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.dinheiro.Money;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-FIN-006 — quanto o fechamento vai custar, antes de acontecer.
 *
 * <p>R-UX-05: ação com efeito financeiro exige confirmação explícita <strong>com o efeito descrito
 * em número</strong>. Um "tem certeza?" sem número treina a pessoa a clicar em sim, e aí a
 * confirmação não protege mais nada.
 *
 * <p>Não grava. Usa o mesmo {@link ContasDoPeriodo} e as mesmas recusas de
 * {@link PeriodoDeFechamento} que o fechamento vai usar — uma prévia que aceite o que a
 * confirmação nega é pior do que prévia nenhuma.
 *
 * <p>A permissão é {@code financeiro:close} e não a de leitura: a prévia mostra o salário de todo
 * mundo junto, que é o dado mais sensível do sistema. Quem não pode fechar não precisa vê-lo.
 */
public class PreverFechamentoUseCase {

    private final ContasDoPeriodo contas;
    private final PeriodoDeFechamento periodo;

    public PreverFechamentoUseCase(ContasDoPeriodo contas, PeriodoDeFechamento periodo) {
        this.contas = contas;
        this.periodo = periodo;
    }

    @PreAuthorize("hasAuthority('" + Permissao.FINANCEIRO_FECHAR + "')")
    @Transactional(readOnly = true)
    public PreviaDoFechamento executar(LocalDate de, LocalDate ate) {
        periodo.conferir(de, ate);

        List<ContasDoPeriodo.Posicao> posicoes = contas.em(de, ate);
        List<PreviaDoFechamento.LinhaDaPrevia> linhas = posicoes.stream()
                .map(p -> new PreviaDoFechamento.LinhaDaPrevia(p.profissionalId(),
                        p.saldoAnterior(), p.movimento(), p.aRepassar()))
                .toList();
        Money total = linhas.stream()
                .map(PreviaDoFechamento.LinhaDaPrevia::aRepassar)
                .reduce(Money.ZERO, Money::somar);

        return new PreviaDoFechamento(de, ate, total,
                VersaoDaPrevia.calcular(de, ate, posicoes), linhas);
    }
}
