package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.AgendamentoResumo;
import br.com.salao.agenda.api.ErrosDaAgenda;
import br.com.salao.agenda.api.EsperaNaFila;
import br.com.salao.agenda.api.OrigemDoAgendamento;
import br.com.salao.agenda.internal.domain.CandidatasDaVaga;
import br.com.salao.agenda.internal.domain.CandidatasDaVaga.Candidata;
import br.com.salao.agenda.internal.domain.ChamadaDaFila;
import br.com.salao.agenda.internal.domain.ResultadoDaChamada;
import br.com.salao.agenda.internal.infra.FilaDeEsperaJdbc;
import br.com.salao.agenda.internal.infra.VagasAbertasJdbc;
import br.com.salao.agenda.internal.infra.VagasAbertasJdbc.Vaga;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-AGD-010 — a recepção ligou; o que a cliente respondeu.
 *
 * <h2>A chamada precisa caber</h2>
 *
 * <p>As candidatas são recalculadas aqui, com a mesma conta da tela ({@link CalculoDeCandidatas}),
 * e a espera precisa estar entre elas. A tela pode estar velha: alguém no balcão pode ter tomado o
 * horário, ou a espera pode ter saído da fila.
 *
 * <h2>Pular exige motivo (RN-AGD-026)</h2>
 *
 * <p>Havendo candidata antes na ordem ainda sem chamada nesta vaga, o motivo é obrigatório e fica
 * gravado. "Não atendeu" conta como chamada — seguir para a próxima é a ordem funcionando.
 *
 * <h2>Aceitar é marcar (RN-AGD-027)</h2>
 *
 * <p>O agendamento é criado pelo caso de uso de RT-AGD-002, dentro desta transação. Se o horário foi
 * tomado entre a tela e o clique, a exclusion constraint recusa, a exceção sobe, e a chamada não é
 * gravada: não existe "aceitou" sem horário marcado.
 */
public class RegistrarChamadaDaFilaUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegistrarChamadaDaFilaUseCase.class);

    private static final int MOTIVO_MINIMO = 3;
    private static final int MOTIVO_MAXIMO = 300;

    private final VagasAbertasJdbc vagas;
    private final FilaDeEsperaJdbc fila;
    private final CalculoDeCandidatas calculo;
    private final CriarAgendamentoUseCase criar;
    private final EstabelecimentoApi estabelecimentos;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public RegistrarChamadaDaFilaUseCase(VagasAbertasJdbc vagas, FilaDeEsperaJdbc fila,
                                         CalculoDeCandidatas calculo,
                                         CriarAgendamentoUseCase criar,
                                         EstabelecimentoApi estabelecimentos,
                                         AuditoriaApi auditoria, Relogio relogio) {
        this.vagas = vagas;
        this.fila = fila;
        this.calculo = calculo;
        this.criar = criar;
        this.estabelecimentos = estabelecimentos;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    /**
     * @param inicio       o horário escolhido — obrigatório em {@code ACEITOU}, ignorado nos outros
     * @param motivoDoPulo obrigatório quando há alguém antes na ordem ainda não chamada nesta vaga;
     *                     sem pulo, não é gravado
     */
    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_ESCREVER_TODAS + "')")
    @Transactional
    public ChamadaDaFila executar(UUID vagaId, UUID esperaId, ResultadoDaChamada resultado,
                            Instant inicio, String motivoDoPulo) {
        if (resultado == null || esperaId == null) {
            throw new ErroDeDominio(ErrosDaAgenda.ESPERA_INVALIDA,
                    "informe a espera e o resultado da chamada");
        }
        UUID tenant = TenantContext.obrigatorio();
        Instant agora = relogio.agora();

        Vaga vaga = vagas.porId(vagaId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDaAgenda.VAGA_NAO_ENCONTRADA,
                        "vaga " + vagaId + " não encontrada"));
        if (!vaga.fim().isAfter(agora)) {
            throw new ErroDeDominio(ErrosDaAgenda.VAGA_ENCERRADA,
                    "o horário desta vaga já passou");
        }

        List<Candidata> candidatas = calculo.de(List.of(vaga), agora,
                estabelecimentos.fusoDe(tenant)).get(vaga.id());
        Candidata chamada = candidatas.stream()
                .filter(candidata -> candidata.espera().id().equals(esperaId))
                .findFirst()
                .orElseThrow(() -> new ErroDeDominio(ErrosDaAgenda.ESPERA_NAO_CABE,
                        "esta espera não cabe mais nesta vaga; recarregue a lista"));

        String motivo = motivoDoPulo(candidatas, chamada, vagas.esperasJaChamadas(vaga.id()),
                motivoDoPulo);

        UUID agendamentoId = null;
        if (resultado == ResultadoDaChamada.ACEITOU) {
            agendamentoId = marcar(vaga, chamada, inicio).id();
        }

        UUID id = vagas.registrarChamada(tenant, vaga.id(), esperaId, resultado, agendamentoId,
                motivo, agora, UsuarioAtual.idOuNulo());
        auditoria.registrar(RegistroDeAuditoria.criacao("chamada_da_fila", id,
                trilha(vaga, chamada, resultado, agendamentoId, motivo)));
        log.info("Fila: chamada na vaga {} para a espera {}: {}{}", vaga.id(), esperaId, resultado,
                motivo == null ? "" : " (fora da ordem)");

        return vagas.chamadaPorId(id).orElseThrow();
    }

    /**
     * RN-AGD-026. Devolve o motivo a gravar: o informado quando houve pulo, nulo quando não houve.
     *
     * <p>A mensagem de erro diz a <em>posição</em> de quem seria pulada, e não o nome: o detalhe
     * do erro pode acabar num log, e nome de cliente não entra em log.
     */
    private static String motivoDoPulo(List<Candidata> candidatas, Candidata chamada,
                                       Set<UUID> jaChamadas, String informado) {
        Optional<Candidata> pulada = CandidatasDaVaga.puladaAoChamar(candidatas,
                chamada.espera().id(), jaChamadas);
        if (pulada.isEmpty()) {
            return null;
        }
        String limpo = informado == null ? "" : informado.strip();
        if (limpo.length() < MOTIVO_MINIMO || limpo.length() > MOTIVO_MAXIMO) {
            throw new ErroDeDominio(ErrosDaAgenda.PULO_SEM_MOTIVO,
                    "a " + pulada.get().posicao() + "ª da ordem ainda não foi chamada nesta vaga; "
                            + "diga por que pular (de " + MOTIVO_MINIMO + " a " + MOTIVO_MAXIMO
                            + " caracteres)");
        }
        return limpo;
    }

    /** RN-AGD-027 — o horário precisa ser desta candidata nesta vaga, e vira agendamento. */
    private AgendamentoResumo marcar(Vaga vaga, Candidata chamada, Instant inicio) {
        if (inicio == null || !chamada.horarios().contains(inicio)) {
            throw new ErroDeDominio(ErrosDaAgenda.HORARIO_FORA_DA_VAGA,
                    "escolha um dos horários desta vaga para marcar");
        }
        EsperaNaFila espera = chamada.espera();
        // PAINEL: a recepção falou com a cliente, como no balcão (RN-AGD-013).
        AgendamentoResumo marcado = criar.executar(new NovoAgendamento(espera.clienteId(),
                vaga.profissionalId(), espera.servicoId(), inicio, OrigemDoAgendamento.PAINEL,
                null, null, false, false, null, null));
        if (fila.marcarAtendida(espera.id()) == 0) {
            // Saiu da fila entre o cálculo e aqui — outra aba marcou atendida ou desistência.
            throw new ErroDeDominio(ErrosDaAgenda.ESPERA_NAO_CABE,
                    "esta espera saiu da fila enquanto a chamada era registrada");
        }
        return marcado;
    }

    private static Map<String, Object> trilha(Vaga vaga, Candidata chamada,
                                              ResultadoDaChamada resultado, UUID agendamentoId,
                                              String motivo) {
        var dados = new LinkedHashMap<String, Object>();
        dados.put("vagaId", vaga.id().toString());
        dados.put("esperaId", chamada.espera().id().toString());
        dados.put("posicao", chamada.posicao());
        dados.put("resultado", resultado.name());
        if (agendamentoId != null) {
            dados.put("agendamentoId", agendamentoId.toString());
        }
        if (motivo != null) {
            dados.put("motivoDoPulo", motivo);
        }
        return dados;
    }
}
