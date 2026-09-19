package br.com.salao.conversacional.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RT-CNV-008 — o texto do aviso a quem não é da equipe. */
class AvisoDeIaTest {

    @Test
    @DisplayName("diz que é IA, que não atende clientes e que ninguém lê — com o nome do salão")
    void diz_as_tres_coisas() {
        String texto = AvisoDeIa.texto("Studio Marilda", null);

        assertThat(texto)
                .startsWith("Mensagem automática do Studio Marilda:")
                .contains("assistente de inteligência artificial")
                .contains("só para a equipe do salão")
                .contains("não atende clientes")
                .contains("ninguém lê as mensagens");
    }

    @Test
    @DisplayName("sem contato, a linha do contato não existe")
    void sem_contato() {
        assertThat(AvisoDeIa.texto("Studio Marilda", null)).doesNotContain("Para falar");
        assertThat(AvisoDeIa.texto("Studio Marilda", "  ")).doesNotContain("Para falar");
    }

    @Test
    @DisplayName("telefone gravado em dígitos sai formatado — fixo e celular")
    void contato_telefone() {
        assertThat(AvisoDeIa.texto("Studio Marilda", "551134567890"))
                .endsWith("\n\nPara falar com o salão: (11) 3456-7890");
        assertThat(AvisoDeIa.texto("Studio Marilda", "5511987654321"))
                .endsWith("Para falar com o salão: (11) 98765-4321");
    }

    @Test
    @DisplayName("link sai como foi cadastrado")
    void contato_link() {
        assertThat(AvisoDeIa.texto("Studio Marilda", "https://studio.exemplo/agendar"))
                .endsWith("Para falar com o salão: https://studio.exemplo/agendar");
    }
}
