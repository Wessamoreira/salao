package br.com.salao.catalogo.api;

import br.com.salao.shared.erro.CodigoDeErro;
import org.springframework.http.HttpStatus;

/** RT-CAT-001 — catálogo de erros do módulo. Ver docs/modulos/catalogo/regras.md. */
public enum ErrosDoCatalogo implements CodigoDeErro {

    DADOS_INVALIDOS("ER-CAT-DADOS_INVALIDOS", HttpStatus.UNPROCESSABLE_ENTITY,
            "Dados do serviço inválidos"),

    /**
     * A sequência de blocos não fecha: começa ou termina em pausa, ou repete o mesmo tipo.
     *
     * <p>Código próprio porque a correção é específica e o texto genérico não diria o que fazer.
     */
    SEQUENCIA_INVALIDA("ER-CAT-SEQUENCIA_INVALIDA", HttpStatus.UNPROCESSABLE_ENTITY,
            "Sequência de etapas inválida"),

    NOME_JA_CADASTRADO("ER-CAT-NOME_JA_CADASTRADO", HttpStatus.CONFLICT,
            "Já existe um serviço com este nome"),

    SERVICO_NAO_ENCONTRADO("ER-CAT-SERVICO_NAO_ENCONTRADO", HttpStatus.NOT_FOUND,
            "Serviço não encontrado"),

    /**
     * Tentou cancelar um preço que já entrou em vigor.
     *
     * <p>Apagá-lo faria o serviço voltar ao valor anterior retroativamente, e o relatório do mês
     * passado mudaria sozinho. Corrige-se cadastrando outro preço, que deixa rastro.
     */
    PRECO_JA_VIGENTE("ER-CAT-PRECO_JA_VIGENTE", HttpStatus.UNPROCESSABLE_ENTITY,
            "Este preço já está valendo");

    private final String codigo;
    private final HttpStatus status;
    private final String titulo;

    ErrosDoCatalogo(String codigo, HttpStatus status, String titulo) {
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
