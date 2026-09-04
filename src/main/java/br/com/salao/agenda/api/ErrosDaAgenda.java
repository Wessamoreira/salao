package br.com.salao.agenda.api;

import br.com.salao.shared.erro.CodigoDeErro;
import org.springframework.http.HttpStatus;

/** RT-AGD-001 — catálogo de erros do módulo. Ver docs/modulos/agenda/regras.md. */
public enum ErrosDaAgenda implements CodigoDeErro {

    SERVICO_NAO_ENCONTRADO("ER-AGD-SERVICO_NAO_ENCONTRADO", HttpStatus.NOT_FOUND,
            "Serviço não encontrado"),

    /**
     * Consultou disponibilidade para alguém que não executa o serviço.
     *
     * <p>Devolver lista vazia seria pior: a recepção veria "sem horário" e ofereceria outro dia,
     * quando o problema é que aquela pessoa não faz aquilo. RN-AGD-001 com nome.
     */
    PROFISSIONAL_NAO_EXECUTA("ER-AGD-PROFISSIONAL_NAO_EXECUTA", HttpStatus.UNPROCESSABLE_ENTITY,
            "Profissional não executa este serviço");

    private final String codigo;
    private final HttpStatus status;
    private final String titulo;

    ErrosDaAgenda(String codigo, HttpStatus status, String titulo) {
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
