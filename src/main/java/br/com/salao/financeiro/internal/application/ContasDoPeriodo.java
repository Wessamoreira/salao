package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.internal.infra.LancamentosJdbc;
import br.com.salao.shared.dinheiro.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * RT-FIN-006 — a posição de cada profissional num período.
 *
 * <h2>Um cálculo só, usado pela prévia e pelo fechamento</h2>
 *
 * <p>A prévia existe para dizer, antes de confirmar, quanto o fechamento vai custar (R-UX-05). Se
 * ela tivesse o próprio cálculo, a diferença entre os dois números apareceria depois do clique —
 * na única tela do sistema em que ninguém aceita ser surpreendido.
 *
 * <h2>O sinal é invertido aqui, uma vez</h2>
 *
 * <p>No razão, a conta do profissional soma negativo quando o salão deve: comissão sai do salão.
 * Aqui e daqui para cima, <strong>positivo é o salão devendo</strong> — a mesma convenção do
 * extrato (RN-FIN-011), pela mesma razão: ninguém deveria precisar lembrar do sinal ao ler um
 * contracheque.
 *
 * <h2>O acumulado, e não o período</h2>
 *
 * <p>{@code saldoAnterior} é a soma de <em>tudo</em> antes de {@code de}, e não do período
 * anterior. Quem adiantou mais do que ganhou termina o mês devendo, e essa dívida precisa
 * atravessar — um cálculo por período a perderia de vista no dia 1º. Os {@code REPASSE} já feitos
 * estão dentro da mesma soma, zerando o que já foi pago.
 */
public class ContasDoPeriodo {

    static final String CONTRAPARTE = "PROFISSIONAL";

    private final LancamentosJdbc lancamentos;

    public ContasDoPeriodo(LancamentosJdbc lancamentos) {
        this.lancamentos = lancamentos;
    }

    /**
     * @param saldoAnterior positivo é o salão devendo; negativo é a pessoa devendo
     * @param movimento     o que o período produziu, no mesmo sinal
     */
    public record Posicao(UUID profissionalId, Money saldoAnterior, Money movimento) {

        public Money saldoFinal() {
            return saldoAnterior.somar(movimento);
        }

        /**
         * O que sai do caixa: o saldo final com piso em zero.
         *
         * <p>Quem deve ao salão não gera repasse negativo — dinheiro não anda para trás sozinho.
         * A dívida fica no razão e o fechamento seguinte a encontra.
         */
        public Money aRepassar() {
            return saldoFinal().ehNegativo() ? Money.ZERO : saldoFinal();
        }

        /** Se vale a pena existir uma linha para esta pessoa neste fechamento. */
        public boolean temPosicao() {
            return !movimento.ehZero() || !saldoFinal().ehZero();
        }
    }

    /**
     * Todo mundo que este período alcança, na ordem em que o banco os agrupou.
     *
     * <p>Duas consultas agrupadas e nenhum laço de consulta: o fechamento pergunta pelo salão
     * inteiro, e uma consulta por pessoa seria N idas ao banco para montar a tabela que ele já
     * sabe agrupar.
     */
    public List<Posicao> em(LocalDate de, LocalDate ate) {
        Map<UUID, Money> antes = lancamentos.saldosAcumuladosAte(CONTRAPARTE, de.minusDays(1));
        Map<UUID, Money> periodo = lancamentos.saldosNoPeriodo(CONTRAPARTE, de, ate);

        // Quem só tem saldo anterior precisa entrar tanto quanto quem só tem movimento: alguém que
        // ficou devendo em março e não trabalhou em abril continua devendo, e sumir da lista faria
        // a dívida desaparecer da conferência.
        Set<UUID> todos = new LinkedHashSet<>(periodo.keySet());
        todos.addAll(antes.keySet());

        List<Posicao> posicoes = new ArrayList<>(todos.size());
        for (UUID profissional : todos) {
            Posicao posicao = new Posicao(profissional,
                    // Invertido uma vez: no razão, o salão devendo soma negativo.
                    antes.getOrDefault(profissional, Money.ZERO).negado(),
                    periodo.getOrDefault(profissional, Money.ZERO).negado());
            if (posicao.temPosicao()) {
                posicoes.add(posicao);
            }
        }
        /*
         * NÃO APAGUE: esta ordenação é contrato, não estética.
         *
         * `VersaoDaPrevia` faz SHA-256 desta lista para detectar mudança entre a prévia e a
         * confirmação — e a lista nasce de dois `group by` SEM `order by`, onde o PostgreSQL
         * não promete ordem: com `HashAggregate` ela depende do layout do hash, e o planejador
         * troca de estratégia conforme estatística e `work_mem`.
         *
         * Prévia e confirmação são consultas separadas. Sem ordem estável, o mesmo dado gera
         * hashes diferentes e o fechamento recusa com "os valores mudaram" sem nada ter mudado:
         * o dono atualiza a tela e recebe a mesma recusa, sem conseguir pagar ninguém.
         *
         * Guardado por `ContasDoPeriodoTest.ordem_nao_depende_do_banco`, que dubla o repositório
         * para devolver as chaves ao contrário. Um teste de integração não serve aqui: com
         * poucas linhas o banco devolve ordenado por sorte, e o teste passa sem a linha.
         *
         * Por texto e não por `UUID.compareTo`: aquele compara dois `long` ASSINADOS, o que faz
         * a ordem dar saltos em torno do bit de sinal.
         */
        posicoes.sort(Comparator.comparing(p -> p.profissionalId().toString()));
        return List.copyOf(posicoes);
    }
}
