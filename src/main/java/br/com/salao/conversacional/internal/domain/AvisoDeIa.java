package br.com.salao.conversacional.internal.domain;

import br.com.salao.shared.telefone.Telefone;

/**
 * RT-CNV-008 — o texto do aviso a quem não é da equipe (RN-CNV-027, RN-CNV-028).
 *
 * <p><strong>Fixo, e sem modelo.</strong> Só entram o nome do salão e o contato que o dono cadastrou:
 * nada do que a pessoa escreveu, nada sobre o número dela. É o mesmo texto para todo desconhecido —
 * e por isso não conta a ninguém se aquele número tem ou teve conta no salão.
 *
 * <p>Diz as três coisas que a pessoa precisa saber para não ficar esperando: é uma IA, ela não atende
 * clientes, e ninguém lê o que chega aqui.
 */
public final class AvisoDeIa {

    private AvisoDeIa() {
    }

    /**
     * @param contatoParaClientes telefone em E.164 ou link {@code https://}, como gravado; nulo sai sem a
     *                            linha do contato
     */
    public static String texto(String nomeDoSalao, String contatoParaClientes) {
        var texto = new StringBuilder("Mensagem automática do ").append(nomeDoSalao)
                .append(": este WhatsApp é atendido por um assistente de inteligência artificial")
                .append(" que trabalha só para a equipe do salão. Ele não atende clientes, e ninguém")
                .append(" lê as mensagens enviadas para cá.");
        if (contatoParaClientes != null && !contatoParaClientes.isBlank()) {
            texto.append("\n\nPara falar com o salão: ").append(exibir(contatoParaClientes));
        }
        return texto.toString();
    }

    /** O link como foi cadastrado; o telefone, formatado — gravado ele é só dígitos. */
    static String exibir(String contato) {
        return contato.startsWith("https://") ? contato : new Telefone(contato).formatado();
    }
}
