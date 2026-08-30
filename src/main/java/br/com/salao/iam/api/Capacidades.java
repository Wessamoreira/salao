package br.com.salao.iam.api;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * RT-IAM-006 — o contrato que tira regra de negócio do front.
 *
 * <p>O front <strong>desenha o que vem daqui</strong>. Não existe
 * {@code if (perfil === 'ADMIN')} em lugar nenhum: menu, botão e limite chegam prontos.
 *
 * <p>E o backend <strong>valida de novo</strong> em toda chamada. Esconder botão é UX; a
 * autorização acontece no caso de uso, com {@code @PreAuthorize} e checagem de posse.
 *
 * @param limites números que o front precisa para avisar <em>antes</em> ("desconto acima de 10%
 *                precisa do gerente") sem conhecer a regra — ele só compara com o que recebeu
 * @param mfaObrigatorio quando verdadeiro e {@code mfaAtivo} falso, o backend recusa tudo além da
 *                       inscrição do segundo fator (RN-IAM-014)
 */
public record Capacidades(
        UUID usuarioId,
        String nome,
        String email,
        Perfil perfil,
        EstabelecimentoResumo estabelecimento,
        Set<String> permissoes,
        List<Menu> menus,
        Map<String, Object> flags,
        Map<String, Object> limites,
        boolean mfaAtivo,
        boolean mfaObrigatorio) {

    public record EstabelecimentoResumo(UUID id, String nome, String timezone, String moeda) {
    }

    public record Menu(String id, String rotulo, String rota, String icone) {
    }
}
