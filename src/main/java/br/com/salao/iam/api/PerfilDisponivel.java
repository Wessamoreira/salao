package br.com.salao.iam.api;

import java.util.List;

/**
 * RT-IAM-009 — um perfil como a tela de acessos o oferece.
 *
 * <p>Quem escolhe o perfil de alguém precisa saber o que aquilo concede. "GERENTE" não diz se a
 * pessoa passa a ver o financeiro do salão — e é exatamente essa a pergunta de quem está
 * escolhendo.
 *
 * <p>As frases vêm daqui pela mesma razão que os menus vêm de {@link Capacidades}: um dicionário
 * de permissões no front seria uma segunda cópia do mapa, e a segunda cópia diverge na primeira
 * permissão nova — em silêncio. No servidor há teste provando que nenhuma permissão fica sem
 * frase.
 *
 * @param podeFazer o que este perfil concede, em ordem de leitura e em português de gente
 * @param exigeMfa quando verdadeiro, quem receber este perfil não acessa nada antes de configurar
 *                 o segundo fator (RN-IAM-014) — não é opção da tela nem do salão
 */
public record PerfilDisponivel(
        Perfil perfil,
        String rotulo,
        String resumo,
        boolean exigeMfa,
        List<String> podeFazer) {
}
