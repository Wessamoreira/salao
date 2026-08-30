package br.com.salao.iam.api;

/**
 * RT-IAM-008 — trilha append-only de quem fez o quê.
 *
 * <h2>Não é log</h2>
 *
 * <p>Log serve para investigar comportamento do sistema e pode ser apagado, amostrado e filtrado.
 * Auditoria serve para responder <em>"quem alterou este agendamento?"</em> meses depois, numa
 * discussão com cliente ou com profissional — e por isso é imutável, retida por prazo definido e
 * consultável por chave de negócio.
 *
 * <p>A imutabilidade não é convenção: a role da aplicação simplesmente <strong>não tem</strong>
 * {@code UPDATE} nem {@code DELETE} nesta tabela (V3), e há teste provando.
 *
 * <h2>Grava na mesma transação do fato</h2>
 *
 * <p>Registro de uma alteração que não commitou seria uma mentira na trilha — e trilha que mente
 * é pior que trilha ausente, porque alguém vai acreditar nela. Se o negócio falha, a auditoria
 * some junto.
 *
 * <p>Tentativa recusada é outra coisa: é sinal de segurança, e vive no log, não aqui.
 */
public interface AuditoriaApi {

    /** Registra o fato, resolvendo ator, IP, user agent e trace do contexto atual. */
    void registrar(RegistroDeAuditoria registro);
}
