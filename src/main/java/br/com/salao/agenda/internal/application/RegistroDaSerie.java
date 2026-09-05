package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.Recorrencia;
import br.com.salao.agenda.internal.infra.AgendaJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.seguranca.UsuarioAtual;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-AGD-009 — as duas escritas da série que precisam de transação própria.
 *
 * <p>Existe por uma restrição real, não por gosto de camada. {@code CriarRecorrenciaUseCase} não
 * pode ser {@code @Transactional} — cada ocorrência precisa da sua transação, senão a primeira
 * recusa envenena todas as outras. Mas a trilha é {@code MANDATORY} de propósito ("auditoria
 * precisa commitar junto com o fato que descreve"), então ela exige uma transação aberta.
 *
 * <p>Chamar um método {@code @Transactional} da própria classe não resolveria: auto-invocação não
 * passa pelo proxy — a mesma armadilha que RT-CAT-004 transformou em regra de arquitetura. Daí um
 * colaborador: outro bean, outro proxy, transação de verdade.
 *
 * <p>E <strong>bean de verdade</strong>: a primeira versão instanciava esta classe com {@code new}
 * dentro do caso de uso, o que tem exatamente o mesmo efeito de não anotar nada — objeto sem proxy
 * não tem transação. O sintoma foi idêntico ao da auto-invocação, e a causa também.
 */
public class RegistroDaSerie {

    private final AgendaJdbc agenda;
    private final AuditoriaApi auditoria;

    public RegistroDaSerie(AgendaJdbc agenda, AuditoriaApi auditoria) {
        this.agenda = agenda;
        this.auditoria = auditoria;
    }

    /** Grava a regra antes de qualquer ocorrência: é ela que dá identidade à série. */
    @Transactional
    public UUID abrir(UUID estabelecimentoId, Recorrencia recorrencia) {
        return agenda.inserirRecorrencia(estabelecimentoId, recorrencia,
                UsuarioAtual.idOuNulo());
    }

    /** Fecha a série na trilha, com o número que a recepção ainda precisa resolver. */
    @Transactional
    public void registrar(UUID recorrenciaId, Map<String, Object> dados) {
        auditoria.registrar(RegistroDeAuditoria.criacao("recorrencia", recorrenciaId, dados));
    }

    /** Liga a ocorrência à série — uma escrita por ocorrência, cada uma na sua transação. */
    @Transactional
    public void vincular(UUID agendamentoId, UUID recorrenciaId) {
        agenda.vincularARecorrencia(agendamentoId, recorrenciaId);
    }
}
