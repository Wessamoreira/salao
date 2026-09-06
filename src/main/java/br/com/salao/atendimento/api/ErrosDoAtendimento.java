package br.com.salao.atendimento.api;

import br.com.salao.shared.erro.CodigoDeErro;
import org.springframework.http.HttpStatus;

/**
 * RT-ATD-001 — catálogo de erros do módulo. Ver docs/modulos/atendimento/regras.md.
 *
 * <h2>Por que um enum por módulo, e não um por caso de uso</h2>
 *
 * <p>O módulo nasceu com um enum dentro de cada caso de uso, seguindo {@code arquivos} — que tinha
 * um caso de uso só. Com seis, {@code ER-ATD-COMANDA_NAO_ESTA_ABERTA} estava declarado
 * <strong>cinco vezes</strong> e {@code ER-ATD-ITEM_NAO_ENCONTRADO} três.
 *
 * <p>Não é redundância inofensiva: mudar o status HTTP ou o título em uma das cópias faria a mesma
 * falha responder diferente conforme a rota que o usuário tocou, e nada acusaria. É a mesma razão
 * de {@code catalogo}, {@code agenda}, {@code equipe} e {@code estoque} terem um enum por módulo.
 */
public enum ErrosDoAtendimento implements CodigoDeErro {

    // ------------------------------------------------------------------ comanda

    COMANDA_NAO_ENCONTRADA("ER-ATD-COMANDA_NAO_ENCONTRADA", HttpStatus.NOT_FOUND,
            "Comanda não encontrada"),

    /**
     * A comanda está fechada ou estornada.
     *
     * <p>É o erro mais compartilhado do módulo — lançar item, remover, descontar e mexer em
     * executante param todos aqui —, e é por isso que ele precisa de uma declaração só.
     */
    COMANDA_NAO_ESTA_ABERTA("ER-ATD-COMANDA_NAO_ESTA_ABERTA", HttpStatus.CONFLICT,
            "Esta comanda não está aberta"),

    CLIENTE_NAO_ENCONTRADO("ER-ATD-CLIENTE_NAO_ENCONTRADO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Cliente não encontrado"),

    AGENDAMENTO_NAO_ENCONTRADO("ER-ATD-AGENDAMENTO_NAO_ENCONTRADO", HttpStatus.NOT_FOUND,
            "Agendamento não encontrado"),

    AGENDAMENTO_CANCELADO("ER-ATD-AGENDAMENTO_CANCELADO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Este agendamento foi cancelado"),

    AGENDAMENTO_DE_OUTRA_CLIENTE("ER-ATD-AGENDAMENTO_DE_OUTRA_CLIENTE",
            HttpStatus.UNPROCESSABLE_ENTITY, "Este horário é de outra cliente"),

    // ------------------------------------------------------------------ item

    ITEM_NAO_ENCONTRADO("ER-ATD-ITEM_NAO_ENCONTRADO", HttpStatus.NOT_FOUND,
            "Item não encontrado"),

    ITEM_NAO_E_SERVICO("ER-ATD-ITEM_NAO_E_SERVICO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Produto não tem executante"),

    QUANTIDADE_INVALIDA("ER-ATD-QUANTIDADE_INVALIDA", HttpStatus.UNPROCESSABLE_ENTITY,
            "A quantidade precisa ser maior que zero"),

    SERVICO_NAO_ENCONTRADO("ER-ATD-SERVICO_NAO_ENCONTRADO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Serviço não encontrado"),

    SERVICO_FORA_DO_CARDAPIO("ER-ATD-SERVICO_FORA_DO_CARDAPIO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Este serviço não está mais no cardápio"),

    /** Falha de cadastro, não brinde: zero é um preço (RN-ATD-005). */
    SERVICO_SEM_PRECO("ER-ATD-SERVICO_SEM_PRECO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Este serviço não tem preço definido"),

    PRODUTO_NAO_ENCONTRADO("ER-ATD-PRODUTO_NAO_ENCONTRADO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Produto não encontrado"),

    /**
     * RT-ATD-003 — o produto existe e não está à venda.
     *
     * <p>Código próprio, separado de "sem preço", porque a correção é outra: aqui o produto é de
     * consumo interno ou foi desativado, e digitar um preço não resolve.
     */
    PRODUTO_NAO_E_VENDAVEL("ER-ATD-PRODUTO_NAO_E_VENDAVEL", HttpStatus.UNPROCESSABLE_ENTITY,
            "Este produto não está à venda"),

    PRODUTO_SEM_PRECO("ER-ATD-PRODUTO_SEM_PRECO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Este produto não tem preço de venda"),

    // ------------------------------------------------------------------ executante

    EXECUTANTE_NAO_ENCONTRADO("ER-ATD-EXECUTANTE_NAO_ENCONTRADO", HttpStatus.NOT_FOUND,
            "Executante não encontrado"),

