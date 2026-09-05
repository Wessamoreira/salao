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
            "Cancelamento exige motivo"),

    /**
     * RT-AGD-006 — no-show marcado antes de o horário começar.
     *
     * <p>Ninguém falta a um compromisso que ainda não começou. Marcar antes seria registrar uma
     * ausência que não aconteceu — e, como o status é terminal, não haveria como desfazer quando a
     * cliente chegasse na hora certa.
     */
    AINDA_NAO_COMECOU("ER-AGD-AINDA_NAO_COMECOU", HttpStatus.UNPROCESSABLE_ENTITY,
            "O horário ainda não começou"),

    /** RT-AGD-007 — dados do bloqueio inválidos: sem alvo, sem motivo, ou período vazio. */
    BLOQUEIO_INVALIDO("ER-AGD-BLOQUEIO_INVALIDO", HttpStatus.UNPROCESSABLE_ENTITY,
            "Dados do bloqueio inválidos"),

    BLOQUEIO_NAO_ENCONTRADO("ER-AGD-BLOQUEIO_NAO_ENCONTRADO", HttpStatus.NOT_FOUND,
            "Bloqueio não encontrado"),

    /**
     * RT-AGD-008 — o empurrão não coube, e nada foi movido.
     *
     * <p>Tudo ou nada: uma agenda empurrada pela metade é pior do que uma agenda não empurrada,
     * porque a recepção não consegue dizer em que estado ela está — e já ligou para parte das
     * clientes. O erro nomeia o atendimento que barrou, para haver o que consertar.
     */
    EMPURRAO_NAO_COUBE("ER-AGD-EMPURRAO_NAO_COUBE", HttpStatus.CONFLICT,
            "O empurrão não coube; nada foi movido"),

    /** RT-AGD-009 — dados da série inválidos: frequência, quantidade ou primeiro horário. */
    RECORRENCIA_INVALIDA("ER-AGD-RECORRENCIA_INVALIDA", HttpStatus.UNPROCESSABLE_ENTITY,
            "Dados da recorrência inválidos"),

    RECORRENCIA_NAO_ENCONTRADA("ER-AGD-RECORRENCIA_NAO_ENCONTRADA", HttpStatus.NOT_FOUND,
            "Recorrência não encontrada");

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
