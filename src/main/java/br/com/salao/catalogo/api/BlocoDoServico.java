package br.com.salao.catalogo.api;

/**
 * RT-CAT-001 — um trecho do serviço, com duração fixa.
 *
 * @param duracaoMinutos sempre maior que zero; bloco de duração nula não é etapa, é ruído
 */
public record BlocoDoServico(TipoDeBloco tipo, int duracaoMinutos) {

    public BlocoDoServico {
        if (tipo == null) {
            throw new IllegalArgumentException("o tipo do bloco é obrigatório");
        }
        if (duracaoMinutos <= 0) {
            throw new IllegalArgumentException("duração precisa ser positiva: " + duracaoMinutos);
        }
    }

    public static BlocoDoServico ativo(int minutos) {
        return new BlocoDoServico(TipoDeBloco.ATIVO, minutos);
    }

    public static BlocoDoServico pausa(int minutos) {
        return new BlocoDoServico(TipoDeBloco.PAUSA, minutos);
    }
}
