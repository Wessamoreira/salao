package br.com.salao.iam.api;

import java.time.Instant;
import java.util.UUID;

/**
 * RT-IAM-007 — o usuário como a tela de gestão o vê.
 *
 * <p>Sem hash de senha, sem contador de falhas, sem instante de bloqueio: são dados de
 * autenticação, e listar usuários não é autenticar. Um DTO que carrega o que a tela não usa é um
 * vazamento esperando o próximo endpoint que o devolva sem pensar.
 */
public record UsuarioResumo(
        UUID id,
        String nome,
        String email,
        /** RT-CNV-002 — E.164, só dígitos. Nulo para quem não usa o agente, que é quase todo mundo. */
        String telefone,
        Perfil perfil,
        boolean ativo,
        boolean mfaAtivo,
        Instant ultimoAcessoEm) {
}
