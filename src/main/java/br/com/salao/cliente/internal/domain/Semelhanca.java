package br.com.salao.cliente.internal.domain;

import java.util.Optional;

/**
 * RT-CLI-003 — decide se duas fichas são a mesma pessoa.
 *
 * <h2>Por que o escore de trigrama sozinho não serve</h2>
 *
 * <p>Medido no próprio Postgres, com pares reais de salão:
 *
 * <pre>
 *   maria silva  ×  mariana silva          0,733   pessoas DIFERENTES
 *   maria silva  ×  maria silva santos     0,667   MESMA pessoa
 *   jose antonio ×  jose antonio da costa  0,591   MESMA pessoa
 *   maria silva  ×  maria silvana souza    0,550   pessoas DIFERENTES
 *   maria silva  ×  julia silva            0,412   mãe e filha
 *   ana          ×  ana paula              0,400   MESMA pessoa
 * </pre>
 *
 * <p>Não existe limiar que separe essas linhas: o par de pessoas diferentes com maior escore
 * (0,733) está <em>acima</em> de dois pares que são a mesma pessoa. Qualquer corte junta gente que
 * não deve ser juntada ou perde duplicado óbvio — e o primeiro erro é bem pior, porque mesclar é
 * trabalhoso de desfazer.
 *
 * <h2>O que separa de verdade: o primeiro nome</h2>
 *
 * <p>Mãe e filha compartilham o <strong>sobrenome</strong>; a mesma pessoa cadastrada duas vezes
 * compartilha o <strong>primeiro nome</strong>. É o que distingue "Maria Silva"/"Júlia Silva"
 * (família) de "Ana"/"Ana Paula" (mesma pessoa), que têm escore praticamente idêntico.
 *
 * <p>O escore continua servindo — como confirmação secundária quando não há telefone —, mas não é
 * mais quem decide.
 */
public final class Semelhanca {

    /**
     * Sem telefone para confirmar, exige-se nome bem parecido.
     *
     * <p>0,5 fica acima de "maria silva" × "maria souza" (0,412), que são pessoas diferentes com o
     * mesmo primeiro nome — o caso que este piso existe para barrar.
     */
    private static final double ESCORE_MINIMO_SEM_TELEFONE = 0.5;

    /** Até dois caracteres, um "nome" é uma inicial: "M. Silva", "J. Gonçalves". */
    private static final int TAMANHO_DE_INICIAL = 2;

    private Semelhanca() {
    }

    /**
     * @param escore   {@code similarity()} entre os dois nomes normalizados, de 0 a 1
     * @return vazio quando as fichas não se parecem o bastante para valer o aviso
     */
    public static Optional<GrauDeSemelhanca> avaliar(String nomeDigitado, String nomeExistente,
                                                     boolean mesmoTelefone, double escore) {
        boolean mesmoPrimeiroNome = primeiroNomeCompativel(nomeDigitado, nomeExistente);

        if (mesmoTelefone) {
            // O telefone já é uma coincidência forte; o primeiro nome decide entre "de novo a
            // mesma pessoa" e "outra pessoa da mesma casa".
            return Optional.of(mesmoPrimeiroNome
                    ? GrauDeSemelhanca.PROVAVEL
                    : GrauDeSemelhanca.MESMO_TELEFONE);
        }

        if (mesmoPrimeiroNome && escore >= ESCORE_MINIMO_SEM_TELEFONE) {
            return Optional.of(GrauDeSemelhanca.POSSIVEL);
        }
        return Optional.empty();
    }

    /**
     * Primeiros nomes iguais, ou um deles sendo a inicial do outro.
     *
     * <p>A inicial é limitada a dois caracteres de propósito. Aceitar prefixo de qualquer tamanho
     * faria "Luís" e "Luísa" casarem — que num salão é, com frequência, pai e filha.
     */
    private static boolean primeiroNomeCompativel(String um, String outro) {
        String a = primeiroNome(um);
        String b = primeiroNome(outro);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        String curto = a.length() <= b.length() ? a : b;
        String longo = curto == a ? b : a;
        return curto.length() <= TAMANHO_DE_INICIAL && longo.startsWith(curto);
    }

    private static String primeiroNome(String nome) {
        if (nome == null) {
            return "";
        }
        String limpo = SemAcento.de(nome).strip();
        int espaco = limpo.indexOf(' ');
        return espaco < 0 ? limpo : limpo.substring(0, espaco);
    }
}
