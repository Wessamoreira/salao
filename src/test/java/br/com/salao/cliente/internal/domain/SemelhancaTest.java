package br.com.salao.cliente.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Domínio puro. Cada caso é uma situação de balcão, e os escores são os medidos de verdade no
 * Postgres — não números inventados para o teste passar.
 */
class SemelhancaTest {

    private Optional<GrauDeSemelhanca> comTelefone(String digitado, String existente, double e) {
        return Semelhanca.avaliar(digitado, existente, true, e);
    }

    private Optional<GrauDeSemelhanca> semTelefone(String digitado, String existente, double e) {
        return Semelhanca.avaliar(digitado, existente, false, e);
    }

    @Test
    @DisplayName("mesmo telefone e mesmo primeiro nome: provavelmente a mesma pessoa")
    void mesmo_telefone_mesmo_primeiro_nome() {
        assertThat(comTelefone("Maria Silva", "Maria da Silva", 0.800))
                .contains(GrauDeSemelhanca.PROVAVEL);
        assertThat(comTelefone("Ana", "Ana Paula", 0.400))
                .contains(GrauDeSemelhanca.PROVAVEL);
        assertThat(comTelefone("José Antônio", "José Antônio da Costa", 0.591))
                .contains(GrauDeSemelhanca.PROVAVEL);
    }

    @Test
    @DisplayName("mãe e filha: mesmo telefone, primeiro nome diferente — não é duplicado")
    void mae_e_filha_nao_sao_duplicado() {
        // Escore 0,412, praticamente o mesmo de "Ana"/"Ana Paula" (0,400), que É a mesma pessoa.
        // Nenhum limiar separa os dois casos; o primeiro nome separa.
        assertThat(comTelefone("Júlia Silva", "Maria Silva", 0.412))
                .contains(GrauDeSemelhanca.MESMO_TELEFONE);
    }

    @Test
    @DisplayName("Mariana não é Maria, mesmo com escore alto")
    void mariana_nao_e_maria() {
        // 0,733 — mais alto que "José Antônio"/"José Antônio da Costa" (0,591), que é a MESMA
        // pessoa. É o par que prova que ordenar por escore não resolve.
        assertThat(semTelefone("Mariana Silva", "Maria Silva", 0.733)).isEmpty();
        assertThat(comTelefone("Mariana Silva", "Maria Silva", 0.733))
                .contains(GrauDeSemelhanca.MESMO_TELEFONE);
    }

    @Test
    @DisplayName("a inicial conta como o primeiro nome")
    void inicial_conta_como_primeiro_nome() {
        assertThat(comTelefone("M Silva", "Maria Silva", 0.538))
                .contains(GrauDeSemelhanca.PROVAVEL);
        assertThat(comTelefone("Juliana G", "Juliana Gonçalves", 0.474))
                .contains(GrauDeSemelhanca.PROVAVEL);
    }

    @Test
    @DisplayName("prefixo longo NÃO conta: Luísa não é Luís")
    void prefixo_longo_nao_conta() {
        // Num salão isso é, com frequência, pai e filha — e eles dividem o telefone.
        assertThat(comTelefone("Luísa Prado", "Luís Prado", 0.700))
                .contains(GrauDeSemelhanca.MESMO_TELEFONE);
    }

    @Test
    @DisplayName("sem telefone, exige nome bem parecido")
    void sem_telefone_exige_nome_parecido() {
        assertThat(semTelefone("Maria Silva", "Maria Silva Santos", 0.667))
                .contains(GrauDeSemelhanca.POSSIVEL);
        // Mesmo primeiro nome, sobrenome diferente, sem telefone que confirme: são duas pessoas.
        assertThat(semTelefone("Maria Silva", "Maria Souza", 0.412)).isEmpty();
    }

    @Test
    @DisplayName("acento e caixa não mudam a decisão")
    void acento_e_caixa_nao_importam() {
        assertThat(comTelefone("JOSÉ Antônio", "jose antonio", 0.900))
                .contains(GrauDeSemelhanca.PROVAVEL);
    }

    @Test
    @DisplayName("nomes sem relação nenhuma não geram aviso")
    void nomes_sem_relacao() {
        assertThat(semTelefone("Maria Silva", "João Pereira", 0.0)).isEmpty();
    }

    @Test
    @DisplayName("nome vazio não casa com ninguém")
    void nome_vazio() {
        assertThat(semTelefone("", "Maria Silva", 0.9)).isEmpty();
        assertThat(semTelefone(null, "Maria Silva", 0.9)).isEmpty();
    }
}
