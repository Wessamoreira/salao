package br.com.salao.financeiro.internal.domain;

/**
 * RT-FIN-009 — o que foi feito de cada linha do arquivo.
 *
 * <p>Três desfechos, e o número da linha em todos: quem vai conferir abre o arquivo e vai direto nela.
 */
public sealed interface LinhaDoExtrato {

    int linha();

    record Lida(int linha, TransacaoLida transacao) implements LinhaDoExtrato {
    }

    /** Não é venda a conciliar — Pix, venda negada. Não é erro: não entra, e a conta diz quantas. */
    record Ignorada(int linha, String motivo) implements LinhaDoExtrato {
    }

    /** Parece venda e não deu para ler — ou contradiz o que já foi importado. Alguém precisa olhar. */
    record Recusada(int linha, String motivo) implements LinhaDoExtrato {
    }
}
