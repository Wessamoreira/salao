package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.AgendamentoCriado;
import br.com.salao.agenda.api.AgendamentoResumo;
import br.com.salao.agenda.api.BlocoDoAgendamento;
import br.com.salao.agenda.api.ErrosDaAgenda;
import br.com.salao.agenda.api.Janela;
import br.com.salao.agenda.api.StatusAgendamento;
import br.com.salao.agenda.internal.infra.AgendaJdbc;
import br.com.salao.catalogo.api.RecursoApi;
import br.com.salao.catalogo.api.SequenciaDeBlocos;
import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.catalogo.api.ServicoResumo;
import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.idempotencia.ChaveDeIdempotencia;
import br.com.salao.shared.idempotencia.Idempotencia;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-AGD-002 — criar agendamento.
 *
 * <p>É a operação mais executada do sistema, quase sempre com a cliente ao telefone. Se for lenta
 * ou trabalhosa, a recepção volta para o caderno e o projeto inteiro falha.
 *
 * <h2>O banco é o árbitro do conflito</h2>
 *
 * <p>A validação de sobreposição feita aqui existe para dar <strong>erro bonito antes</strong>, e
 * não para evitar a corrida: entre a checagem e o insert cabe outra requisição. Quem decide é a
 * exclusion constraint, e o erro dela é traduzido pelo nome. Sem lock de aplicação — não sobrevive
 * a duas instâncias e dá falsa segurança.
 *
 * <h2>Sobre {@code agenda:write:own}</h2>
 *
 * <p>A permissão exigida é {@code agenda:write:all}, e só ela. O escopo {@code :own} exigiria saber
 * <em>qual profissional</em> o usuário é, e esse vínculo não existe: {@code usuario} não tem
 * {@code profissional_id}. Aceitar {@code :own} aqui seria tratá-lo como {@code :all} — um
 * profissional marcando na agenda de qualquer colega. Falhar fechado é a única saída honesta
 * enquanto o vínculo não existir; está registrado como pendência bloqueante.
 */
public class CriarAgendamentoUseCase {

    private static final Logger log = LoggerFactory.getLogger(CriarAgendamentoUseCase.class);

    private final ProfissionalApi equipe;
    private final ServicoApi catalogo;
    private final ClienteApi clientes;
    private final EstabelecimentoApi estabelecimentos;
    private final AgendaJdbc agenda;
    private final AuditoriaApi auditoria;
    private final ApplicationEventPublisher eventos;
    private final Idempotencia idempotencia;
    private final ReservaDeHorario reserva;

