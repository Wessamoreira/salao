package br.com.salao.catalogo.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.salao.catalogo.api.BlocoDoServico;
import br.com.salao.catalogo.api.SequenciaDeBlocos;
import br.com.salao.catalogo.api.TipoDeBloco;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

    // ------------------------------------------------------------------ RT-CAT-003

    @Test
    @DisplayName("o recurso é exigido pelo BLOCO, não pelo serviço inteiro")
    void recurso_mora_no_bloco() {
        // A coloração ocupa o lavatório só na lavagem: 30 minutos de 100. Exigi-lo no serviço
        // reservaria o equipamento pelos 100 — o mesmo desperdício que os blocos existem para
        // eliminar, movido para outra coisa escassa.
        UUID lavatorio = UUID.randomUUID();
        var coloracao = new SequenciaDeBlocos(List.of(
                BlocoDoServico.ativo(30),
                BlocoDoServico.pausa(40),
                BlocoDoServico.ativo(30).exigindo(lavatorio)));

        assertThat(coloracao.blocos().get(0).recursosExigidos())
                .as("aplicar a tinta não usa o lavatório").isEmpty();
        assertThat(coloracao.blocos().get(2).recursosExigidos()).containsExactly(lavatorio);
        assertThat(coloracao.recursosExigidos())
                .as("a pergunta grossa: este serviço depende do lavatório?")
                .containsExactly(lavatorio);
    }

    @Test
    @DisplayName("um bloco PAUSA pode exigir recurso: a cliente segue na cadeira")
    void pausa_tambem_ocupa() {
        // O caso que um modelo ingênuo erra. Durante os 40 minutos da química a profissional está
        // livre — e a cadeira não. Não saber dizer isso é vender a mesma cadeira duas vezes.
        UUID cadeira = UUID.randomUUID();
        var coloracao = new SequenciaDeBlocos(List.of(
                BlocoDoServico.ativo(30).exigindo(cadeira),
                BlocoDoServico.pausa(40).exigindo(cadeira),
                BlocoDoServico.ativo(30).exigindo(cadeira)));

        assertThat(coloracao.minutosDoProfissional()).as("a profissional continua livre na pausa")
                .isEqualTo(60);
        assertThat(coloracao.blocos().get(1).recursosExigidos())
                .as("a cadeira, não").containsExactly(cadeira);
    }

    @Test
    @DisplayName("os recursos de um bloco não podem ser alterados por quem os recebeu")
    void recursos_sao_imutaveis() {
        var bloco = BlocoDoServico.ativo(30).exigindo(UUID.randomUUID());

        assertThatThrownBy(() -> bloco.recursosExigidos().add(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("bloco sem recurso é o caso comum, e não precisa dizer nada")
    void sem_recurso_e_conjunto_vazio() {
        assertThat(BlocoDoServico.ativo(30).recursosExigidos()).isEmpty();
        assertThat(new BlocoDoServico(TipoDeBloco.ATIVO, 30, null).recursosExigidos())
                .as("null e vazio significam a mesma coisa: nenhum equipamento disputado")
                .isEqualTo(Set.of());
    }

    // ------------------------------------------------------------------ RT-CAT-004

    @Test
    @DisplayName("a pausa NÃO encolhe quando o profissional é mais rápido")
    void pausa_nao_encolhe() {
        // É a decisão da rotina. A Ana é mais rápida aplicando e finalizando; a química leva os
        // mesmos 40 minutos, porque é reação e não habilidade. Encolher a pausa junto mandaria
        // lavar o cabelo antes da hora, e quem paga o resultado é a cliente.
        var daAna = coloracao().comDuracaoTotal(90);

        assertThat(daAna.duracaoTotal()).isEqualTo(90);
        assertThat(daAna.blocos().get(1).duracaoMinutos()).as("a química").isEqualTo(40);
        assertThat(daAna.minutosDoProfissional()).as("o trabalho encolheu de 60 para 50")
                .isEqualTo(50);
        assertThat(daAna.blocos().get(0).duracaoMinutos()).isEqualTo(25);
        assertThat(daAna.blocos().get(2).duracaoMinutos()).isEqualTo(25);
    }

    @Test
    @DisplayName("serviço de bloco único: a duração é o bloco")
    void bloco_unico() {
        var escova = SequenciaDeBlocos.simples(45);

        assertThat(escova.comDuracaoTotal(30).duracaoTotal()).isEqualTo(30);
        assertThat(escova.comDuracaoTotal(30).blocos()).singleElement()
                .satisfies(b -> assertThat(b.duracaoMinutos()).isEqualTo(30));
    }

    @Test
    @DisplayName("o arredondamento é determinístico e a soma fecha exata")
    void distribuicao_fecha_exata() {
        // Sem regra fixa, a mesma entrada produziria receitas diferentes conforme a ordem de
        // iteração — e a agenda mostraria horários que mudam sozinhos.
        var servico = new SequenciaDeBlocos(List.of(
                BlocoDoServico.ativo(30), BlocoDoServico.pausa(40), BlocoDoServico.ativo(20)));

        var reduzido = servico.comDuracaoTotal(91);

        assertThat(reduzido.duracaoTotal()).isEqualTo(91);
        assertThat(reduzido.minutosDePausa()).isEqualTo(40);
        assertThat(reduzido.comDuracaoTotal(91)).isEqualTo(reduzido);
    }

    @Test
    @DisplayName("nenhum bloco ativo pode desaparecer")
    void bloco_ativo_nunca_some() {
        var servico = new SequenciaDeBlocos(List.of(
                BlocoDoServico.ativo(100), BlocoDoServico.pausa(10), BlocoDoServico.ativo(1)));

        var minimo = servico.comDuracaoTotal(12);

        assertThat(minimo.blocos()).allSatisfy(
                bloco -> assertThat(bloco.duracaoMinutos()).isPositive());
        assertThat(minimo.duracaoTotal()).isEqualTo(12);
    }

    @Test
    @DisplayName("duração que não deixa espaço para o trabalho é recusada")
    void duracao_menor_que_a_pausa() {
        // Dizer que alguém faz a coloração em 40 minutos é dizer que ela aplica, espera a química
        // e finaliza no tempo em que só a química acontece.
        assertThatThrownBy(() -> coloracao().comDuracaoTotal(40))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pausa");
        assertThatThrownBy(() -> coloracao().comDuracaoTotal(41))
                .as("41 deixa 1 minuto para dois blocos ativos")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("o mínimo possível é a pausa mais um minuto por bloco ativo")
    void minimo_absoluto() {
        var minimo = coloracao().comDuracaoTotal(42);

        assertThat(minimo.duracaoTotal()).isEqualTo(42);
        assertThat(minimo.minutosDoProfissional()).isEqualTo(2);
    }

    @Test
    @DisplayName("reescalar preserva os recursos exigidos de cada bloco")
    void reescalar_preserva_recursos() {
        // O que se muda é o tempo, não a receita: se a lavagem exige o lavatório, ela continua
        // exigindo quando a Ana a faz mais rápido.
        UUID lavatorio = UUID.randomUUID();
        var servico = new SequenciaDeBlocos(List.of(
                BlocoDoServico.ativo(30),
                BlocoDoServico.pausa(40),
                BlocoDoServico.ativo(30).exigindo(lavatorio)));

        var daAna = servico.comDuracaoTotal(90);

        assertThat(daAna.blocos().get(2).recursosExigidos()).containsExactly(lavatorio);
        assertThat(daAna.recursosExigidos()).containsExactly(lavatorio);
    }
}