package br.com.salao.catalogo.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.catalogo.api.Recurso;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Domínio puro: o recurso do salão. */
class RecursoTest {

    private Recurso lavatorios(int quantidade) {
        return new Recurso(UUID.randomUUID(), "Lavatório", quantidade, true, 0);
    }

    @Test
    @DisplayName("quantidade zero não existe: o que o salão não tem é recurso INATIVO")
    void quantidade_zero_nao_existe() {
        // Zero e `ativo = false` diriam a mesma coisa de duas formas. Duas formas de dizer a mesma
        // coisa é o começo de um relatório que não fecha — a mesma razão de RN-CAT-003.
        assertThatThrownBy(() -> lavatorios(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inativo");
        assertThatThrownBy(() -> lavatorios(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("um recurso é sempre ao menos uma unidade")
    void uma_unidade_basta() {
        assertThat(lavatorios(1).quantidade()).isEqualTo(1);
        assertThat(lavatorios(3).quantidade()).isEqualTo(3);
    }

    @Test
    @DisplayName("recurso sem nome não é recurso")
    void nome_e_obrigatorio() {
        assertThatThrownBy(() -> new Recurso(UUID.randomUUID(), null, 1, true, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Recurso(UUID.randomUUID(), "   ", 1, true, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
