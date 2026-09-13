package br.com.salao.iam.api;

/**
 * RT-CNV-002 — por que um telefone não virou usuário efetivo.
 *
 * <p><strong>Para diagnóstico, nunca para resposta.</strong> O agente responde igual em todos os
 * casos — como desconhecido. Dizer a quem mandou a mensagem "este número é de um administrador sem
 * segundo fator" confirmaria, para quem está sondando, que o número pertence à equipe e qual o
 * perfil dela. Mesma decisão de {@code CREDENCIAIS_INVALIDAS}.
 */
public enum MotivoDaRecusa {

    /** Nenhum usuário deste salão tem este telefone. É o caso de toda cliente. */
    TELEFONE_DESCONHECIDO,

    USUARIO_INATIVO,

    /**
     * Bloqueado por tentativas de senha. Um bloqueio em curso indica ataque à conta, e o canal não
     * deve ser a porta lateral dele.
     */
    USUARIO_BLOQUEADO,

    /**
     * O perfil exige segundo fator (RN-IAM-014) e o canal não tem como apresentá-lo. Na web, sem MFA
     * esse perfil não acessa nada; pelo canal, idem.
     */
    PERFIL_EXIGE_SEGUNDO_FATOR,

    /** Conta de dispositivo ({@code PAINEL}) ou o próprio {@code BOT}: não são pessoas. */
    PERFIL_SEM_CONVERSA
}
