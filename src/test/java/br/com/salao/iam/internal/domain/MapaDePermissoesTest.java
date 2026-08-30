package br.com.salao.iam.internal.domain;

import static br.com.salao.iam.api.Permissao.*;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.iam.api.Perfil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** As decisões de quem vê o quê — em teste, porque cada uma é uma promessa feita ao dono. */
class MapaDePermissoesTest {

    @Test
    @DisplayName("profissional vê só a própria agenda e o próprio extrato")
    void profissional_e_restrito() {
        var permissoes = MapaDePermissoes.de(Perfil.PROFISSIONAL);

        assertThat(permissoes).contains(AGENDA_LER_PROPRIA, FINANCEIRO_LER_PROPRIO, CLIENTE_LER);
        assertThat(permissoes)
                .as("agenda e faturamento dos outros são justamente o que ele não pode ver")
                .doesNotContain(AGENDA_LER_TODAS, FINANCEIRO_LER_TODOS);
        assertThat(permissoes)
                .as("ficha de química indica alergia — dado de saúde")
                .doesNotContain(CLIENTE_FICHA_LER);
    }

    @Test
    @DisplayName("recepção agenda para todos, mas não vê comissão nem mexe em preço")
    void recepcao_nao_ve_financeiro() {
        var permissoes = MapaDePermissoes.de(Perfil.RECEPCAO);

        assertThat(permissoes).contains(AGENDA_ESCREVER_TODAS, COMANDA_ABRIR, COMANDA_FECHAR);
        assertThat(permissoes).doesNotContain(
                FINANCEIRO_LER_TODOS, FINANCEIRO_LER_PROPRIO, PRODUTO_PRECO_ESCREVER);
        assertThat(MapaDePermissoes.descontoMaximoPercentual(Perfil.RECEPCAO)).isEqualTo(10);
    }

    @Test
    @DisplayName("painel é conta de dispositivo em espaço público: leitura da agenda e nada mais")
    void painel_so_le_agenda() {
        assertThat(MapaDePermissoes.de(Perfil.PAINEL)).containsExactly(AGENDA_LER_TODAS);
    }

    @Test
    @DisplayName("o bot não tem permissão própria")
    void bot_nao_tem_permissao() {
        // Ele age EM NOME DE um usuário e herda as permissões dele. Permissão própria criaria o
        // confused deputy: mandar mensagem ao bot e conseguir o que o próprio login não permite.
        assertThat(MapaDePermissoes.de(Perfil.BOT)).isEmpty();
    }

    @Test
    @DisplayName("gerente opera tudo menos a estrutura")
    void gerente_nao_configura() {
        var permissoes = MapaDePermissoes.de(Perfil.GERENTE);

        assertThat(permissoes).contains(FINANCEIRO_LER_TODOS, FINANCEIRO_FECHAR);
        assertThat(permissoes).doesNotContain(CONFIG_GERENCIAR, USUARIO_GERENCIAR);
    }

    @ParameterizedTest
    @EnumSource(Perfil.class)
    @DisplayName("exige MFA exatamente quem enxerga o financeiro do salão ou gerencia usuários")
    void exigencia_de_mfa_segue_a_permissao(Perfil perfil) {
        var permissoes = MapaDePermissoes.de(perfil);
        boolean sensivel = permissoes.contains(FINANCEIRO_LER_TODOS)
                || permissoes.contains(USUARIO_GERENCIAR);

        if (sensivel) {
            assertThat(MapaDePermissoes.exigeMfa(perfil))
                    .as("%s enxerga dado sensível e precisa de segundo fator", perfil)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("nenhum perfil além de ADMIN pode gerenciar usuários ou configuração")
    void poderes_estruturais_sao_do_admin() {
        for (Perfil perfil : Perfil.values()) {
            if (perfil == Perfil.ADMIN) {
                continue;
            }
            assertThat(MapaDePermissoes.de(perfil))
                    .as("%s", perfil)
                    .doesNotContain(USUARIO_GERENCIAR, CONFIG_GERENCIAR);
        }
    }
}
