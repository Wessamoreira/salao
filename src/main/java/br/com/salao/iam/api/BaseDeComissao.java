package br.com.salao.iam.api;

/**
 * RT-IAM-001 — sobre qual valor a comissão do profissional incide.
 *
 * <p>{@code BRUTO}: sobre o valor cobrado do cliente. {@code LIQUIDO}: descontada antes a taxa da
 * maquininha. A diferença é real — cerca de 3% do faturamento em serviço pago no crédito — e a
 * resposta muda de salão para salão, e às vezes muda dentro do mesmo salão.
 */
public enum BaseDeComissao {
    BRUTO, LIQUIDO
}
