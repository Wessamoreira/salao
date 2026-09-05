package br.com.salao.notificacao.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Domínio puro: o texto e a espera. */
class ModeloTest {

    private final Modelo lembrete = new Modelo("lembrete",
            "Oi {nome}, seu horário no salão é {quando}. Até lá!");

    @Test
    @DisplayName("preenche os buracos com o que veio")
    void preenche() {
        String texto = lembrete.preencher(Map.of("nome", "Marilda", "quando", "amanhã às 14h"));

        assertThat(texto).isEqualTo("Oi Marilda, seu horário no salão é amanhã às 14h. Até lá!");
    }

    @Test
    @DisplayName("faltar parâmetro é erro, não buraco no texto")
    void faltar_parametro_e_erro() {
        // Sem isto sairia "Oi {nome}, seu horário é amanhã" — entregue, e fazendo o salão parecer
        // quebrado para a cliente. É o defeito que ninguém reporta e todo mundo vê.
        assertThatThrownBy(() -> lembrete.preencher(Map.of("quando", "amanhã")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nome");
    }

    @Test
    @DisplayName("sobrar parâmetro também é erro")
    void sobrar_parametro_e_erro() {
        // Sobrar significa que quem chamou acha que o modelo diz algo que ele não diz — e o texto
        // que sai não é o que a pessoa pensou que ia sair.
        assertThatThrownBy(() -> lembrete.preencher(
                Map.of("nome", "Marilda", "quando", "amanhã", "valor", "R$ 80")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valor");
    }

    @Test
    @DisplayName("o mesmo parâmetro repetido é preenchido em todos os lugares")
    void parametro_repetido() {
        var modelo = new Modelo("oi", "{nome}? {nome}, é você?");

        assertThat(modelo.preencher(Map.of("nome", "Ana"))).isEqualTo("Ana? Ana, é você?");
    }

    @Test
    @DisplayName("valor com cifrão não vira grupo de regex")
    void valor_com_cifrao() {
        // `$` em `replace` de regex é referência a grupo. Sem escapar, "R$ 80" viraria texto
        // truncado ou exceção — e o defeito só aparece com valor em dinheiro, que é justamente o
        // que uma mensagem de salão carrega.
        var modelo = new Modelo("total", "Seu total foi {valor}.");

        assertThat(modelo.preencher(Map.of("valor", "R$ 80,00")))
                .isEqualTo("Seu total foi R$ 80,00.");
    }

    @Test
    @DisplayName("modelo sem nome ou sem texto é recusado")
    void modelo_precisa_de_nome_e_texto() {
        assertThatThrownBy(() -> new Modelo("", "texto"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Modelo("nome", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a espera dobra e para de dobrar")
    void espera_dobra() {
        // Sem a espera crescente, um provedor fora do ar vira uma rajada que o mantém fora do ar.
        assertThat(EsperaEntreTentativas.apos(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(EsperaEntreTentativas.apos(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(EsperaEntreTentativas.apos(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(EsperaEntreTentativas.apos(5)).isEqualTo(Duration.ofMinutes(16));
        assertThat(EsperaEntreTentativas.apos(9)).as("não cresce além do teto")
                .isEqualTo(Duration.ofMinutes(16));
    }

    @Test
    @DisplayName("desiste na quinta falha")
    void desiste_na_quinta() {
        // Além de meia hora a mensagem perdeu o sentido: um lembrete de "amanhã às 14h" que sai
        // seis horas depois é ruído.
        assertThat(EsperaEntreTentativas.desistir(4)).isFalse();
        assertThat(EsperaEntreTentativas.desistir(5)).isTrue();
    }
}
