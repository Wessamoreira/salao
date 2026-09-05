package br.com.salao.notificacao.internal.domain;

import java.time.Duration;

/**
 * RT-NOT-001 — quanto esperar antes de tentar de novo.
 *
 * <p>Dobrando: 1, 2, 4, 8, 16 minutos, e para na quinta falha. Sem a espera crescente, um provedor
 * fora do ar vira uma rajada de chamadas que o mantém fora do ar — e o salão descobre pelo bloqueio
 * da conta, não pela mensagem que não chegou.
 *
 * <p>Cinco tentativas cobrem cerca de meia hora. Além disso a mensagem perdeu o sentido: um
 * lembrete de "amanhã às 14h" que sai seis horas depois é ruído, e insistir num provedor que
 * recusou cinco vezes seguidas não é resiliência, é teimosia com custo.
 */
public final class EsperaEntreTentativas {

    /** Depois disto, a mensagem é dada como falha e alguém precisa olhar. */
    public static final int MAXIMO_DE_TENTATIVAS = 5;

    private EsperaEntreTentativas() {
    }

    /** @param tentativasFeitas quantas já falharam, contando a que acabou de falhar */
    public static Duration apos(int tentativasFeitas) {
        if (tentativasFeitas < 1) {
            return Duration.ZERO;
        }
        return Duration.ofMinutes(1L << Math.min(tentativasFeitas - 1, 4));
    }

    public static boolean desistir(int tentativasFeitas) {
        return tentativasFeitas >= MAXIMO_DE_TENTATIVAS;
    }
}
