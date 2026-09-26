package br.com.salao.iam.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.Permissao;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RT-IAM-009 — o catálogo que a tela de acessos lê. */
class CatalogoDePerfisTest {

    /**
     * A garantia que um dicionário no front não teria.
     *
     * <p>Sem este teste, a permissão nova entra no {@link MapaDePermissoes}, o perfil passa a
     * concedê-la, e a tela simplesmente não a menciona — sem erro, sem aviso, sem ninguém
     * perceber. Um administrador leria "o que este perfil pode" e a lista estaria incompleta.
     */
    @Test
    @DisplayName("toda permissão do sistema tem frase no catálogo")
    void nenhuma_permissao_sem_frase() throws Exception {
        var todas = new ArrayList<String>();
        for (var campo : Permissao.class.getDeclaredFields()) {
            if (Modifier.isStatic(campo.getModifiers()) && campo.getType() == String.class) {
                todas.add((String) campo.get(null));
            }
        }

        assertThat(todas).isNotEmpty();
        assertThat(CatalogoDePerfis.permissoesComFrase())
                .as("acrescente a frase em CatalogoDePerfis.ORDEM_DE_LEITURA")
                .containsExactlyInAnyOrderElementsOf(todas);
    }

    @Test
    @DisplayName("BOT não é oferecido: o servidor recusa atribuí-lo a uma pessoa")
    void bot_fora_da_lista() {
        assertThat(CatalogoDePerfis.todos()).extracting(p -> p.perfil())
                .doesNotContain(Perfil.BOT)
                .containsExactly(Perfil.ADMIN, Perfil.GERENTE, Perfil.RECEPCAO,
                        Perfil.PROFISSIONAL, Perfil.PAINEL);
    }

    @Test
    @DisplayName("cada perfil sai com rótulo, resumo e a marca de segundo fator do mapa")
    void descreve_cada_perfil() {
        var porPerfil = CatalogoDePerfis.todos();

        assertThat(porPerfil).allSatisfy(p -> {
            assertThat(p.rotulo()).isNotBlank();
            assertThat(p.resumo()).isNotBlank();
            assertThat(p.podeFazer()).isNotEmpty();
            assertThat(p.exigeMfa()).isEqualTo(MapaDePermissoes.exigeMfa(p.perfil()));
        });
    }

    @Test
    @DisplayName("o teto do desconto entra na frase: é o que separa recepção de gerente")
    void desconto_com_teto() {
        // As duas têm `comanda:discount`; sem o teto, a mesma frase descreveria poderes
        // diferentes — e é justamente esta a comparação de quem escolhe um perfil.
        assertThat(frasesDe(Perfil.RECEPCAO)).contains("dar desconto de até 10%");
        assertThat(frasesDe(Perfil.GERENTE)).contains("dar desconto sem limite");
    }

    @Test
    @DisplayName("as frases saem na ordem de leitura, não na ordem do conjunto")
    void ordem_estavel() {
        // `MapaDePermissoes` guarda um `Set`: sem a lista ordenada, as vinte e quatro linhas de
        // um administrador sairiam embaralhadas, e em ordem diferente a cada execução.
        var recepcao = frasesDe(Perfil.RECEPCAO);

        assertThat(recepcao.indexOf("ver a agenda de todo o salão"))
                .isLessThan(recepcao.indexOf("abrir comanda"));
        assertThat(recepcao.indexOf("abrir comanda"))
                .isLessThan(recepcao.indexOf("ver a lista de clientes"));
    }

    private List<String> frasesDe(Perfil perfil) {
        return CatalogoDePerfis.todos().stream()
                .filter(p -> p.perfil() == perfil).findFirst().orElseThrow().podeFazer();
    }
}
