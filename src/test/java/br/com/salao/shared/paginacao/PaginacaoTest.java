package br.com.salao.shared.paginacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaginacaoTest {

    @Test
    void cursor_faz_round_trip() {
        Cursor original = Cursor.de("2026-09-02T13:00:00Z", "a1b2c3");

        Cursor voltou = Cursor.decodificar(original.codificar());

        assertThat(voltou).isEqualTo(original);
        assertThat(voltou.valores()).containsExactly("2026-09-02T13:00:00Z", "a1b2c3");
    }

    @Test
    @DisplayName("cursor codificado é seguro para URL")
    void cursor_seguro_para_url() {
        String codificado = Cursor.de("valor com espaço", "+/=").codificar();

        assertThat(codificado).doesNotContain("+", "/", "=");
    }

    @Test
    void cursor_invalido_e_rejeitado() {
        assertThatThrownBy(() -> Cursor.decodificar("!!!não é base64!!!"))
                .isInstanceOf(Cursor.CursorInvalidoException.class);
    }

    @Test
    @DisplayName("ler limite+1 é como se detecta próxima página sem pagar count(*)")
    void detecta_proxima_pagina_sem_count() {
        List<String> lidos = List.of("a", "b", "c");   // limite 2, leu 3

        Pagina<String> pagina = Pagina.de(lidos, 2, Cursor::de);

        assertThat(pagina.itens()).containsExactly("a", "b");
        assertThat(pagina.temMais()).isTrue();
        assertThat(pagina.proximoCursor()).isNotNull();
    }

    @Test
    void ultima_pagina_nao_tem_cursor() {
        Pagina<String> pagina = Pagina.de(List.of("a", "b"), 2, Cursor::de);

        assertThat(pagina.temMais()).isFalse();
        assertThat(pagina.proximoCursor()).isNull();
    }

    @Test
    void pagina_vazia() {
        Pagina<String> pagina = Pagina.de(List.of(), 10, Cursor::de);

        assertThat(pagina.itens()).isEmpty();
        assertThat(pagina.temMais()).isFalse();
        assertThat(pagina.proximoCursor()).isNull();
    }
}
