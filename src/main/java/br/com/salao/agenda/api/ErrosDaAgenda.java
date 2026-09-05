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
            "Profissional não executa este serviço"),

    /**
     * RN-AGD-004 — o horário foi tomado, inclusive na corrida perdida contra o banco.
     *
     * <p>É o erro que o front trata como estado normal e não como falha: recarrega a faixa e
     * mostra o que sobrou (R-UX-20). Duas clientes na mesma cadeira foi a reclamação nº 1 do
     * caderno.
     */
    CONFLITO_HORARIO("ER-AGD-CONFLITO_HORARIO", HttpStatus.CONFLICT,
            "Horário já ocupado"),

    /** RN-AGD-005 — nenhuma unidade do recurso está livre no período. */
    RECURSO_OCUPADO("ER-AGD-RECURSO_OCUPADO", HttpStatus.CONFLICT,
            "Nenhum recurso livre neste horário"),

    /** RN-AGD-002 — fora da jornada, e sem permissão (ou vontade) de forçar. */
    FORA_DA_JORNADA("ER-AGD-FORA_DA_JORNADA", HttpStatus.UNPROCESSABLE_ENTITY,
            "Horário fora da jornada do profissional"),

    /** RN-AGD-008 — a recepção lança atendimento de ontem; só admin pode. */
    DATA_PASSADA("ER-AGD-DATA_PASSADA", HttpStatus.UNPROCESSABLE_ENTITY,
            "Não se agenda no passado"),

    SERVICO_INATIVO("ER-AGD-SERVICO_INATIVO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Serviço fora do cardápio"),

    CLIENTE_NAO_ENCONTRADO("ER-AGD-CLIENTE_NAO_ENCONTRADO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Cliente não encontrado"),

    AGENDAMENTO_NAO_ENCONTRADO("ER-AGD-AGENDAMENTO_NAO_ENCONTRADO", HttpStatus.NOT_FOUND,
            "Agendamento não encontrado"),

    /**
     * RT-AGD-003 — só se move o que ainda vai acontecer.
     *
     * <p>Um atendimento em curso não se arrasta na grade: a cliente já está na cadeira. Um
     * cancelado ou concluído, muito menos — mover o passado reescreveria o que aconteceu.
     */
    NAO_PODE_REAGENDAR("ER-AGD-NAO_PODE_REAGENDAR", HttpStatus.UNPROCESSABLE_ENTITY,
            "Este agendamento não pode ser movido"),

    /**
     * RN-AGD-007 — a transição de status pedida não existe na máquina de estados.
     *
     * <p>409 e não 422: o pedido está bem formado, o que não bate é o estado atual do recurso. A
     * tela recarrega e mostra em que estado ele realmente está.
     */
    TRANSICAO_INVALIDA("ER-AGD-TRANSICAO_INVALIDA", HttpStatus.CONFLICT,
            "Transição de status inválida"),

    /**
     * RT-AGD-005 — cancelamento sem motivo.
     *
     * <p>Cancelar é a operação que apaga um compromisso da agenda de duas pessoas. Sem o motivo,
     * o histórico da cliente vira uma coluna de "cancelado" sem sentido, e a conversa sobre quem
     * desmarca demais não tem em que se apoiar.
     */
    MOTIVO_OBRIGATORIO("ER-AGD-MOTIVO_OBRIGATORIO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Cancelamento exige motivo");

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
