package br.com.salao.conversacional.internal.application;

/**
 * RT-CNV-003 — a integração falhou, e tentar de novo NÃO vai resolver.
 *
 * <p>Mídia que não existe mais na Meta, URL fora dos hosts permitidos, conteúdo que não confere com o
 * sha256, arquivo acima do teto, formato recusado. Insistir aqui não é resiliência, é custo — e no
 * caso da URL estranha, é repetir uma tentativa de vazamento.
 */
public class FalhaPermanente extends RuntimeException {

    public FalhaPermanente(String mensagem) {
        super(mensagem);
    }
}
