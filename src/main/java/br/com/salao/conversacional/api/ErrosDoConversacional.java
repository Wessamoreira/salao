package br.com.salao.conversacional.api;

import br.com.salao.shared.erro.CodigoDeErro;
import org.springframework.http.HttpStatus;

/** RT-CNV-001 — catálogo de erros do módulo. Ver docs/modulos/conversacional/regras.md. */
public enum ErrosDoConversacional implements CodigoDeErro {

    /**
     * O canal do WhatsApp não está cadastrado ou está desligado.
     *
     * <p>Só aparece nas rotas do salão. O webhook <strong>nunca</strong> devolve isto: para a Meta
     * ele responde 200 e conta o descarte, porque um erro ali viraria reenvio infinito de uma
     * mensagem que nunca vai ter dono (RN-CNV-004).
     */
    CANAL_NAO_CONFIGURADO("ER-CNV-CANAL_NAO_CONFIGURADO", HttpStatus.NOT_FOUND,
            "Canal do WhatsApp não configurado"),

    /**
     * O {@code phone_number_id} informado já pertence a outro estabelecimento.
     *
     * <p>Código próprio porque a correção não é tentar de novo: é descobrir qual salão está com o
     * número. Dois salões apontando para o mesmo número faria a mensagem de um cair na caixa do
     * outro — e o roteamento é a única coisa que o webhook tem.
     */
    NUMERO_JA_EM_USO("ER-CNV-NUMERO_JA_EM_USO", HttpStatus.CONFLICT,
            "Este número já está ligado a outro estabelecimento"),

    /**
     * RT-CNV-005 — a pessoa confirmou uma ação que não existe mais no sistema.
     *
     * <p>Só acontece entre versões: a intenção foi proposta por uma ferramenta que a versão seguinte
     * removeu ou transformou em leitura. A confirmação é recusada e registrada — executar "o que tiver
     * o mesmo nome" seria executar outra coisa.
     */
    ACAO_INDISPONIVEL("ER-CNV-ACAO_INDISPONIVEL", HttpStatus.UNPROCESSABLE_ENTITY,
            "Ação indisponível pelo agente"),

    DADOS_INVALIDOS("ER-CNV-DADOS_INVALIDOS", HttpStatus.UNPROCESSABLE_ENTITY,
            "Dados do canal inválidos"),

    /** RT-CNV-007 — período invertido, longo demais, ou cursor que não veio do painel. */
    FILTRO_INVALIDO("ER-CNV-FILTRO_INVALIDO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Filtro da revisão inválido");

    private final String codigo;
    private final HttpStatus status;
    private final String titulo;

    ErrosDoConversacional(String codigo, HttpStatus status, String titulo) {
        this.codigo = codigo;
        this.status = status;
        this.titulo = titulo;
    }

    @Override
    public String codigo() {
        return codigo;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String titulo() {
        return titulo;
    }
}
