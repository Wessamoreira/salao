package br.com.salao.iam.api;

/**
 * RT-CNV-002 — o resultado de perguntar "quem é este telefone?".
 *
 * <p>Tipo fechado, e não {@code Optional<UsuarioEfetivo>}: o vazio de um {@code Optional} não diz
 * por quê, e "por que o dono não conseguiu usar o agente" é a primeira pergunta do suporte quando
 * a resposta for {@link MotivoDaRecusa#PERFIL_EXIGE_SEGUNDO_FATOR}.
 */
public sealed interface Identificacao {

    record Identificado(UsuarioEfetivo usuario) implements Identificacao {
    }

    record NaoIdentificado(MotivoDaRecusa motivo) implements Identificacao {
    }
}
