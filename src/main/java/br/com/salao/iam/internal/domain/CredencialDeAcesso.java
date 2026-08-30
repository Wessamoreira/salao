package br.com.salao.iam.internal.domain;

import br.com.salao.iam.api.Perfil;
import java.time.Instant;
import java.util.UUID;

/**
 * RT-IAM-002 — o mínimo necessário para decidir um login.
 *
 * <p>Projeção estreita de propósito: é o único dado que atravessa estabelecimentos, lido pela
 * conexão de plataforma antes de o tenant ser conhecido. Quanto menos campos, menor o alcance
 * dessa consulta — e ela não traz telefone, documento nem qualquer coisa além do que a decisão
 * exige.
 */
public record CredencialDeAcesso(
        UUID usuarioId,
        UUID estabelecimentoId,
        String email,
        String senhaHash,
        Perfil perfil,
        boolean ativo,
        int falhasConsecutivas,
        Instant bloqueadoAte,
        boolean mfaAtivo) {
}
