package br.com.salao.cliente.api;

import java.util.List;

/**
 * RT-CLI-002 / RT-CLI-006 — o resultado da busca <strong>e o que o servidor entendeu do termo</strong>.
 *
 * <h2>Por que a interpretação viaja junto</h2>
 *
 * <p>A tela precisa dizer à recepção o que está fazendo: buscar pelos quatro últimos dígitos é
 * diferente de buscar por nome, e ver isso escrito é o que ensina a regra sem manual.
 *
 * <p>A alternativa seria o front reimplementar "dígito significa telefone" (RN-CLI-009) em
 * TypeScript. Seria a regra de domínio escrita duas vezes — e a segunda cópia divergiria
 * silenciosamente, mostrando na tela algo diferente do que a consulta fez. Aqui o rótulo descreve
 * o que <em>aconteceu</em>, não um palpite sobre o que vai acontecer.
 *
 * <p>Pelo mesmo motivo o {@code aviso} vem do domínio: "informe ao menos 2 letras" é o texto de
 * {@code TermoDeBusca.Insuficiente}, não uma frase reescrita no front.
 *
 * @param aviso o que falta para a busca valer, ou {@code null} quando o termo serve
 */
public record ResultadoDaBusca(
        Interpretacao interpretacao,
        String aviso,
        List<ClienteResumo> itens) {

    public enum Interpretacao {
        /** Buscou pelo fim do telefone. */
        TELEFONE,
        /** Buscou por parte do nome ou do apelido. */
        NOME,
        /** Não buscou: o termo ainda não diz o suficiente. */
        INSUFICIENTE
    }

    public static ResultadoDaBusca insuficiente(String motivo) {
        return new ResultadoDaBusca(Interpretacao.INSUFICIENTE, motivo, List.of());
    }
}
