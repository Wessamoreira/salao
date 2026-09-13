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
        List<GrupoDeMenu> grupos,
        List<Menu> menus,
        Map<String, Object> flags,
        Map<String, Object> limites,
        boolean mfaAtivo,
        boolean mfaObrigatorio) {

    public record EstabelecimentoResumo(UUID id, String nome, String timezone, String moeda) {
    }

    /**
     * RT-INF-010 — uma seção da navegação lateral.
     *
     * <p>Os grupos vêm do servidor pela mesma razão que os menus vêm: agrupar no front seria
     * recriar no JavaScript a regra que este endpoint existe para eliminar. A ordem da lista é a
     * ordem da tela, e o rótulo é o que aparece — o front não traduz nem reordena nada.
     *
     * <p>Um grupo sem nenhum menu visível simplesmente não é desenhado. É por isso que a lista
     * chega completa, em vez de filtrada: filtrar é aritmética sobre o que já foi enviado, e
     * mandar grupo vazio deixaria a recepção vendo um título sozinho.
     */
    public record GrupoDeMenu(String id, String rotulo) {
    }

    /** @param grupo o id de um {@link GrupoDeMenu}; define onde o item aparece na lateral */
    public record Menu(String id, String rotulo, String rota, String icone, String grupo) {
    }
}
