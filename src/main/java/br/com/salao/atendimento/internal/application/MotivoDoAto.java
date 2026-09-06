package br.com.salao.atendimento.internal.application;

import br.com.salao.atendimento.api.ErrosDoAtendimento;
import br.com.salao.shared.erro.ErroDeDominio;

/**
 * RT-ATD-007 — o motivo que todo ato sobre dinheiro fechado exige.
 *
 * <p>É o que separa a correção legítima do desfazimento silencioso. Sem ele a trilha registra quem
 * e quando, e a pergunta que o dono faz — <em>por que esta comanda de trezentos reais foi
 * estornada?</em> — fica sem resposta.
 *
 * <p>Mesma exigência de RT-AGD-005, que já pedia motivo para cancelar agendamento, e pela mesma
 * razão: "cancelado" sem motivo vira a resposta padrão para tudo.
 */
final class MotivoDoAto {

    private static final int MAXIMO = 500;

    private MotivoDoAto() {
    }

    static String exigir(String bruto) {
        String limpo = bruto == null ? null : bruto.strip();
        if (limpo == null || limpo.isEmpty()) {
            throw new ErroDeDominio(ErrosDoAtendimento.MOTIVO_OBRIGATORIO,
                    "informe por que a comanda está sendo alterada");
        }
        return limpo.substring(0, Math.min(limpo.length(), MAXIMO));
    }
}
