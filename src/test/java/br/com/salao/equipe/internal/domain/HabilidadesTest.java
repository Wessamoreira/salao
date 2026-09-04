package br.com.salao.equipe.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.equipe.api.Habilidades;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Domínio puro: o que um profissional executa. */
class HabilidadesTest {

    private final UUID coloracao = UUID.randomUUID();
    private final UUID manicure = UUID.randomUUID();

    @Test
    @DisplayName("no regime aberto, executa até o serviço que nem existia quando foi configurado")
    void regime_aberto_alcanca_servico_novo() {
        // É a razão de o regime existir. Com lista branca pura, todo serviço novo do cardápio
        // nasceria invisível para todo mundo até alguém revisitar cada cadastro — em silêncio.
        var todas = Habilidades.todosOsServicos();

        assertThat(todas.executa(coloracao)).isTrue();
        assertThat(todas.executa(UUID.randomUUID())).as("um serviço criado depois").isTrue();
    }

    @Test
    @DisplayName("no regime restrito, executa só o que está na lista")
    void regime_restrito_e_lista_branca() {
        var so = Habilidades.apenas(Set.of(manicure));

        assertThat(so.executa(manicure)).isTrue();
        assertThat(so.executa(coloracao)).isFalse();
    }

    @Test
    @DisplayName("restringir a nenhum serviço é recusado: seria sumir da agenda sem dizer")
    void restrito_e_vazio_e_recusado() {
        // O único jeito de deixar alguém sem poder atender nada sem que isso esteja dito em lugar
        // nenhum. Quem quer tirar a pessoa da agenda desativa o profissional.
        assertThatThrownBy(() -> Habilidades.apenas(Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("desative");
        assertThatThrownBy(() -> new Habilidades(false, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("no regime aberto a lista é ignorada, não consultada e vazia")
    void regime_aberto_ignora_a_lista() {
        // Distinção que parece pedante e não é: se o regime aberto lesse a lista, um profissional
        // com lista vazia responderia "não executa nada" — o defeito que a chave evita.
        var aberto = new Habilidades(true, Set.of());

        assertThat(aberto.executa(coloracao)).isTrue();
        assertThat(aberto.servicos()).isEmpty();
    }

    @Test
    @DisplayName("a lista devolvida não pode ser alterada por quem a recebeu")
    void lista_e_imutavel() {
        var so = Habilidades.apenas(Set.of(manicure));

        assertThatThrownBy(() -> so.servicos().add(coloracao))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
