package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.AgendamentoCriado;
import br.com.salao.agenda.api.AgendamentoResumo;
import br.com.salao.agenda.api.BlocoDoAgendamento;
import br.com.salao.agenda.api.ErrosDaAgenda;
import br.com.salao.agenda.api.Janela;
import br.com.salao.agenda.api.StatusAgendamento;
import br.com.salao.agenda.internal.domain.MaterializadorDeBlocos;
import br.com.salao.agenda.internal.domain.MaterializadorDeBlocos.BlocoNoTempo;
import br.com.salao.agenda.internal.infra.AgendaJdbc;
import br.com.salao.agenda.internal.infra.AgendaJdbc.UnidadeOcupada;
import br.com.salao.catalogo.api.Recurso;
import br.com.salao.catalogo.api.RecursoApi;
import br.com.salao.catalogo.api.SequenciaDeBlocos;
import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.catalogo.api.ServicoResumo;
import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.equipe.api.IntervaloDeTrabalho;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final RecursoApi recursos;
    private final ClienteApi clientes;
    private final EstabelecimentoApi estabelecimentos;
    private final AgendaJdbc agenda;
    private final AuditoriaApi auditoria;
    private final ApplicationEventPublisher eventos;
    private final Idempotencia idempotencia;
    private final Relogio relogio;

    public CriarAgendamentoUseCase(ProfissionalApi equipe, ServicoApi catalogo,
                                   RecursoApi recursos, ClienteApi clientes,
                                   EstabelecimentoApi estabelecimentos, AgendaJdbc agenda,
                                   AuditoriaApi auditoria, ApplicationEventPublisher eventos,
                                   Idempotencia idempotencia, Relogio relogio) {
        this.equipe = equipe;
        this.catalogo = catalogo;
        this.recursos = recursos;
        this.clientes = clientes;
        this.estabelecimentos = estabelecimentos;
        this.agenda = agenda;
        this.auditoria = auditoria;
        this.eventos = eventos;
        this.idempotencia = idempotencia;
        this.relogio = relogio;
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

        List<BlocoNoTempo> noTempo =
                MaterializadorDeBlocos.materializar(receita, pedido.inicio());
        Janela periodo = MaterializadorDeBlocos.periodoTotal(noTempo);

        validarNaoRetroativo(pedido, periodo);
        ZoneId fuso = estabelecimentos.fusoDe(tenant);
        validarJornada(pedido, noTempo, fuso);

        List<BlocoDoAgendamento> blocos = alocar(pedido, noTempo, periodo);

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

    /** RN-AGD-008 — a recepção lança o que ficou no papel, mas dizendo que é isso. */
    private void validarNaoRetroativo(NovoAgendamento pedido, Janela periodo) {
        if (!periodo.inicio().isBefore(relogio.agora())) {
            return;
        }
        if (!pedido.forcarPassado()) {
            throw new ErroDeDominio(ErrosDaAgenda.DATA_PASSADA,
                    "o horário " + periodo.inicio() + " já passou");
        }
        exigirJustificativa(pedido);
    }

    /**
     * RN-AGD-002 — todo bloco que ocupa o profissional dentro da jornada, menos as exceções.
     *
     * <p>Só os blocos ATIVO. A pausa pode cair no almoço: a cliente fica com a química agindo e a
     * profissional almoça — que é exatamente o que acontece no salão.
     */
    private void validarJornada(NovoAgendamento pedido, List<BlocoNoTempo> blocos, ZoneId fuso) {
        if (pedido.forcarForaJornada()) {
            exigirJustificativa(pedido);
            return;
        }
        var livres = new ArrayList<Janela>();
        // Dois dias: um serviço pode atravessar a meia-noite, e a jornada é por dia da semana.
        for (LocalDate data : diasTocados(blocos, fuso)) {
            for (IntervaloDeTrabalho intervalo
                    : equipe.intervalosEm(List.of(pedido.profissionalId()), data)
                            .getOrDefault(pedido.profissionalId(), List.of())) {
                livres.add(new Janela(data.atTime(intervalo.inicio()).atZone(fuso).toInstant(),
                        data.atTime(intervalo.fim()).atZone(fuso).toInstant()));
            }
        }
        for (BlocoNoTempo bloco : blocos) {
            if (bloco.profissionalOcupado()
                    && livres.stream().noneMatch(bloco.janela()::cabeEm)) {
                throw new ErroDeDominio(ErrosDaAgenda.FORA_DA_JORNADA,
                        "o trecho de " + bloco.janela().inicio() + " a " + bloco.janela().fim()
                                + " está fora da jornada");
            }
        }
    }

    private Set<LocalDate> diasTocados(List<BlocoNoTempo> blocos, ZoneId fuso) {
        var dias = new LinkedHashSet<LocalDate>();
        for (BlocoNoTempo bloco : blocos) {
            dias.add(bloco.janela().inicio().atZone(fuso).toLocalDate());
            dias.add(bloco.janela().fim().minusNanos(1).atZone(fuso).toLocalDate());
        }
        return dias;
    }

    private void exigirJustificativa(NovoAgendamento pedido) {
        if (pedido.justificativa() == null || pedido.justificativa().isBlank()) {
            throw new ErroDeDominio(ErrosDaAgenda.FORA_DA_JORNADA,
                    "forçar exige justificativa, que vai para a trilha");
        }
    }

    /**
     * Transforma cada bloco em linha, alocando a unidade de cada recurso exigido.
     *
     * <p>Bloco com mais de um recurso vira mais de uma linha com a mesma {@code ordem}: a primeira
     * carrega o profissional, as demais só o recurso. É o caso raro — uma cliente usa uma cadeira e
     * um lavatório, não dois de cada — e agrupar por {@code ordem} devolve a linha do tempo.
     */
    private List<BlocoDoAgendamento> alocar(NovoAgendamento pedido, List<BlocoNoTempo> noTempo,
                                            Janela periodo) {
        Set<UUID> exigidos = new LinkedHashSet<>();
        noTempo.forEach(bloco -> exigidos.addAll(bloco.recursosExigidos()));

        Map<UUID, List<UnidadeOcupada>> ocupadas = agenda.unidadesOcupadas(exigidos, periodo);
        Map<UUID, Integer> quantidades = new HashMap<>();
        for (Recurso recurso : recursos.ativos()) {
            quantidades.put(recurso.id(), recurso.quantidade());
        }

        var deste = new HashMap<UUID, List<UnidadeOcupada>>();
        var linhas = new ArrayList<BlocoDoAgendamento>();
        for (BlocoNoTempo bloco : noTempo) {
            UUID profissional = bloco.profissionalOcupado() ? pedido.profissionalId() : null;
            boolean primeira = true;

            for (UUID recursoId : bloco.recursosExigidos()) {
                Integer quantidade = quantidades.get(recursoId);
                if (quantidade == null) {
                    throw new ErroDeDominio(ErrosDaAgenda.RECURSO_OCUPADO,
                            "o recurso " + recursoId + " não está disponível");
                }
                int unidade = AlocadorDeUnidades.primeiraLivre(recursoId, quantidade,
                                bloco.janela(), ocupadas,
                                deste.getOrDefault(recursoId, List.of()))
                        .orElseThrow(() -> new ErroDeDominio(ErrosDaAgenda.RECURSO_OCUPADO,
                                "nenhuma unidade livre do recurso " + recursoId
                                        + " entre " + bloco.janela().inicio()
                                        + " e " + bloco.janela().fim()));
                deste.computeIfAbsent(recursoId, id -> new ArrayList<>())
                        .add(new UnidadeOcupada(unidade, bloco.janela()));
                linhas.add(new BlocoDoAgendamento(bloco.ordem(), bloco.tipo(), bloco.janela(),
                        primeira ? profissional : null, recursoId, unidade));
                primeira = false;
            }
            if (primeira) {
                // Nenhum recurso: a linha do bloco existe do mesmo jeito, porque é ela que guarda
                // a linha do tempo — inclusive a da pausa, que não ocupa ninguém.
                linhas.add(new BlocoDoAgendamento(bloco.ordem(), bloco.tipo(), bloco.janela(),
                        profissional, null, null));
            }
        }
        return linhas;
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