    PROFISSIONAL_NAO_ENCONTRADO("ER-ATD-PROFISSIONAL_NAO_ENCONTRADO",
            HttpStatus.UNPROCESSABLE_ENTITY, "Profissional não encontrado ou inativo"),

    NAO_EXECUTA_O_SERVICO("ER-ATD-NAO_EXECUTA_O_SERVICO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Este profissional não executa este serviço"),

    JA_ESTA_NO_ITEM("ER-ATD-JA_ESTA_NO_ITEM", HttpStatus.CONFLICT,
            "Esta pessoa já está neste item"),

    PERCENTUAL_INVALIDO("ER-ATD-PERCENTUAL_INVALIDO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Percentual inválido"),

    COMISSAO_PASSA_DE_CEM("ER-ATD-COMISSAO_PASSA_DE_CEM", HttpStatus.UNPROCESSABLE_ENTITY,
            "As comissões do item passariam de 100%"),

    PRINCIPAL_NAO_SAI("ER-ATD-PRINCIPAL_NAO_SAI", HttpStatus.UNPROCESSABLE_ENTITY,
            "O executante principal não pode ser removido"),

    // ------------------------------------------------------------------ pagamento

    /**
     * RT-FIN-001 — a soma dos pagamentos passaria do que a comanda deve.
     *
     * <p>Troco não é pagamento: quem entrega cem numa conta de oitenta pagou oitenta. Registrar
     * cem faria o faturamento do dia crescer sozinho, e o caixa nunca fecharia.
     */
    PAGAMENTO_PASSA_DO_TOTAL("ER-ATD-PAGAMENTO_PASSA_DO_TOTAL", HttpStatus.UNPROCESSABLE_ENTITY,
            "Este pagamento passa do valor da comanda"),

    PAGAMENTO_NAO_E_DESTA_COMANDA("ER-ATD-PAGAMENTO_NAO_E_DESTA_COMANDA", HttpStatus.NOT_FOUND,
            "Pagamento não encontrado nesta comanda"),

    /**
     * RT-ATD-006 — ainda falta receber.
     *
     * <p>A mensagem diz <strong>quanto</strong> falta. Fechar com saldo aberto faria a comanda
     * afirmar que o atendimento foi pago quando não foi — e o faturamento do dia passaria a
     * incluir dinheiro que ninguém recebeu. Quem quer registrar que não vai receber agora tem
     * {@code FIADO}; quem vai dar de graça tem {@code CORTESIA}. As duas quitam.
     */
    COMANDA_COM_SALDO_ABERTO("ER-ATD-COMANDA_COM_SALDO_ABERTO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Ainda falta receber nesta comanda"),

    /**
     * RT-ATD-007 — pagaram mais do que a comanda vale.
     *
     * <p>Só acontece depois de reabrir e tirar item: enquanto aberta, nenhum pagamento passa do que
     * falta (RN-FIN-003). Fechar assim registraria um faturamento maior do que o atendimento — e o
     * troco devido ficaria sem rastro nenhum.
     */
    COMANDA_PAGA_A_MAIS("ER-ATD-COMANDA_PAGA_A_MAIS", HttpStatus.UNPROCESSABLE_ENTITY,
            "Há mais pago do que o valor da comanda"),

    /** RT-ATD-007 — reabrir e estornar só valem para comanda fechada. */
    COMANDA_NAO_ESTA_FECHADA("ER-ATD-COMANDA_NAO_ESTA_FECHADA", HttpStatus.CONFLICT,
            "Esta comanda não está fechada"),

    /**
     * RT-ATD-007 — mexer em dinheiro fechado sem dizer por quê.
     *
     * <p>O motivo é o que separa a correção legítima do desfazimento silencioso. Sem ele, a
     * trilha registra quem e quando, e a pergunta que o dono faz — <em>por que esta comanda de
     * trezentos reais foi estornada?</em> — fica sem resposta.
     */
    MOTIVO_OBRIGATORIO("ER-ATD-MOTIVO_OBRIGATORIO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Informe o motivo"),

    // ------------------------------------------------------------------ desconto

    DESCONTO_INVALIDO("ER-ATD-DESCONTO_INVALIDO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Desconto inválido"),

    /** 403 e não 422: o valor pode estar certo — quem pediu é que não pode tanto (RN-ATD-012). */
    DESCONTO_ACIMA_DO_LIMITE("ER-ATD-DESCONTO_ACIMA_DO_LIMITE", HttpStatus.FORBIDDEN,
            "Desconto acima do seu limite");

    private final String codigo;
    private final HttpStatus status;
    private final String titulo;

    ErrosDoAtendimento(String codigo, HttpStatus status, String titulo) {
        this.codigo = codigo;
        this.status = status;
        this.titulo = titulo;
    }

    @Override public String codigo() { return codigo; }
    @Override public HttpStatus status() { return status; }
    @Override public String titulo() { return titulo; }
}
