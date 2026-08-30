package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.BaseDeComissao;
import br.com.salao.iam.api.PeriodicidadeDeFechamento;

/**
 * RT-IAM-001 — entrada do provisionamento.
 *
 * <p>Os três últimos campos são anuláveis e caem no padrão brasileiro mais comum. Exigi-los no
 * provisionamento seria pedir ao dono do salão uma decisão sobre base de comissão antes de ele ter
 * visto uma única tela — e essas respostas mudam depois, o que é justamente por que viraram
 * configuração e não código.
 *
 * <p>Os dados do administrador são obrigatórios: um estabelecimento sem ninguém que possa entrar
 * nele não serve para nada, e criá-lo depois deixaria uma janela em que o tenant existe e está
 * inacessível.
 */
public record ProvisionarEstabelecimentoCommand(
        String nome,
        String documento,
        String fusoIana,
        String moeda,
        BaseDeComissao baseComissao,
        Boolean descontoAfetaComissao,
        PeriodicidadeDeFechamento periodicidadeDeFechamento,
        String adminNome,
        String adminEmail,
        String adminSenha) {

    public ProvisionarEstabelecimentoCommand {
        fusoIana = fusoIana == null || fusoIana.isBlank() ? "America/Sao_Paulo" : fusoIana;
        moeda = moeda == null || moeda.isBlank() ? "BRL" : moeda;
        baseComissao = baseComissao == null ? BaseDeComissao.BRUTO : baseComissao;
        descontoAfetaComissao = descontoAfetaComissao != null && descontoAfetaComissao;
        periodicidadeDeFechamento = periodicidadeDeFechamento == null
                ? PeriodicidadeDeFechamento.MENSAL
                : periodicidadeDeFechamento;
    }

    public static ProvisionarEstabelecimentoCommand comPadroes(
            String nome, String documento, String adminNome, String adminEmail, String adminSenha) {
        return new ProvisionarEstabelecimentoCommand(nome, documento, null, null, null, null, null,
                adminNome, adminEmail, adminSenha);
    }
}
