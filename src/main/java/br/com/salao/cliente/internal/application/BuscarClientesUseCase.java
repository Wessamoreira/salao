package br.com.salao.cliente.internal.application;

import br.com.salao.cliente.api.ResultadoDaBusca;
import br.com.salao.cliente.internal.domain.TermoDeBusca;
import br.com.salao.cliente.internal.infra.ClientesJdbc;
import br.com.salao.iam.api.Permissao;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CLI-002 / R-UX-03 — encontrar quem está ligando.
 *
 * <h2>Sem paginação, de propósito</h2>
 *
 * <p>Contraria a regra geral do projeto, e o motivo é o uso: isto roda enquanto o telefone toca.
 * Uma busca que devolve a segunda página é uma busca que <strong>falhou</strong> — a resposta útil
 * é refinar o termo, não paginar. O limite existe para proteger o banco de um termo genérico
 * demais, não para navegar.
 *
 * <p>É diferente da listagem de clientes (RT-CLI-006), que precisa de paginação por cursor porque
 * ali o objetivo é percorrer tudo.
 */
public class BuscarClientesUseCase {

    /** Cabe na tela sem rolar. Acima disso, a lista já não ajuda a decidir. */
    public static final int LIMITE_PADRAO = 20;
    public static final int LIMITE_MAXIMO = 50;

    private final ClientesJdbc clientes;

    public BuscarClientesUseCase(ClientesJdbc clientes) {
        this.clientes = clientes;
    }

    @PreAuthorize("hasAuthority('" + Permissao.CLIENTE_LER + "')")
    @Transactional(readOnly = true)
    public ResultadoDaBusca executar(String digitado, Integer limite) {
        // Termo curto devolve lista vazia, não erro: é o estado normal enquanto alguém digita a
        // primeira letra, e um 422 por tecla faria a tela piscar. O motivo vai junto, para a tela
        // dizer o que falta sem reescrever a regra.
        TermoDeBusca termo = TermoDeBusca.de(digitado);
        if (termo instanceof TermoDeBusca.Insuficiente(String motivo)) {
            return ResultadoDaBusca.insuficiente(motivo);
        }
        var interpretacao = termo instanceof TermoDeBusca.PorTelefone
                ? ResultadoDaBusca.Interpretacao.TELEFONE
                : ResultadoDaBusca.Interpretacao.NOME;
        return new ResultadoDaBusca(interpretacao, null,
                clientes.buscar(termo, limiteValido(limite)));
    }

    private int limiteValido(Integer pedido) {
        if (pedido == null || pedido < 1) {
            return LIMITE_PADRAO;
        }
        return Math.min(pedido, LIMITE_MAXIMO);
    }
}
