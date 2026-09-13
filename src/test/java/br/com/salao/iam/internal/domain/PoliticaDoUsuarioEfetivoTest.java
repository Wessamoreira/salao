package br.com.salao.iam.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.salao.iam.api.Identificacao;
import br.com.salao.iam.api.MotivoDaRecusa;
import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.Permissao;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RT-CNV-002 — quem vira usuário efetivo, e com o quê. */
class PoliticaDoUsuarioEfetivoTest {

    private static final UUID ID = UUID.randomUUID();
    private static final Instant AGORA = Instant.parse("2026-09-12T15:00:00Z");

    private Identificacao avaliar(Perfil perfil) {
        return PoliticaDoUsuarioEfetivo.avaliar(ID, "Bia", perfil, true, null, AGORA);
    }

    @Test
    @DisplayName("recepção vira usuário efetivo com as permissões dela")
    void recepcao() {
        var resultado = avaliar(Perfil.RECEPCAO);

        assertThat(resultado).isInstanceOfSatisfying(Identificacao.Identificado.class, id -> {
            assertThat(id.usuario().usuarioId()).isEqualTo(ID);
            assertThat(id.usuario().permissoes())
                    .isEqualTo(MapaDePermissoes.de(Perfil.RECEPCAO));
        });
    }

    @Test
    @DisplayName("TODO perfil que exige segundo fator é recusado — inclusive os que ainda não existem")
    void segundo_fator_para_todo_perfil() {
        /*
         * A regra é sobre `exigeMfa`, e não sobre uma lista de perfis escrita aqui. O dia em que o
         * mapa der `financeiro:read:all` à recepção, ela passa a exigir MFA na web — e este teste
         * garante que ela passa a ser recusada no canal no mesmo dia, sem ninguém lembrar.
         */
        for (Perfil perfil : Perfil.values()) {
            if (MapaDePermissoes.exigeMfa(perfil) && perfil != Perfil.BOT && perfil != Perfil.PAINEL) {
                assertThat(avaliar(perfil)).as(perfil.name())
                        .isEqualTo(new Identificacao.NaoIdentificado(
                                MotivoDaRecusa.PERFIL_EXIGE_SEGUNDO_FATOR));
            }
        }
        assertThat(avaliar(Perfil.ADMIN)).isInstanceOf(Identificacao.NaoIdentificado.class);
        assertThat(avaliar(Perfil.GERENTE)).isInstanceOf(Identificacao.NaoIdentificado.class);
    }

    @Test
    @DisplayName("nenhum usuário efetivo carrega permissão de escopo próprio")
    void sem_escopo_proprio() {
        // `:own` promete "só o que é seu", e nenhum caso de uso confere o dono. Pelo agente, isso
        // seria um prompt pedindo a agenda de todo mundo e recebendo.
        for (Perfil perfil : Perfil.values()) {
            if (avaliar(perfil) instanceof Identificacao.Identificado id) {
                assertThat(id.usuario().permissoes()).as(perfil.name())
                        .noneMatch(p -> p.endsWith(":own"));
            }
        }
    }

    @Test
    @DisplayName("profissional é identificado, perde o :own e mantém o resto")
    void profissional() {
        var resultado = avaliar(Perfil.PROFISSIONAL);

        assertThat(resultado).isInstanceOfSatisfying(Identificacao.Identificado.class, id ->
                assertThat(id.usuario().permissoes())
                        .contains(Permissao.CLIENTE_LER)
                        .doesNotContain(Permissao.AGENDA_LER_PROPRIA,
                                Permissao.AGENDA_ESCREVER_PROPRIA,
                                Permissao.FINANCEIRO_LER_PROPRIO));
    }

    @Test
    @DisplayName("ninguém recebe pelo canal permissão que o próprio perfil não tem")
    void nunca_mais_que_o_perfil() {
        // O teto só tira. Uma política que acrescentasse seria o confused deputy por outra porta.
        for (Perfil perfil : Perfil.values()) {
            assertThat(MapaDePermissoes.de(perfil))
                    .as(perfil.name())
                    .containsAll(PoliticaDoUsuarioEfetivo.permissoesPeloCanal(perfil));
        }
    }

    @Test
    @DisplayName("painel e bot não são pessoas")
    void contas_que_nao_sao_pessoas() {
        assertThat(avaliar(Perfil.PAINEL)).isEqualTo(
                new Identificacao.NaoIdentificado(MotivoDaRecusa.PERFIL_SEM_CONVERSA));
        assertThat(avaliar(Perfil.BOT)).isEqualTo(
                new Identificacao.NaoIdentificado(MotivoDaRecusa.PERFIL_SEM_CONVERSA));
    }

    @Test
    @DisplayName("inativo é recusado, e o motivo é a inatividade — não o perfil")
    void inativo() {
        var resultado = PoliticaDoUsuarioEfetivo.avaliar(ID, "Bia", Perfil.ADMIN, false, null, AGORA);

        assertThat(resultado).isEqualTo(
                new Identificacao.NaoIdentificado(MotivoDaRecusa.USUARIO_INATIVO));
    }

    @Test
    @DisplayName("bloqueio em curso recusa; bloqueio vencido não")
    void bloqueio() {
        var emCurso = PoliticaDoUsuarioEfetivo.avaliar(ID, "Bia", Perfil.RECEPCAO, true,
                AGORA.plusSeconds(60), AGORA);
        var vencido = PoliticaDoUsuarioEfetivo.avaliar(ID, "Bia", Perfil.RECEPCAO, true,
                AGORA, AGORA);

        assertThat(emCurso).isEqualTo(
                new Identificacao.NaoIdentificado(MotivoDaRecusa.USUARIO_BLOQUEADO));
        assertThat(vencido).isInstanceOf(Identificacao.Identificado.class);
    }
}
