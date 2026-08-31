package br.com.salao.equipe.internal.application;

import br.com.salao.equipe.api.BloqueioDeJornada;
import br.com.salao.equipe.api.ErrosDaEquipe;
import br.com.salao.equipe.api.MotivoDoBloqueio;
import br.com.salao.equipe.internal.infra.ProfissionaisJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tenant.TenantContext;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EQP-003 — registrar feriado, férias, curso, atestado ou folga.
 *
 * <h2>Sem profissional significa o salão inteiro</h2>
 *
 * <p>Feriado não é exceção de uma pessoa. Modelá-lo por profissional obrigaria a criar uma linha
 * para cada um — e a esquecer de criar para quem fosse contratado depois, fazendo o salão
 * aparecer aberto no Natal para a profissional nova.
 *
 * <h2>Bloqueios podem se sobrepor, e isso é normal</h2>
 *
 * <p>Ao contrário da jornada (RN-EQP-007), aqui a sobreposição é banal e inofensiva: férias que
 * englobam um feriado, atestado dentro de uma folga. Os dois removem disponibilidade e a soma dá o
 * mesmo resultado. Proibir obrigaria a recepção a apagar o feriado para lançar as férias.
 */
public class RegistrarBloqueioUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegistrarBloqueioUseCase.class);

    private final ProfissionaisJdbc profissionais;
    private final AuditoriaApi auditoria;

    public RegistrarBloqueioUseCase(ProfissionaisJdbc profissionais, AuditoriaApi auditoria) {
        this.profissionais = profissionais;
        this.auditoria = auditoria;
    }

    /**
     * @param profissionalId {@code null} para o salão inteiro
     * @param horaInicio     ambas nulas para dia inteiro; com hora, vale para um dia só
     */
    @PreAuthorize("hasAuthority('" + Permissao.USUARIO_GERENCIAR + "')")
    @Transactional
    public BloqueioDeJornada executar(UUID profissionalId, MotivoDoBloqueio motivo,
                                      String observacao, LocalDate dataInicio, LocalDate dataFim,
                                      LocalTime horaInicio, LocalTime horaFim) {
        if (motivo == null || dataInicio == null || dataFim == null) {
            throw new ErroDeDominio(ErrosDaEquipe.DADOS_INVALIDOS,
                    "motivo e período são obrigatórios");
        }
        if (dataFim.isBefore(dataInicio)) {
            throw new ErroDeDominio(ErrosDaEquipe.DADOS_INVALIDOS,
                    "o fim do período é antes do início");
        }
        if ((horaInicio == null) != (horaFim == null)) {
            throw new ErroDeDominio(ErrosDaEquipe.DADOS_INVALIDOS,
                    "informe as duas horas ou nenhuma");
        }
        if (horaInicio != null) {
            if (!horaFim.isAfter(horaInicio)) {
                throw new ErroDeDominio(ErrosDaEquipe.DADOS_INVALIDOS,
                        "a hora final precisa ser depois da inicial");
            }
            if (!dataInicio.equals(dataFim)) {
                // "Das 14h às 18h por uma quinzena" ninguém pede, e aceitar obrigaria toda
                // consulta de disponibilidade a tratar um caso que não existe.
                throw new ErroDeDominio(ErrosDaEquipe.DADOS_INVALIDOS,
                        "bloqueio por hora vale para um dia só");
            }
        }
        if (profissionalId != null && profissionais.porId(profissionalId).isEmpty()) {
            throw new ErroDeDominio(ErrosDaEquipe.PROFISSIONAL_NAO_ENCONTRADO,
                    "profissional " + profissionalId + " não encontrado");
        }

        UUID id = profissionais.inserirBloqueio(TenantContext.obrigatorio(), profissionalId,
                motivo, observacao, dataInicio, dataFim, horaInicio, horaFim,
                UsuarioAtual.idOuNulo());

        // Atestado é dado de saúde: o motivo vai para a trilha, a observação não. "Cirurgia no
        // joelho" digitado no campo livre não pode acabar numa tabela append-only fora do alcance
        // de qualquer expurgo (mesma razão de RN-CLI-007).
        auditoria.registrar(RegistroDeAuditoria.criacao("bloqueio_de_jornada", id,
                Map.of("motivo", motivo.name(),
                        "salaoInteiro", profissionalId == null,
                        "dias", dataInicio.datesUntil(dataFim.plusDays(1)).count())));
        log.info("Bloqueio {} registrado: {} de {} a {}", id, motivo, dataInicio, dataFim);

        return profissionais.bloqueiosEntre(profissionalId, dataInicio, dataFim).stream()
                .filter(bloqueio -> bloqueio.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
