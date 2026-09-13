package br.com.salao.shared.seguranca;

import java.util.Objects;
import java.util.UUID;

/**
 * RT-CNV-002 — o principal de quem age EM NOME DE uma pessoa, e não é ela.
 *
 * <p>É o que o agente conversacional carrega no contexto de segurança quando executa algo pedido
 * por um usuário da equipe pelo WhatsApp. As autoridades são as desse usuário (com o teto do canal,
 * ver {@code PoliticaDoUsuarioEfetivo}); o principal diz, a quem perguntar, que quem apertou o botão
 * foi o agente.
 *
 * <p><strong>Mora em {@code shared}, e não no {@code iam}</strong>, porque {@link UsuarioAtual}
 * precisa reconhecê-lo — é por ele que a auditoria e todo {@code criado_por} atribuem a ação à
 * pessoa — e {@code shared} não depende de módulo nenhum.
 *
 * <p>Distinguir este principal do JWT de uma pessoa não é detalhe de log: é a diferença, numa
 * reclamação, entre "a recepção cancelou" e "o agente cancelou a pedido da recepção".
 */
public record EmNomeDe(UUID usuarioId) {

    public EmNomeDe {
        Objects.requireNonNull(usuarioId, "o agente sempre age em nome de alguém");
    }
}
