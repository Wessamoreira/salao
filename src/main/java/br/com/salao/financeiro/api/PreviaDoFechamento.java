package br.com.salao.financeiro.api;

import br.com.salao.shared.dinheiro.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * RT-FIN-006 — quanto o fechamento vai custar, antes de acontecer.
 *
 * <p>Existe por R-UX-05: ação com efeito financeiro exige confirmação explícita <strong>com o
 * efeito descrito em número</strong> — <em>"isso vai gerar R$ 3.480,00 de repasse para 4
 * profissionais"</em>. Um "tem certeza?" sem número treina a pessoa a clicar em sim.
 *
 * <p>Não grava nada, e é a mesma forma da simulação de comissão (RT-FIN-002): toda escrita cara
 * deste sistema tem uma prévia que a antecede.
 *
 * @param linhas todo mundo que o fechamento vai alcançar, inclusive quem não recebe nada — quem
 *               ficou devendo precisa aparecer, senão a conferência não fecha
 */
public record PreviaDoFechamento(
        LocalDate de,
        LocalDate ate,
        Money totalARepassar,
        String versao,
        List<LinhaDaPrevia> linhas) {

    /**
     * @param aRepassar já com o piso em zero: quem deve ao salão aparece com zero aqui e com o
     *                  saldo negativo em {@link #saldoFinal()}
     */
    public record LinhaDaPrevia(
            UUID profissionalId,
            Money saldoAnterior,
            Money movimentoDoPeriodo,
            Money aRepassar) {

        /** O que a pessoa tem a receber antes do piso — negativo quando ela é quem deve. */
        public Money saldoFinal() {
            return saldoAnterior.somar(movimentoDoPeriodo);
        }
    }

    /** Quantas pessoas efetivamente recebem — é o número que a frase da confirmação usa. */
    public int quantosRecebem() {
        return (int) linhas.stream().filter(l -> !l.aRepassar().ehZero()).count();
    }
}
