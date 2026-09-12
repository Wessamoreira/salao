package br.com.salao.financeiro.internal.application;

import br.com.salao.financeiro.api.ErrosDoFinanceiro;
import br.com.salao.financeiro.internal.infra.FechamentosJdbc;
import br.com.salao.shared.erro.ErroDeDominio;
import java.time.LocalDate;
import java.util.Optional;

/**
 * RT-FIN-006 — o que torna um período fechável.
 *
 * <p>As três recusas moram juntas porque a prévia precisa recusar exatamente o que o fechamento
 * recusaria: uma prévia que aceite o que a confirmação vai negar é pior que nenhuma prévia — ela
 * promete um número e entrega um erro.
 */
public class PeriodoDeFechamento {

    private final FechamentosJdbc fechamentos;
    private final Competencia competencia;

    public PeriodoDeFechamento(FechamentosJdbc fechamentos, Competencia competencia) {
        this.fechamentos = fechamentos;
        this.competencia = competencia;
    }

    public void conferir(LocalDate de, LocalDate ate) {
        if (de == null || ate == null || ate.isBefore(de)) {
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "informe um período com início antes do fim");
        }
        // Fechar um período que ainda não aconteceu seria pagar por trabalho que ninguém fez. O
        // dia corrente vale: salão fecha o mês no dia 31, à noite.
        if (ate.isAfter(competencia.hoje())) {
            throw new ErroDeDominio(ErrosDoFinanceiro.DADOS_INVALIDOS,
                    "não se fecha um período que ainda não terminou; hoje é " + competencia.hoje());
        }
        Optional<LocalDate> ultimo = fechamentos.ultimoFim();
        if (ultimo.isPresent() && !de.isAfter(ultimo.get())) {
            // O EXCLUDE do banco recusa períodos sobrepostos, mas não recusa fechar março DEPOIS
            // de abril — e a soma acumulada até 31 de março não enxerga o repasse de abril, então
            // março seria pago de novo.
            throw new ErroDeDominio(ErrosDoFinanceiro.PERIODO_JA_FECHADO,
                    "o último fechamento vai até " + ultimo.get()
                            + "; o próximo período precisa começar depois disso");
        }
    }
}
