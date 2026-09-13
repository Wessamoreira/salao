package br.com.salao.conversacional.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.UsuarioEfetivo;
import br.com.salao.shared.agente.Ferramenta;
import br.com.salao.shared.agente.Ferramenta.Natureza;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RT-CNV-004 / RN-CNV-010 — a lista permitida, calculada no servidor. */
class CatalogoDeFerramentasTest {

    private final UsuarioEfetivo recepcao = new UsuarioEfetivo(UUID.randomUUID(), "Bia",
            Perfil.RECEPCAO, Set.of(Permissao.AGENDA_LER_TODAS, Permissao.CLIENTE_LER,
                    Permissao.AGENDA_ESCREVER_TODAS));

    private static Ferramenta de(String nome, Natureza natureza, String... permissoes) {
        return new FerramentaDeTeste(nome, natureza, a -> FerramentaDeTeste.ok(), permissoes);
    }

    @Test
    @DisplayName("oferece só o que o usuário tem TODAS as permissões para usar")
    void todas_as_permissoes() {
        var catalogo = new CatalogoDeFerramentas(List.of(
                de("listar", Natureza.LEITURA, Permissao.AGENDA_LER_TODAS),
                de("financeiro", Natureza.LEITURA, Permissao.FINANCEIRO_LER_TODOS),
                de("duas", Natureza.LEITURA, Permissao.AGENDA_LER_TODAS,
                        Permissao.FINANCEIRO_LER_TODOS)));

        assertThat(catalogo.permitidasPara(recepcao)).extracting(Ferramenta::nome)
                .containsExactly("listar");
    }

    @Test
    @DisplayName("escrita nunca é oferecida, mesmo a quem tem a permissão")
    void escrita_nunca() {
        // A recepção pode escrever na agenda pela tela. Pelo agente, escrita só passa por simular →
        // confirmação humana → confirmar (RT-CNV-005/006).
        var catalogo = new CatalogoDeFerramentas(List.of(
                de("cancelar", Natureza.ESCRITA, Permissao.AGENDA_ESCREVER_TODAS)));

        assertThat(catalogo.permitidasPara(recepcao)).isEmpty();
    }

    @Test
    @DisplayName("ferramenta que não declara permissão nunca é oferecida")
    void sem_declaracao() {
        // Quem não disse o que exige é tratado como perigoso, e não como livre.
        var catalogo = new CatalogoDeFerramentas(List.of(de("livre", Natureza.LEITURA)));

        assertThat(catalogo.permitidasPara(recepcao)).isEmpty();
    }

    @Test
    @DisplayName("nome repetido derruba a subida")
    void nome_repetido() {
        // Dois beans com o mesmo nome fariam o modelo pedir um e o sistema executar o outro.
        assertThatThrownBy(() -> new CatalogoDeFerramentas(List.of(
                de("listar", Natureza.LEITURA, Permissao.CLIENTE_LER),
                de("listar", Natureza.LEITURA, Permissao.CLIENTE_LER))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("nome fora do formato dos provedores derruba a subida")
    void nome_invalido() {
        assertThatThrownBy(() -> new CatalogoDeFerramentas(List.of(
                de("listar serviços", Natureza.LEITURA, Permissao.CLIENTE_LER))))
                .isInstanceOf(IllegalStateException.class);
    }
}
