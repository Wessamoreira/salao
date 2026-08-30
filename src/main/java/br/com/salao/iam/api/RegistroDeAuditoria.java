package br.com.salao.iam.api;

import java.util.Map;
import java.util.UUID;

/**
 * RT-IAM-008 — o que o módulo informa; o resto é preenchido sozinho.
 *
 * <p>Quem chama descreve <strong>o quê</strong>. Quem, quando, de onde e sob qual trace são
 * resolvidos por {@code AuditoriaApi} a partir do contexto da requisição — porque pedir isso a
 * cada chamador significaria que, na décima chamada, alguém passaria o usuário errado ou deixaria
 * o IP em branco.
 *
 * @param acao     verbo do que aconteceu: {@code PERFIL_ALTERADO}, {@code USUARIO_DESATIVADO}
 * @param entidade nome do agregado, como no glossário: {@code usuario}, {@code agendamento}
 * @param antes    estado anterior, ou {@code null} em criação
 * @param depois   estado novo, ou {@code null} em remoção
 */
public record RegistroDeAuditoria(
        String acao,
        String entidade,
        UUID entidadeId,
        Map<String, Object> antes,
        Map<String, Object> depois) {

    public static RegistroDeAuditoria criacao(String entidade, UUID id,
                                              Map<String, Object> depois) {
        return new RegistroDeAuditoria(entidade.toUpperCase() + "_CRIADO", entidade, id,
                null, depois);
    }

    public static RegistroDeAuditoria alteracao(String acao, String entidade, UUID id,
                                                Map<String, Object> antes,
                                                Map<String, Object> depois) {
        return new RegistroDeAuditoria(acao, entidade, id, antes, depois);
    }
}
