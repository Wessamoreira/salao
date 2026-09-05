package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.AgendamentoResumo;
import br.com.salao.agenda.api.ErrosDaAgenda;
import br.com.salao.agenda.api.Frequencia;
import br.com.salao.agenda.api.OrigemDoAgendamento;
import br.com.salao.agenda.api.Recorrencia;
import br.com.salao.agenda.api.SerieCriada;
import br.com.salao.agenda.internal.infra.AgendaJdbc;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.TenantContext;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * RT-AGD-009 — "a Marilda vem toda quinta às 14h".
 *
 * <h2>Cada ocorrência é um compromisso independente</h2>
 *
 * <p>É a decisão que separa esta rotina de RT-AGD-008, e as duas parecem a mesma pergunta.
 *
 * <p>Lá, tudo ou nada: o empurrão é <strong>um ato só</strong> — o dia da profissional andou —, e
 * uma agenda empurrada pela metade é pior do que uma não empurrada.
 *
 * <p>Aqui, o oposto. A terceira quinta é feriado, ou o horário já está tomado, e recusar as outras
 * onze por causa dela seria absurdo. <strong>Marca o que cabe e devolve o que não coube</strong> —
 * informação que a recepção resolve uma a uma, não um erro que apaga o trabalho inteiro.
 *
 * <p>O critério que decide entre os dois: se as partes são <em>consequências de um mesmo fato</em>,
 * é tudo ou nada; se são <em>compromissos separados que por acaso foram pedidos juntos</em>, é
 * parcial.
 *
 * <h2>As datas somam semanas no fuso do salão</h2>
 *
 * <p>Somar 168 horas ao instante atravessa o horário de verão e a cliente chega uma hora errada. As
 * ocorrências são calculadas em {@link ZonedDateTime} no fuso do estabelecimento (ADR-0009), o que
 * mantém "quinta às 14h" sendo quinta às 14h dos dois lados da virada.
 */
public class CriarRecorrenciaUseCase {

    private static final Logger log = LoggerFactory.getLogger(CriarRecorrenciaUseCase.class);

    /** Um ano de quintas. Acima disso é engano, e materializar 500 linhas por engano dói. */
    private static final int MAXIMO_DE_OCORRENCIAS = 52;

    private final CriarAgendamentoUseCase criar;
    private final EstabelecimentoApi estabelecimentos;
    private final AgendaJdbc agenda;
    private final RegistroDaSerie registro;

    public CriarRecorrenciaUseCase(CriarAgendamentoUseCase criar,
                                   EstabelecimentoApi estabelecimentos, AgendaJdbc agenda,
                                   RegistroDaSerie registro) {
        this.criar = criar;
        this.estabelecimentos = estabelecimentos;
        this.agenda = agenda;
        this.registro = registro;
    }

    /**
     * <strong>Sem {@code @Transactional} de propósito</strong>, e isto é o que faz o "marca o que
     * cabe" funcionar de verdade.
     *
     * <p>Numa transação única, o {@code ErroDeDominio} de uma ocorrência recusada marcaria a
     * transação inteira como <em>rollback-only</em> — e pior: a violação da exclusion constraint
     * aborta a transação no PostgreSQL, e nenhum comando posterior funciona sem savepoint. O
     * resultado seria "não marca nada", com aparência de "marcou onze".
     *
     * <p>Cada ocorrência abre a sua transação, pela anotação que já está em
     * {@code CriarAgendamentoUseCase}. É a tradução técnica exata da decisão de negócio: cada
     * ocorrência é um compromisso independente.
     */
    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_ESCREVER_TODAS + "')")
    public SerieCriada executar(UUID clienteId, UUID profissionalId, UUID servicoId,
                                Instant primeiroInicio, Frequencia frequencia, int quantidade,
                                String observacao) {
        if (quantidade < 2) {
            // Uma ocorrência só não é série: é um agendamento, e RT-AGD-002 já o faz.
            throw new ErroDeDominio(ErrosDaAgenda.RECORRENCIA_INVALIDA,
                    "uma série precisa de ao menos duas ocorrências");
        }
        if (quantidade > MAXIMO_DE_OCORRENCIAS) {
            throw new ErroDeDominio(ErrosDaAgenda.RECORRENCIA_INVALIDA,
                    quantidade + " ocorrências passa de um ano; confira o valor");
        }
        if (frequencia == null || primeiroInicio == null) {
            throw new ErroDeDominio(ErrosDaAgenda.RECORRENCIA_INVALIDA,
                    "frequência e primeiro horário são obrigatórios");
        }

        UUID tenant = TenantContext.obrigatorio();
        ZoneId fuso = estabelecimentos.fusoDe(tenant);

        UUID recorrenciaId = registro.abrir(tenant,
                new Recorrencia(null, clienteId, profissionalId, servicoId, frequencia,
                        primeiroInicio, true));

        var marcados = new ArrayList<UUID>(quantidade);
        var recusados = new ArrayList<Instant>();

        for (Instant quando : ocorrencias(primeiroInicio, frequencia, quantidade, fuso)) {
            try {
                AgendamentoResumo criado = criar.executar(new NovoAgendamento(clienteId,
                        profissionalId, servicoId, quando, OrigemDoAgendamento.RECORRENCIA,
                        observacao, null, false, false, null, null));
                registro.vincular(criado.id(), recorrenciaId);
                marcados.add(criado.id());
            } catch (ErroDeDominio e) {
                // Feriado, horário tomado, profissional de folga. Cada um é um caso para a
                // recepção resolver — não um motivo para desfazer os outros onze.
                recusados.add(quando);
            }
        }

        registro.registrar(recorrenciaId,
                trilha(frequencia, quantidade, marcados.size(), recusados.size()));
        log.info("Série {} criada: {} marcados, {} não couberam", recorrenciaId, marcados.size(),
                recusados.size());

        return new SerieCriada(agenda.recorrenciaPorId(recorrenciaId).orElseThrow(), marcados,
                recusados);
    }

    /**
     * Os instantes da série, somando semanas <strong>no fuso do salão</strong>.
     *
     * <p>Somar {@code Duration.ofDays(7)} ao instante daria 168 horas exatas — e na semana da
     * virada do horário de verão isso desloca o horário em uma hora. "Quinta às 14h" precisa
     * continuar sendo quinta às 14h.
     */
    private List<Instant> ocorrencias(Instant primeiro, Frequencia frequencia, int quantidade,
                                      ZoneId fuso) {
        ZonedDateTime base = primeiro.atZone(fuso);
        var instantes = new ArrayList<Instant>(quantidade);
        for (int i = 0; i < quantidade; i++) {
            instantes.add(base.plusWeeks((long) i * frequencia.semanas()).toInstant());
        }
        return instantes;
    }

    private Map<String, Object> trilha(Frequencia frequencia, int pedidas, int marcadas,
                                       int recusadas) {
        var dados = new LinkedHashMap<String, Object>();
        dados.put("frequencia", frequencia.name());
        dados.put("pedidas", pedidas);
        dados.put("marcadas", marcadas);
        // O número que vale a auditoria: quantas a recepção ainda precisa resolver.
        dados.put("naoCouberam", recusadas);
        return dados;
    }
}