    public CriarAgendamentoUseCase(ProfissionalApi equipe, ServicoApi catalogo,
                                   RecursoApi recursos, ClienteApi clientes,
                                   EstabelecimentoApi estabelecimentos, AgendaJdbc agenda,
                                   AuditoriaApi auditoria, ApplicationEventPublisher eventos,
                                   Idempotencia idempotencia, Relogio relogio) {
        this.reserva = new ReservaDeHorario(equipe, recursos, agenda, relogio);
        this.equipe = equipe;
        this.catalogo = catalogo;
        this.clientes = clientes;
        this.estabelecimentos = estabelecimentos;
        this.agenda = agenda;
        this.auditoria = auditoria;
        this.eventos = eventos;
        this.idempotencia = idempotencia;
    }

    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_ESCREVER_TODAS + "')")
    @Transactional
    public AgendamentoResumo executar(NovoAgendamento pedido) {
        if (pedido.chaveIdempotencia() == null) {
            return criar(pedido);
        }
        // Dentro da transação de propósito: o registro de idempotência precisa commitar junto com
        // o agendamento. Fora dela existe a janela em que o agendamento foi criado e o registro
        // não — e a repetição cria o segundo, que é exatamente o que a chave existia para impedir.
        return idempotencia.executar(
                new ChaveDeIdempotencia("criar-agendamento", pedido.chaveIdempotencia()),
                pedido, AgendamentoResumo.class, () -> criar(pedido)).valor();
    }

    private AgendamentoResumo criar(NovoAgendamento pedido) {
        UUID tenant = TenantContext.obrigatorio();
        ServicoResumo servico = servicoAtivo(pedido.servicoId());

        if (!clientes.existe(pedido.clienteId())) {
            // `existe` e não `porId`: agendar valida o vínculo sem carregar dado pessoal que não
            // vai usar, e o que não é carregado não vaza em log.
            throw new ErroDeDominio(ErrosDaAgenda.CLIENTE_NAO_ENCONTRADO,
                    "cliente " + pedido.clienteId() + " não encontrado");
        }

        // RN-AGD-001. `receitaDe` responde "executa?" e "em quanto tempo?" de uma vez, e devolve
        // vazio também para profissional inativo — as duas perguntas que precisam ser feitas.
        SequenciaDeBlocos receita = equipe.receitaDe(pedido.profissionalId(), pedido.servicoId())
                .orElseThrow(() -> new ErroDeDominio(ErrosDaAgenda.PROFISSIONAL_NAO_EXECUTA,
                        "o profissional " + pedido.profissionalId()
                                + " não executa " + servico.nome()));
        receita = comDuracaoPedida(receita, pedido.duracaoMinutos());

        var montada = this.reserva.montar(pedido.profissionalId(), receita, pedido.inicio(),
                estabelecimentos.fusoDe(tenant),
                new ReservaDeHorario.Forcamentos(pedido.forcarForaJornada(),
                        pedido.forcarPassado(), pedido.justificativa()));
        Janela periodo = montada.periodo();
        List<BlocoDoAgendamento> blocos = montada.blocos();

        StatusAgendamento status = StatusAgendamento.inicialPara(pedido.origem());
        UUID id;
        try {
            id = agenda.inserirCabeca(tenant, pedido.clienteId(), pedido.profissionalId(),
                    pedido.servicoId(), status, periodo, pedido.origem(), pedido.observacao(),
                    UsuarioAtual.idOuNulo());
            agenda.inserirBlocos(tenant, id, blocos, status);
        } catch (DataIntegrityViolationException e) {
            throw traduzir(e);
        }

        auditoria.registrar(RegistroDeAuditoria.criacao("agendamento", id, trilha(pedido, periodo,
                receita)));
        eventos.publishEvent(new AgendamentoCriado(tenant, id, pedido.clienteId(),
                pedido.profissionalId(), pedido.servicoId(), periodo.inicio(), periodo.fim()));
        log.info("Agendamento criado: {} ({} min, {} do profissional)", id,
                receita.duracaoTotal(), receita.minutosDoProfissional());

        return agenda.porId(id).orElseThrow();
    }

    private ServicoResumo servicoAtivo(UUID servicoId) {
        ServicoResumo servico = catalogo.porId(servicoId).orElseThrow(() -> new ErroDeDominio(
                ErrosDaAgenda.SERVICO_NAO_ENCONTRADO, "serviço " + servicoId + " não encontrado"));
        if (!servico.ativo()) {
            throw new ErroDeDominio(ErrosDaAgenda.SERVICO_INATIVO,
                    servico.nome() + " saiu do cardápio");
        }
        return servico;
    }

    /** A3 — duração diferente do padrão, distribuída entre os blocos ativos (RN-CAT-013). */
    private SequenciaDeBlocos comDuracaoPedida(SequenciaDeBlocos receita, Integer minutos) {
        if (minutos == null) {
            return receita;
        }
        try {
            return receita.comDuracaoTotal(minutos);
        } catch (IllegalArgumentException e) {
            throw new ErroDeDominio(ErrosDaAgenda.SERVICO_INATIVO, e.getMessage());
        }
    }

    /**
     * Traduz a recusa do banco pelo <strong>nome da constraint</strong>.
     *
     * <p>É a corrida perdida: entre a validação e o insert, outra requisição tomou o horário. O
     * front trata 409 como estado normal — recarrega a faixa e mostra o que sobrou.
     */
    private ErroDeDominio traduzir(DataIntegrityViolationException e) {
        String mensagem = e.getMostSpecificCause().getMessage();
        if (mensagem != null && mensagem.contains("bloco_sem_sobreposicao_recurso")) {
            return new ErroDeDominio(ErrosDaAgenda.RECURSO_OCUPADO,
                    "o recurso foi tomado enquanto este agendamento era gravado");
        }
        return new ErroDeDominio(ErrosDaAgenda.CONFLITO_HORARIO,
                "o horário foi tomado enquanto este agendamento era gravado");
    }

    private Map<String, Object> trilha(NovoAgendamento pedido, Janela periodo,
                                       SequenciaDeBlocos receita) {
        var dados = new java.util.LinkedHashMap<String, Object>();
        dados.put("inicio", periodo.inicio().toString());
        dados.put("fim", periodo.fim().toString());
        dados.put("origem", pedido.origem().name());
        dados.put("minutosDoProfissional", receita.minutosDoProfissional());
        if (pedido.duracaoMinutos() != null) {
            dados.put("duracaoForcada", pedido.duracaoMinutos());
        }
        // Forçar sem deixar rastro é o mesmo que não ter regra.
        if (pedido.forcarForaJornada()) {
            dados.put("forcouForaJornada", true);
            dados.put("justificativa", pedido.justificativa());
        }
        if (pedido.forcarPassado()) {
            dados.put("forcouPassado", true);
            dados.put("justificativa", pedido.justificativa());
        }
        return dados;
    }
}
