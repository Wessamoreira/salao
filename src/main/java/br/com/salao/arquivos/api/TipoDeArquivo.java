package br.com.salao.arquivos.api;

/**
 * RT-ARQ-001 — os tipos que o salão pode guardar.
 *
 * <p><strong>Lista branca</strong>, e aqui ela é a resposta certa — ao contrário das habilidades
 * (RN-EQP-013), onde a lista branca pura estava errada. A diferença é o que acontece quando a lista
 * está incompleta: lá, um serviço novo ficava invisível e ninguém entendia por quê; aqui, um tipo
 * de arquivo não previsto simplesmente não sobe, e a mensagem diz isso.
 *
 * <p>O custo de errar para o lado permissivo é de outra ordem: um SVG é um documento com script
 * dentro, um HTML servido do mesmo domínio rouba a sessão de quem abrir, e um "PDF" que na verdade
 * é um executável é um problema de quem baixar.
 */
public enum TipoDeArquivo {

    JPEG("image/jpeg", ".jpg"),
    PNG("image/png", ".png"),
    /** iPhone tira foto assim por padrão, e metade das fotos de salão vem de iPhone. */
    HEIC("image/heic", ".heic"),
    WEBP("image/webp", ".webp"),
    PDF("application/pdf", ".pdf");

    private final String contentType;
    private final String extensao;

    TipoDeArquivo(String contentType, String extensao) {
        this.contentType = contentType;
        this.extensao = extensao;
    }

    /**
     * O {@code Content-Type} que o servidor devolve ao ler o arquivo.
     *
     * <p><strong>Sempre o detectado, nunca o que o cliente enviou.</strong> Devolver o do cliente
     * anularia a validação inteira: um arquivo que passou como JPEG mas é servido como
     * {@code text/html} executa no navegador de quem abrir, do domínio do salão.
     */
    public String contentType() {
        return contentType;
    }

    public String extensao() {
        return extensao;
    }
}
