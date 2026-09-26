package br.com.salao.iam.internal.domain;

import static br.com.salao.iam.api.Permissao.*;

import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.PerfilDisponivel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RT-IAM-009 — como os perfis se explicam a quem escolhe um.
 *
 * <h2>Rótulo aqui, e não no front</h2>
 *
 * <p>Mesmo motivo dos menus de {@code ConsultarCapacidadesUseCase}: o front desenha o que vem do
 * servidor. Um dicionário de permissões em JavaScript seria a segunda cópia do
 * {@link MapaDePermissoes}, e a segunda cópia envelhece calada — a permissão nova aparece no mapa,
 * o front continua com a lista antiga, e ninguém percebe porque nada quebra.
 *
 * <p>Aqui um teste prova que toda constante de {@code Permissao} tem frase. É a garantia que o
 * dicionário no front não teria.
 *
 * <h2>Uma lista ordenada, e não um mapa</h2>
 *
 * <p>{@link #ORDEM_DE_LEITURA} é ao mesmo tempo o dicionário e a ordem em que as frases aparecem:
 * agenda, comanda, dinheiro, estoque, cliente, sistema — do que se faz o dia inteiro ao que se faz
 * uma vez por mês. Um {@code Map} devolveria as mesmas frases em ordem arbitrária, e a lista de
 * vinte e quatro itens de um administrador viraria sopa.
 */
public final class CatalogoDePerfis {

    private CatalogoDePerfis() {
    }

    /** O que cada permissão concede, na ordem em que se lê. Toda constante entra aqui. */
    private static final List<Map.Entry<String, String>> ORDEM_DE_LEITURA = List.of(
            Map.entry(AGENDA_LER_PROPRIA, "ver a própria agenda"),
            Map.entry(AGENDA_LER_TODAS, "ver a agenda de todo o salão"),
            Map.entry(AGENDA_ESCREVER_PROPRIA, "marcar e desmarcar na própria agenda"),
            Map.entry(AGENDA_ESCREVER_TODAS, "marcar e desmarcar para qualquer profissional"),

            Map.entry(COMANDA_ABRIR, "abrir comanda"),
            Map.entry(COMANDA_FECHAR, "fechar comanda e receber"),
            // A frase desta leva o limite do perfil: "dar desconto" sem o teto esconde justamente
            // o que separa a recepção do gerente. Ver `frase`.
            Map.entry(COMANDA_DESCONTO, "dar desconto"),
            Map.entry(COMANDA_REABRIR, "reabrir comanda já fechada"),

            Map.entry(FINANCEIRO_LER_PROPRIO, "ver o próprio extrato de comissão"),
            Map.entry(FINANCEIRO_LER_TODOS, "ver o dinheiro do salão inteiro"),
            Map.entry(FINANCEIRO_ADIANTAR, "entregar vale"),
            Map.entry(FINANCEIRO_FECHAR, "fechar o período e apurar comissão"),
            Map.entry(FINANCEIRO_CONCILIAR, "conferir o que a maquininha depositou"),

            Map.entry(ESTOQUE_LER, "ver o estoque"),
            Map.entry(ESTOQUE_ESCREVER, "dar entrada e baixa no estoque"),
            Map.entry(PRODUTO_PRECO_ESCREVER, "mudar preço de produto"),

            Map.entry(CLIENTE_LER, "ver a lista de clientes"),
            Map.entry(CLIENTE_ESCREVER, "cadastrar e corrigir cliente"),
            Map.entry(CLIENTE_FICHA_LER, "ler a ficha técnica, que é dado de saúde"),
            Map.entry(CLIENTE_ANONIMIZAR, "apagar os dados de uma cliente a pedido dela"),

            Map.entry(USUARIO_GERENCIAR, "criar acesso e mudar perfil"),
            Map.entry(CONFIG_GERENCIAR, "mudar a configuração do salão e o cardápio"),
            Map.entry(RELATORIO_LER, "ver relatórios"),
            Map.entry(AGENTE_REVISAR, "revisar o que o agente respondeu"));

    private record Descricao(String rotulo, String resumo) {
    }

    /**
     * O nome e a frase de cada perfil.
     *
     * <p>{@code BOT} tem descrição e mesmo assim não é oferecido ({@link #todos()}): um dia esta
     * tela pode precisar explicar o que é o ator das linhas de auditoria, e deixá-lo sem nome
     * aqui faria a próxima pessoa inventar um segundo lugar para escrevê-lo.
     */
    private static final Map<Perfil, Descricao> DESCRICOES = Map.of(
            Perfil.ADMIN, new Descricao("Administradora",
                    "A dona do salão. Faz tudo, inclusive criar acesso e mudar a configuração."),
            Perfil.GERENTE, new Descricao("Gerente",
                    "Opera o dia e vê o dinheiro do salão — mas não cria acesso "
                            + "nem mexe na configuração."),
            Perfil.RECEPCAO, new Descricao("Recepção",
                    "O balcão: agenda de todo mundo, comanda e cliente. "
                            + "Não vê comissão nem custo."),
            Perfil.PROFISSIONAL, new Descricao("Profissional",
                    "Vê a própria agenda e o próprio extrato, e nada de mais ninguém."),
            Perfil.PAINEL, new Descricao("Painel de parede",
                    "Conta de aparelho: mostra a agenda numa tela pendurada e não toca em nada."),
            Perfil.BOT, new Descricao("Agente",
                    "Não é conta de pessoa: o agente age em nome de quem escreveu e "
                            + "herda as permissões daquela pessoa."));

    /** A ordem da tela: do que manda mais para o que manda menos. */
    private static final List<Perfil> OFERECIDOS = List.of(
            Perfil.ADMIN, Perfil.GERENTE, Perfil.RECEPCAO, Perfil.PROFISSIONAL, Perfil.PAINEL);

    /**
     * Os perfis que se pode dar a uma pessoa.
     *
     * <p>{@code BOT} fica de fora: o servidor recusa atribuí-lo (RT-IAM-007), e oferecer numa tela
     * o que o servidor recusa é convidar o erro só para mostrar a recusa depois.
     */
    public static List<PerfilDisponivel> todos() {
        var lista = new ArrayList<PerfilDisponivel>(OFERECIDOS.size());
        for (Perfil perfil : OFERECIDOS) {
            Descricao descricao = DESCRICOES.get(perfil);
            lista.add(new PerfilDisponivel(perfil, descricao.rotulo(), descricao.resumo(),
                    MapaDePermissoes.exigeMfa(perfil), podeFazer(perfil)));
        }
        return List.copyOf(lista);
    }

    private static List<String> podeFazer(Perfil perfil) {
        var permissoes = MapaDePermissoes.de(perfil);
        var frases = new ArrayList<String>();
        for (var entrada : ORDEM_DE_LEITURA) {
            if (permissoes.contains(entrada.getKey())) {
                frases.add(frase(perfil, entrada));
            }
        }
        return List.copyOf(frases);
    }

    /**
     * O desconto é a única frase que depende do perfil, porque o limite é o que a distingue: a
     * recepção e o gerente têm a mesma permissão e poderes bem diferentes.
     */
    private static String frase(Perfil perfil, Map.Entry<String, String> entrada) {
        if (!COMANDA_DESCONTO.equals(entrada.getKey())) {
            return entrada.getValue();
        }
        int teto = MapaDePermissoes.descontoMaximoPercentual(perfil);
        return teto >= 100 ? "dar desconto sem limite" : "dar desconto de até " + teto + "%";
    }

    /** Só para o teste que prova que nenhuma permissão ficou sem frase. */
    public static List<String> permissoesComFrase() {
        return ORDEM_DE_LEITURA.stream().map(Map.Entry::getKey).toList();
    }
}
