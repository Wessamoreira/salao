package br.com.salao.equipe.api;

import br.com.salao.shared.erro.CodigoDeErro;
import org.springframework.http.HttpStatus;

/** RT-EQP-001 — catálogo de erros do módulo. Ver docs/modulos/equipe/regras.md. */
public enum ErrosDaEquipe implements CodigoDeErro {

    DADOS_INVALIDOS("ER-EQP-DADOS_INVALIDOS", HttpStatus.UNPROCESSABLE_ENTITY,
            "Dados do profissional inválidos"),

    DOCUMENTO_INVALIDO("ER-EQP-DOCUMENTO_INVALIDO", HttpStatus.UNPROCESSABLE_ENTITY,
            "CPF ou CNPJ inválido"),

    /**
     * Parceiro precisa de CNPJ; empregado e autônomo, de CPF.
     *
     * <p>Código próprio porque a correção é específica e o texto genérico não ajudaria: ou o
     * documento está errado, ou o vínculo está.
     */
    DOCUMENTO_NAO_COMBINA_COM_VINCULO("ER-EQP-DOCUMENTO_NAO_COMBINA_COM_VINCULO",
            HttpStatus.UNPROCESSABLE_ENTITY, "Documento não corresponde ao tipo de vínculo"),

    DOCUMENTO_JA_CADASTRADO("ER-EQP-DOCUMENTO_JA_CADASTRADO", HttpStatus.CONFLICT,
            "Já existe um profissional com este documento"),

    PROFISSIONAL_NAO_ENCONTRADO("ER-EQP-PROFISSIONAL_NAO_ENCONTRADO", HttpStatus.NOT_FOUND,
            "Profissional não encontrado"),

    /**
     * Dois intervalos do mesmo dia se cruzam.
     *
     * <p>O banco também impede, por {@code EXCLUDE}. Este código existe para a mensagem dizer
     * <em>quais</em> horários colidem — um erro de constraint diria apenas que algo colidiu, e a
     * tela não teria o que destacar.
     */
    JORNADA_SOBREPOSTA("ER-EQP-JORNADA_SOBREPOSTA", HttpStatus.UNPROCESSABLE_ENTITY,
            "Horários se sobrepõem"),

    BLOQUEIO_NAO_ENCONTRADO("ER-EQP-BLOQUEIO_NAO_ENCONTRADO", HttpStatus.NOT_FOUND,
            "Bloqueio não encontrado"),

    /**
     * RT-EQP-004 — restringir a habilidade para nenhum serviço.
     *
     * <p>Deixaria o profissional sem poder atender nada, e sem que isso estivesse dito em lugar
     * nenhum: a agenda simplesmente pararia de oferecer horário dele. Quem quer tirar a pessoa da
     * agenda desativa o profissional.
     */
    HABILIDADES_VAZIAS("ER-EQP-HABILIDADES_VAZIAS", HttpStatus.UNPROCESSABLE_ENTITY,
            "Restrição sem nenhum serviço"),

    /**
     * Habilidade declarada para um serviço que não existe neste salão.
     *
     * <p>Não há chave estrangeira para {@code servico} — ela costuraria dois módulos. Esta
     * checagem é a única barreira, e é também a de tenant: ver RN-CAT-011, que mostra que nem a FK
     * separaria os salões.
     */
    SERVICO_NAO_ENCONTRADO("ER-EQP-SERVICO_NAO_ENCONTRADO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Serviço não encontrado");

    private final String codigo;
    private final HttpStatus status;
    private final String titulo;

    ErrosDaEquipe(String codigo, HttpStatus status, String titulo) {
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
