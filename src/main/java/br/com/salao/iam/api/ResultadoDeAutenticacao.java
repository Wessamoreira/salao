package br.com.salao.iam.api;

import java.time.Instant;

/**
 * RT-IAM-005 — o login passou a ter dois desfechos possíveis.
 *
 * <p>Tipo selado de propósito: quem consome é obrigado a tratar os dois casos. Com um único tipo
 * e campos nulos, esquecer o segundo fator seria um {@code null} silencioso — e o esquecimento
 * significaria entregar sessão sem MFA a quem o ativou.
 */
public sealed interface ResultadoDeAutenticacao {

    /** Senha correta e sem segundo fator: a sessão já está aberta. */
    record Autenticado(SessaoIniciada sessao) implements ResultadoDeAutenticacao {
    }

    /**
     * Senha correta, mas o usuário tem MFA. Nenhum token de acesso é emitido aqui — só um desafio
     * de vida curta, que sozinho não abre nada.
     */
    record SegundoFatorPendente(String desafio, Instant expiraEm)
            implements ResultadoDeAutenticacao {
    }
}
