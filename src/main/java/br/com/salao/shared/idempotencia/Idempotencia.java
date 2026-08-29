package br.com.salao.shared.idempotencia;

import java.util.function.Supplier;

/**
 * RT-INF-005 — executa uma escrita no máximo uma vez por chave.
 *
 * <p>Uso no caso de uso, não no controller: o registro de idempotência precisa commitar
 * <strong>na mesma transação</strong> que o efeito de negócio. Fora dela, existe uma janela em que
 * o agendamento foi criado e o registro não — e a repetição cria o segundo.
 *
 * <pre>{@code
 * return idempotencia.executar(
 *         new ChaveDeIdempotencia("criar-agendamento", cmd.chaveIdempotencia()),
 *         cmd,
 *         AgendamentoResponse.class,
 *         () -> criar(cmd));
 * }</pre>
 *
 * <p>Interface e não classe concreta para que o caso de uso seja testável sem banco.
 */
public interface Idempotencia {

    /**
     * @param chave         operação + valor do cabeçalho
     * @param payload       o comando; seu hash detecta reuso de chave com conteúdo diferente
     * @param tipoResposta  o tipo esperado na repetição. <strong>Declarado pelo chamador de
     *                      propósito</strong> — desserializar pelo nome de classe guardado no
     *                      banco seria um vetor de gadget
     * @param acao          a operação de negócio, executada no máximo uma vez
     * @throws br.com.salao.shared.erro.ErroDeDominio {@code ER-INF-IDEMPOTENCIA_CONFLITO} quando a
     *                      mesma chave chega com payload diferente
     */
    <T> ResultadoIdempotente<T> executar(ChaveDeIdempotencia chave,
                                         Object payload,
                                         Class<T> tipoResposta,
                                         Supplier<T> acao);
}
