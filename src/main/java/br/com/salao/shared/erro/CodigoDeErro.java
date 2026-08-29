package br.com.salao.shared.erro;

import org.springframework.http.HttpStatus;

/**
 * RT-INF-003 — contrato de erro entre backend e front.
 *
 * <p>O {@code codigo} é <strong>estável</strong>: o front mapeia código para a mensagem que exibe.
 * É isso que permite trocar o texto sem tocar no front e traduzir depois sem reescrever nada — e
 * é por isso que o front <em>nunca</em> pode fazer {@code if} sobre o campo {@code detail}.
 *
 * <p>Cada módulo implementa este contrato num enum próprio, e todo código novo é registrado na
 * seção "Erros" do {@code regras.md} do módulo.
 */
public interface CodigoDeErro {

    /** Formato {@code ER-<MOD>-<NOME>}, ex.: {@code ER-AGD-CONFLITO_HORARIO}. */
    String codigo();

    HttpStatus status();

    /** Título curto e estável. A mensagem exibida ao usuário é responsabilidade do front. */
    String titulo();
}
