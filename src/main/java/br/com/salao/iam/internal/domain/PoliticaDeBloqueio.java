package br.com.salao.iam.internal.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * RT-IAM-002 / RN-IAM-005 — bloqueio progressivo após falhas consecutivas.
 *
 * <h2>Por que progressivo e não fixo</h2>
 *
 * <p>Bloqueio fixo tem os dois defeitos ao mesmo tempo: curto demais não atrapalha um ataque
 * automatizado, longo demais transforma a recepcionista que errou a senha duas vezes num chamado
 * de suporte no meio do expediente.
 *
 * <p>Dobrando a cada falha, as primeiras tentativas — que são quase sempre erro de digitação —
 * custam segundos, e a milésima custa quinze minutos. O teto existe para que o bloqueio não vire
 * negação de serviço permanente contra um usuário legítimo cujo e-mail alguém resolveu atacar.
 *
 * <p>Domínio puro: nenhuma dependência de framework, e por isso testável sem
 * {@code Thread.sleep}.
 */
public final class PoliticaDeBloqueio {

    /** As primeiras falhas não bloqueiam: errar a senha duas vezes é normal. */
    public static final int FALHAS_ANTES_DO_BLOQUEIO = 4;

    public static final Duration BLOQUEIO_INICIAL = Duration.ofSeconds(30);
    public static final Duration BLOQUEIO_MAXIMO = Duration.ofMinutes(15);

    private PoliticaDeBloqueio() {
    }

    /**
     * @return o instante até o qual o acesso fica bloqueado, ou {@code null} se ainda não bloqueia
     */
    public static Instant bloqueioApos(int falhasConsecutivas, Instant agora) {
        if (falhasConsecutivas <= FALHAS_ANTES_DO_BLOQUEIO) {
            return null;
        }
        int excedentes = falhasConsecutivas - FALHAS_ANTES_DO_BLOQUEIO - 1;
        // Math.min antes de multiplicar: 2^60 segundos estoura o long, e o teto de 15 minutos
        // seria alcançado muito antes disso de qualquer forma.
        long fator = 1L << Math.min(excedentes, 20);
        Duration duracao = BLOQUEIO_INICIAL.multipliedBy(fator);
        if (duracao.compareTo(BLOQUEIO_MAXIMO) > 0) {
            duracao = BLOQUEIO_MAXIMO;
        }
        return agora.plus(duracao);
    }

    public static boolean estaBloqueado(Instant bloqueadoAte, Instant agora) {
        return bloqueadoAte != null && bloqueadoAte.isAfter(agora);
    }
}
