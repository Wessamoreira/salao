package br.com.salao.conversacional.internal.application;

import java.util.function.Supplier;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CNV-005 — o que o modelo dispara roda numa transação somente leitura.
 *
 * <p>Uma ferramenta de leitura que grava, ou uma simulação que grava, é defeito de quem a escreveu — e
 * o agente não pode depender de ninguém acertar isso. Aberta aqui, antes da ferramenta, a transação é
 * herdada pelos casos de uso que ela chama (propagação {@code REQUIRED}), e o Postgres recusa qualquer
 * INSERT, UPDATE ou DELETE: {@code cannot execute ... in a read-only transaction}.
 *
 * <p>Não cobre o que abre transação própria ({@code REQUIRES_NEW}) nem a conexão de manutenção. Nenhum
 * caso de uso do sistema faz o primeiro, e ferramenta nenhuma tem motivo para o segundo.
 */
public class TransacaoSomenteLeitura {

    @Transactional(readOnly = true)
    public <T> T executar(Supplier<T> acao) {
        return acao.get();
    }
}
