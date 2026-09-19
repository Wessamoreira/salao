package br.com.salao.financeiro.internal.domain;

/**
 * RT-FIN-009 — o arquivo inteiro não serve: vazio, planilha do Excel, sem colunas, grande demais.
 *
 * <p>Linha ruim não chega aqui: ela vira {@link LinhaDoExtrato.Recusada} e o resto do arquivo segue.
 * Esta exceção é só para o que impede de ler qualquer linha.
 */
public class ArquivoDeExtratoInvalidoException extends RuntimeException {

    public ArquivoDeExtratoInvalidoException(String mensagem) {
        super(mensagem);
    }
}
