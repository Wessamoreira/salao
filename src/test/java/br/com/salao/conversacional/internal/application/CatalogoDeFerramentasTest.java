package br.com.salao.conversacional.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.UsuarioEfetivo;
import br.com.salao.shared.agente.Ferramenta;
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

    private static Ferramenta leitura(String nome, String... permissoes) {
        return new FerramentaDeTeste(nome, a -> FerramentaDeTeste.ok(), permissoes);
    }

    private static Ferramenta escrita(String nome, String... permissoes) {
        return new EscritaDeTeste(nome, permissoes);
    }

    @Test
    @DisplayName("oferece só o que o usuário tem TODAS as permissões para usar")
    void todas_as_permissoes() {
        var catalogo = new CatalogoDeFerramentas(List.of(
                leitura("listar", Permissao.AGENDA_LER_TODAS),
                leitura("financeiro", Permissao.FINANCEIRO_LER_TODOS),
                leitura("duas", Permissao.AGENDA_LER_TODAS, Permissao.FINANCEIRO_LER_TODOS)));

        assertThat(catalogo.permitidasPara(recepcao)).extracting(Ferramenta::nome)
                .containsExactly("listar");
    }

    @Test
    @DisplayName("escrita é oferecida a quem tem a permissão, e só a quem tem")
    void escrita_com_permissao() {
        // O que o modelo alcança dela é a simulação (RN-CNV-014). A gravação só vem da pessoa.
        var catalogo = new CatalogoDeFerramentas(List.of(
                escrita("agendar", Permissao.AGENDA_ESCREVER_TODAS),
                escrita("estornar", Permissao.FINANCEIRO_LER_TODOS)));

        assertThat(catalogo.permitidasPara(recepcao)).extracting(Ferramenta::nome)
                .containsExactly("agendar");
    }

    @Test
    @DisplayName("ferramenta que não declara permissão nunca é oferecida — de leitura ou de escrita")
    void sem_declaracao() {
        // Quem não disse o que exige é tratado como perigoso, e não como livre.
        var livre = leitura("livre");
        var escritaLivre = escrita("escrita_livre");
        var catalogo = new CatalogoDeFerramentas(List.of(livre, escritaLivre));

        assertThat(catalogo.permitidasPara(recepcao)).isEmpty();
        assertThat(CatalogoDeFerramentas.podeUsar(recepcao, escritaLivre))
                .as("a mesma pergunta decide a confirmação").isFalse();
    }

    @Test
    @DisplayName("nome repetido derruba a subida, mesmo entre leitura e escrita")
    void nome_repetido() {
        // Dois beans com o mesmo nome fariam o modelo pedir um e o sistema executar o outro.
        assertThatThrownBy(() -> new CatalogoDeFerramentas(List.of(
                leitura("listar", Permissao.CLIENTE_LER),
                leitura("listar", Permissao.CLIENTE_LER))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CatalogoDeFerramentas(List.of(
                leitura("agendar", Permissao.CLIENTE_LER),
                escrita("agendar", Permissao.AGENDA_ESCREVER_TODAS))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("nome fora do formato dos provedores derruba a subida")
    void nome_invalido() {
        assertThatThrownBy(() -> new CatalogoDeFerramentas(List.of(
                leitura("listar serviços", Permissao.CLIENTE_LER))))
                .isInstanceOf(IllegalStateException.class);
    }
}
