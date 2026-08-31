package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.BaseDeComissao;
import br.com.salao.iam.api.PeriodicidadeDeFechamento;

/**
 * RT-CFG-001 — o que a tela de configuração manda.
 *
 * <p>Todos os campos vêm sempre, e a validação é a mesma do provisionamento: distinguir "não veio"
 * de "veio vazio" em JSON é fonte conhecida de bug, e a tela manda o formulário inteiro.
 */
public record ConfiguracaoCommand(
        String nome,
        String documento,
        String fusoIana,
        String moeda,
        BaseDeComissao baseComissao,
        Boolean descontoAfetaComissao,
        PeriodicidadeDeFechamento periodicidadeDeFechamento,
        String dddPadrao) {
}
