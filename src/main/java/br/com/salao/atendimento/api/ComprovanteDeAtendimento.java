package br.com.salao.atendimento.api;

import br.com.salao.shared.dinheiro.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * RT-FIN-008 — o comprovante de um atendimento, para a cliente.
 *
 * <h2>Não é nota fiscal, e o texto diz isso</h2>
 *
 * <p>R-UX-16 é explícito: nota fiscal está fora do escopo da v1. Um recibo que não avisa o que
 * <strong>não</strong> é acaba usado como se fosse — e o salão descobre isso numa fiscalização. A
 * frase é responsabilidade do renderizador, não de quem chama: assim ela não pode ser esquecida.
 *
 * <h2>É projeção, não registro</h2>
 *
 * <p>Nada disto é gravado. O comprovante se monta dos itens, descontos e pagamentos que já estão
 * na comanda, com o snapshot de preço que RN-ATD-010 garante — então ele é reprodutível, e dois
 * comprovantes da mesma comanda são idênticos. Guardá-lo criaria uma segunda cópia dos mesmos
 * números, e a cópia é a que discorda depois de um estorno.
 *
 * @param fuso   o fuso do salão: a data do comprovante é o dia do salão, não o do servidor
 *               (ADR-0009). Uma cliente atendida às 23h50 em Manaus não quer um recibo de amanhã
 */
public record ComprovanteDeAtendimento(
        String salao,
        long numero,
        Instant fechadaEm,
        String fuso,
        String cliente,
        List<LinhaDoComprovante> itens,
        Money bruto,
        Money desconto,
        Money total,
        List<PagamentoDoComprovante> pagamentos) {

    /**
     * @param descricao o que a cliente reconhece: o nome do serviço ou do produto
     * @param desconto  o desconto DAQUELA linha, já em valor. Zero não é impresso
     */
    public record LinhaDoComprovante(
            String descricao,
            BigDecimal quantidade,
            Money precoUnitario,
            Money desconto,
            Money valorTotal) {
    }

    /**
     * @param forma    como ela pagou, em palavra e não em sigla de enum
     * @param parcelas só aparece quando é mais de uma
     */
    public record PagamentoDoComprovante(String forma, int parcelas, Money valor) {
    }
}
