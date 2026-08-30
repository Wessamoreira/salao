package br.com.salao.shared.rede;

import jakarta.servlet.http.HttpServletRequest;

/**
 * RT-INF-011 — de quem é esta requisição, para efeito de limite de taxa.
 *
 * <h2>O detalhe que decide se o limite serve para alguma coisa</h2>
 *
 * <p>Atrás de um proxy, o IP real chega em {@code X-Forwarded-For} e
 * {@code request.getRemoteAddr()} devolve o IP do proxy — todos os clientes viram um só, e o
 * limite por IP passa a limitar o mundo inteiro junto.
 *
 * <p>A correção óbvia — ler o cabeçalho — é <strong>pior</strong> se feita sem cuidado: o
 * cabeçalho é enviado pelo cliente. Quem quiser burlar o limite manda um
 * {@code X-Forwarded-For} diferente a cada requisição e passa a ter buckets infinitos. O
 * limite deixa de existir e ninguém percebe, porque ele continua "funcionando".
 *
 * <p>Por isso o cabeçalho só é considerado quando {@code app.rede.atras-de-proxy} está ligado —
 * e ligá-lo é uma afirmação sobre a topologia: <em>nada alcança esta aplicação sem passar pelo
 * proxy</em>. Se a porta da aplicação estiver exposta junto, ligar isto abre o buraco que se
 * queria fechar. O padrão é desligado.
 */
public class EnderecoDoCliente {

    private final boolean atrasDeProxy;

    public EnderecoDoCliente(boolean atrasDeProxy) {
        this.atrasDeProxy = atrasDeProxy;
    }

    public String de(HttpServletRequest requisicao) {
        if (!atrasDeProxy) {
            return requisicao.getRemoteAddr();
        }
        String encaminhado = requisicao.getHeader("X-Forwarded-For");
        if (encaminhado == null || encaminhado.isBlank()) {
            return requisicao.getRemoteAddr();
        }
        // O primeiro da lista é o cliente original; os demais são proxies encadeados.
        int virgula = encaminhado.indexOf(',');
        String primeiro = (virgula < 0 ? encaminhado : encaminhado.substring(0, virgula)).trim();
        return primeiro.isEmpty() ? requisicao.getRemoteAddr() : primeiro;
    }
}
