package br.com.salao.cliente.api;

import br.com.salao.shared.erro.CodigoDeErro;
import org.springframework.http.HttpStatus;

/** RT-CLI-001 — catálogo de erros do módulo. Ver docs/modulos/cliente/regras.md. */
public enum ErrosDoCliente implements CodigoDeErro {

    NOME_OBRIGATORIO("ER-CLI-NOME_OBRIGATORIO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Nome é obrigatório"),

    /**
     * Um código para toda forma de telefone inválido.
     *
     * <p>O detalhe explica o caso concreto — é erro de digitação de quem está atendendo, não
     * sinal de ataque, e aqui esconder a causa só faria a pessoa tentar de novo às cegas.
     */
    TELEFONE_INVALIDO("ER-CLI-TELEFONE_INVALIDO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Telefone inválido"),

    CLIENTE_NAO_ENCONTRADO("ER-CLI-CLIENTE_NAO_ENCONTRADO", HttpStatus.NOT_FOUND,
            "Cliente não encontrado");

    private final String codigo;
    private final HttpStatus status;
    private final String titulo;

    ErrosDoCliente(String codigo, HttpStatus status, String titulo) {
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
