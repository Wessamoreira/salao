package br.com.salao.iam.internal.domain;

import static br.com.salao.iam.api.Permissao.*;

import br.com.salao.iam.api.Perfil;
import java.util.Map;
import java.util.Set;

/**
 * RT-IAM-006 — que permissões cada perfil carrega.
 *
 * <p>Em código, e não em banco, <strong>por enquanto</strong>: enquanto todos os salões usam o
 * mesmo mapa, uma tabela seria configuração que ninguém configura — e ainda assim precisaria de
 * tela, migração e teste. O gatilho para mover está escrito na doc da rotina: o primeiro salão que
 * pedir um perfil diferente.
 *
 * <p>O que já está preparado é o que importa: nada no sistema pergunta pelo perfil. Tudo pergunta
 * por permissão, então trocar a origem do mapa não toca em nenhum caso de uso.
 */
public final class MapaDePermissoes {

    private MapaDePermissoes() {
    }

    private static final Set<String> TUDO = Set.of(
            AGENDA_LER_PROPRIA, AGENDA_LER_TODAS, AGENDA_ESCREVER_PROPRIA, AGENDA_ESCREVER_TODAS,
            COMANDA_ABRIR, COMANDA_FECHAR, COMANDA_DESCONTO, COMANDA_REABRIR,
            FINANCEIRO_LER_PROPRIO, FINANCEIRO_LER_TODOS, FINANCEIRO_FECHAR, FINANCEIRO_CONCILIAR,
            ESTOQUE_LER, ESTOQUE_ESCREVER, PRODUTO_PRECO_ESCREVER,
            CLIENTE_LER, CLIENTE_ESCREVER, CLIENTE_FICHA_LER,
            USUARIO_GERENCIAR, CONFIG_GERENCIAR, RELATORIO_LER);

    private static final Map<Perfil, Set<String>> POR_PERFIL = Map.of(
            Perfil.ADMIN, TUDO,

            // Opera o dia e vê o financeiro do salão, mas não mexe na estrutura: quem cria
            // usuário e muda configuração é o dono.
            Perfil.GERENTE, menos(TUDO, USUARIO_GERENCIAR, CONFIG_GERENCIAR),

            // Só a própria agenda e o próprio extrato. Vê cliente porque precisa saber quem vai
            // atender; não vê a ficha, que é dado de saúde.
            Perfil.PROFISSIONAL, Set.of(
                    AGENDA_LER_PROPRIA, AGENDA_ESCREVER_PROPRIA,
                    FINANCEIRO_LER_PROPRIO, CLIENTE_LER),

            // Agenda para todos, abre e fecha comanda, vende. NÃO vê comissão nem custo de
            // produto — é o que o dono espera, e a recepção normalmente também.
            Perfil.RECEPCAO, Set.of(
                    AGENDA_LER_TODAS, AGENDA_ESCREVER_TODAS,
                    COMANDA_ABRIR, COMANDA_FECHAR, COMANDA_DESCONTO,
                    ESTOQUE_LER, ESTOQUE_ESCREVER,
                    CLIENTE_LER, CLIENTE_ESCREVER),

            // Conta de dispositivo em espaço público: só leitura da agenda, zero financeiro.
            Perfil.PAINEL, Set.of(AGENDA_LER_TODAS),

            // Vazio de propósito: o bot age EM NOME DE um usuário e herda as permissões dele.
            // Dar permissão própria ao bot criaria o confused deputy que o projeto evita.
            Perfil.BOT, Set.of());

    /** Perfis que exigem segundo fator (05-seguranca). */
    private static final Set<Perfil> EXIGEM_MFA = Set.of(Perfil.ADMIN, Perfil.GERENTE);

    private static final Map<Perfil, Integer> DESCONTO_MAXIMO = Map.of(
            Perfil.ADMIN, 100, Perfil.GERENTE, 100, Perfil.RECEPCAO, 10);

    public static Set<String> de(Perfil perfil) {
        return POR_PERFIL.getOrDefault(perfil, Set.of());
    }

    /**
     * Exige MFA quem tem {@code financeiro:read:all} ou gerencia usuários — a regra escrita em
     * {@code 05-seguranca}, expressa por permissão e não por lista de perfis, para continuar
     * valendo quando o mapa mudar.
     */
    public static boolean exigeMfa(Perfil perfil) {
        return EXIGEM_MFA.contains(perfil)
                || de(perfil).contains(FINANCEIRO_LER_TODOS)
                || de(perfil).contains(USUARIO_GERENCIAR);
    }

    public static int descontoMaximoPercentual(Perfil perfil) {
        return DESCONTO_MAXIMO.getOrDefault(perfil, 0);
    }

    private static Set<String> menos(Set<String> base, String... remover) {
        var copia = new java.util.HashSet<>(base);
        copia.removeAll(Set.of(remover));
        return Set.copyOf(copia);
    }
}
