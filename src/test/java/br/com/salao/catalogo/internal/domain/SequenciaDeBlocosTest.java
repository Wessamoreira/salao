package br.com.salao.catalogo.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.catalogo.api.BlocoDoServico;
import br.com.salao.catalogo.api.SequenciaDeBlocos;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Domínio puro: a receita do serviço. */
class SequenciaDeBlocosTest {

    /** Coloração: aplica 30, química age 40, lava e finaliza 30. */
    private SequenciaDeBlocos coloracao() {
        return new SequenciaDeBlocos(List.of(
                BlocoDoServico.ativo(30), BlocoDoServico.pausa(40), BlocoDoServico.ativo(30)));
    }

    @Test
    @DisplayName("a pausa separa o tempo da CLIENTE do tempo do PROFISSIONAL")
    void separa_tempo_da_cliente_do_tempo_do_profissional() {
        // É a razão de o modelo existir. Uma agenda que trate coloração como 100 minutos joga
        // fora 40 minutos de trabalho — num sábado, o horário inteiro de outra cliente.
        var coloracao = coloracao();

        assertThat(coloracao.duracaoTotal()).as("quanto a cliente fica no salão").isEqualTo(100);
        assertThat(coloracao.minutosDoProfissional()).as("quanto custa do profissional")
                .isEqualTo(60);
        assertThat(coloracao.minutosLivresNoMeio()).as("o que dá para vender a outra cliente")
                .isEqualTo(40);
    }

    @Test
    @DisplayName("serviço simples é um bloco só, e a conta continua valendo")
    void servico_simples() {
        var escova = SequenciaDeBlocos.simples(45);

        assertThat(escova.duracaoTotal()).isEqualTo(45);
        assertThat(escova.minutosDoProfissional()).isEqualTo(45);
        assertThat(escova.temPausa()).isFalse();
        assertThat(escova.minutosLivresNoMeio()).isZero();
    }

    @Test
    @DisplayName("não começa em pausa — a cliente chegaria para esperar")
    void nao_comeca_em_pausa() {
        // Não é serviço, é atraso.
        assertThatThrownBy(() -> new SequenciaDeBlocos(List.of(
                BlocoDoServico.pausa(20), BlocoDoServico.ativo(30))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ATIVO");
    }

    @Test
    @DisplayName("não termina em pausa — ninguém escalado para lavar")
    void nao_termina_em_pausa() {
        // O atendimento encerraria com a química agindo, a agenda marcaria a próxima cliente, e
        // a primeira ficaria sentada com tinta no cabelo.
        assertThatThrownBy(() -> new SequenciaDeBlocos(List.of(
                BlocoDoServico.ativo(30), BlocoDoServico.pausa(40))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ATIVO");
    }

    @Test
    @DisplayName("dois blocos do mesmo tipo em sequência são recusados")
    void nao_repete_o_tipo() {
        // Dois ATIVO seguidos são um ATIVO com a soma. Permitir deixaria a mesma receita ser
        // escrita de várias formas — e duas fichas do mesmo serviço com blocos diferentes é o
        // começo de um relatório que não fecha.
        assertThatThrownBy(() -> new SequenciaDeBlocos(List.of(
                BlocoDoServico.ativo(30), BlocoDoServico.ativo(20))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("junte-os");

        assertThatThrownBy(() -> new SequenciaDeBlocos(List.of(
                BlocoDoServico.ativo(30), BlocoDoServico.pausa(10),
                BlocoDoServico.pausa(10), BlocoDoServico.ativo(30))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("duas pausas cabem, desde que alternadas")
    void duas_pausas_alternadas() {
        // Luzes: aplica, age, lava e aplica matiz, age, finaliza. Existe.
        var luzes = new SequenciaDeBlocos(List.of(
                BlocoDoServico.ativo(40), BlocoDoServico.pausa(30),
                BlocoDoServico.ativo(20), BlocoDoServico.pausa(15),
                BlocoDoServico.ativo(25)));

        assertThat(luzes.duracaoTotal()).isEqualTo(130);
        assertThat(luzes.minutosDoProfissional()).isEqualTo(85);
        assertThat(luzes.blocos()).hasSize(5);
    }

    @Test
    @DisplayName("serviço sem bloco nenhum é recusado")
    void sem_bloco() {
        assertThatThrownBy(() -> new SequenciaDeBlocos(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SequenciaDeBlocos(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("bloco de duração zero ou negativa é recusado")
    void duracao_precisa_ser_positiva() {
        assertThatThrownBy(() -> BlocoDoServico.ativo(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BlocoDoServico.pausa(-10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a sequência devolvida não pode ser alterada por quem a recebeu")
    void sequencia_e_imutavel() {
        var coloracao = coloracao();

        assertThatThrownBy(() -> coloracao.blocos().add(BlocoDoServico.ativo(10)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
