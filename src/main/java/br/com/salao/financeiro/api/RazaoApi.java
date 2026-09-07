package br.com.salao.financeiro.api;

import java.util.List;
import java.util.UUID;

/**
 * RT-FIN-003 — contrato do livro-razão.
 *
 * <h2>Por que a apuração é uma chamada, e não um ouvinte do evento</h2>
 *
 * <p>Apurar exige os itens e os executantes da comanda. Se o {@code financeiro} os buscasse, ele
 * dependeria de {@code atendimento} — que já depende dele, para perguntar quanto pagar e quanto
 * falta receber. Seria ciclo, e o Spring Modulith reprovaria o build.
 *
 * <p>A saída não é enfeitar o evento: é o {@code atendimento} <strong>trazer</strong> o que apurar,
 * e o {@code financeiro} decidir em que linhas isso vira. Ele não sabe que comissão entra negativa
 * nem a que mês a linha pertence — e não deveria.
 *
 * <p>E assim a apuração acontece <strong>dentro da transação do fechamento</strong>, que é o que o
 * plano pedia desde o início: <em>"fechar comanda — transacional: estoque + pagamento + comissão +
 * evento"</em>.
 */
public interface RazaoApi {

    /**
     * Escreve as linhas de uma comanda fechada.
     *
     * <p>Idempotente: se a origem já tem linhas não estornadas, não escreve de novo. Fechar é
     * transacional e acontece uma vez, mas uma apuração que dobre valores é o tipo de erro que só
     * aparece no repasse do mês.
     *
     * @return quantas linhas foram escritas; zero significa "já estava apurado"
     */
    int apurar(ApuracaoDaComanda apuracao);

    /**
     * Anula as linhas de uma origem com linhas novas de sinal contrário.
     *
     * <p>Nunca apaga: a role da aplicação não tem {@code delete} nesta tabela (V40). Idempotente
     * pela mesma razão da devolução de estoque — quem já foi estornado tem uma linha apontando
     * para si, e o próprio razão responde se ainda há o que anular.
     *
     * @return quantas linhas foram anuladas
     */
    int estornarOrigem(String origemTipo, UUID origemId, String motivo);

    List<Lancamento> daOrigem(String origemTipo, UUID origemId);
}
