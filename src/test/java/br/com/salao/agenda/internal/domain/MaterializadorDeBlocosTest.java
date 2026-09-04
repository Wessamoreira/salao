package br.com.salao.agenda.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.catalogo.api.BlocoDoServico;
import br.com.salao.catalogo.api.SequenciaDeBlocos;
import br.com.salao.catalogo.api.TipoDeBloco;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Domínio puro: a receita virando linha do tempo. */
class MaterializadorDeBlocosTest {

    private static final Instant TREZE_HORAS = Instant.parse("2026-09-02T16:00:00Z");

    private Instant mais(int minutos) {
        return TREZE_HORAS.plusSeconds(minutos * 60L);
    }

    @Test
    @DisplayName("a coloração vira três blocos, e só o do meio não ocupa o profissional")
    void coloracao_gera_tres_blocos() {
        var coloracao = new SequenciaDeBlocos(List.of(
                BlocoDoServico.ativo(30), BlocoDoServico.pausa(40), BlocoDoServico.ativo(30)));

        var blocos = MaterializadorDeBlocos.materializar(coloracao, TREZE_HORAS);

        assertThat(blocos).hasSize(3);
        assertThat(blocos).extracting(MaterializadorDeBlocos.BlocoNoTempo::profissionalOcupado)
                .containsExactly(true, false, true);
        assertThat(blocos.get(0).janela().inicio()).isEqualTo(TREZE_HORAS);
        assertThat(blocos.get(1).janela().inicio()).isEqualTo(mais(30));
        assertThat(blocos.get(2).janela().inicio()).isEqualTo(mais(70));
        assertThat(MaterializadorDeBlocos.periodoTotal(blocos).fim()).isEqualTo(mais(100));
    }

    @Test
    @DisplayName("os blocos são encostados: o fim de um é o início do seguinte")
    void blocos_encostados() {
        // Folga entre eles seria inventar tempo que o salão não cobra e não usa. E como a janela é
        // [inicio, fim), encostar não é sobrepor (RN-AGD-010).
        var coloracao = new SequenciaDeBlocos(List.of(
                BlocoDoServico.ativo(30), BlocoDoServico.pausa(40), BlocoDoServico.ativo(30)));

        var blocos = MaterializadorDeBlocos.materializar(coloracao, TREZE_HORAS);

        assertThat(blocos.get(0).janela().fim()).isEqualTo(blocos.get(1).janela().inicio());
        assertThat(blocos.get(1).janela().fim()).isEqualTo(blocos.get(2).janela().inicio());
    }

    @Test
    @DisplayName("os recursos exigidos viajam com o bloco")
    void recursos_viajam() {
        UUID lavatorio = UUID.randomUUID();
        var coloracao = new SequenciaDeBlocos(List.of(
                BlocoDoServico.ativo(30),
                BlocoDoServico.pausa(40),
                BlocoDoServico.ativo(30).exigindo(lavatorio)));

        var blocos = MaterializadorDeBlocos.materializar(coloracao, TREZE_HORAS);

        assertThat(blocos.get(0).recursosExigidos()).isEmpty();
        assertThat(blocos.get(2).recursosExigidos()).containsExactly(lavatorio);
    }

    @Test
    @DisplayName("serviço de bloco único vira um bloco só")
    void bloco_unico() {
        var blocos = MaterializadorDeBlocos.materializar(SequenciaDeBlocos.simples(45),
                TREZE_HORAS);

        assertThat(blocos).singleElement().satisfies(bloco -> {
            assertThat(bloco.tipo()).isEqualTo(TipoDeBloco.ATIVO);
            assertThat(bloco.janela().fim()).isEqualTo(mais(45));
            assertThat(bloco.profissionalOcupado()).isTrue();
        });
    }

    @Test
    @DisplayName("a ordem começa em zero e é sequencial")
    void ordem_sequencial() {
        var coloracao = new SequenciaDeBlocos(List.of(
                BlocoDoServico.ativo(30), BlocoDoServico.pausa(40), BlocoDoServico.ativo(30)));

        assertThat(MaterializadorDeBlocos.materializar(coloracao, TREZE_HORAS))
                .extracting(MaterializadorDeBlocos.BlocoNoTempo::ordem)
                .containsExactly(0, 1, 2);
    }
}
