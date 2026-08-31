package br.com.salao.equipe.internal.application;

import br.com.salao.equipe.api.ErrosDaEquipe;
import br.com.salao.equipe.api.IntervaloDeTrabalho;
import br.com.salao.equipe.api.JornadaSemanal;
import br.com.salao.equipe.internal.infra.ProfissionaisJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.TenantContext;
import java.time.DayOfWeek;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EQP-002 — definir a jornada semanal.
 *
 * <h2>A semana inteira, sempre</h2>
 *
 * <p>Recebe a jornada completa e substitui. Aplicar diferenças — este intervalo saiu, aquele
 * entrou — é código que existe para economizar dez escritas por semana e que erra na primeira
 * jornada com dois intervalos no mesmo dia.
 *
 * <p>É a mesma escolha de RT-CLI-007: quem edita manda o formulário inteiro.
 *
 * <h2>A sobreposição é impedida pelo banco</h2>
 *
 * <p>Existe verificação aqui também, e ela não é a garantia — é a mensagem. O {@code EXCLUDE} do
 * Postgres é o que torna a sobreposição impossível, inclusive entre duas instâncias salvando ao
 * mesmo tempo, caso em que qualquer verificação em Java falharia: as duas leem, nenhuma vê
 * conflito, as duas gravam.
 *
 * <p>A verificação em Java existe para dizer <em>quais</em> intervalos conflitam, que é o que a
 * tela precisa mostrar. Um erro de constraint diria apenas que algo colidiu.
 */
public class DefinirJornadaUseCase {

    private static final Logger log = LoggerFactory.getLogger(DefinirJornadaUseCase.class);

    private final ProfissionaisJdbc profissionais;
    private final AuditoriaApi auditoria;

    public DefinirJornadaUseCase(ProfissionaisJdbc profissionais, AuditoriaApi auditoria) {
        this.profissionais = profissionais;
        this.auditoria = auditoria;
    }

    /**
     * Recebe os horários crus e constrói o domínio aqui.
     *
     * <p>Quem chama nunca constrói {@link IntervaloDeTrabalho}: o construtor dele recusa intervalo
     * invertido com {@code IllegalArgumentException}, e essa exceção vinda da borda viraria 500.
     * Traduzir é trabalho desta camada.
     */
    @PreAuthorize("hasAuthority('" + Permissao.USUARIO_GERENCIAR + "')")
    @Transactional
    public JornadaSemanal executar(UUID profissionalId,
                                   Map<DayOfWeek, List<PeriodoBruto>> semana) {
        if (profissionais.porId(profissionalId).isEmpty()) {
            throw new ErroDeDominio(ErrosDaEquipe.PROFISSIONAL_NAO_ENCONTRADO,
                    "profissional " + profissionalId + " não encontrado");
        }
        JornadaSemanal jornada = montar(semana);
        jornada.porDia().forEach(this::exigirSemSobreposicao);

        profissionais.substituirJornada(TenantContext.obrigatorio(), profissionalId, jornada);

        auditoria.registrar(RegistroDeAuditoria.alteracao("JORNADA_DEFINIDA", "profissional",
                profissionalId, null,
                Map.of("dias", jornada.porDia().size(),
                        "minutosNaSemana", jornada.minutosNaSemana())));
        log.info("Jornada de {} definida: {} min/semana", profissionalId,
                jornada.minutosNaSemana());

        return profissionais.jornadaDe(profissionalId);
    }

    private JornadaSemanal montar(Map<DayOfWeek, List<PeriodoBruto>> semana) {
        var porDia = new EnumMap<DayOfWeek, List<IntervaloDeTrabalho>>(DayOfWeek.class);
        semana.forEach((dia, periodos) -> porDia.put(dia, periodos.stream().map(periodo -> {
            try {
                return new IntervaloDeTrabalho(periodo.inicio(), periodo.fim());
            } catch (IllegalArgumentException e) {
                throw new ErroDeDominio(ErrosDaEquipe.DADOS_INVALIDOS,
                        "horário inválido na " + dia + ": " + e.getMessage(),
                        Map.of("dia", dia.name()));
            }
        }).toList()));
        return new JornadaSemanal(porDia);
    }

    private void exigirSemSobreposicao(DayOfWeek dia, List<IntervaloDeTrabalho> intervalos) {
        for (int i = 0; i < intervalos.size(); i++) {
            for (int j = i + 1; j < intervalos.size(); j++) {
                if (intervalos.get(i).sobrepoe(intervalos.get(j))) {
                    throw new ErroDeDominio(ErrosDaEquipe.JORNADA_SOBREPOSTA,
                            "na " + dia + ", " + intervalos.get(i).inicio() + "-"
                                    + intervalos.get(i).fim() + " e " + intervalos.get(j).inicio()
                                    + "-" + intervalos.get(j).fim() + " se sobrepõem",
                            Map.of("dia", dia.name()));
                }
            }
        }
    }
}
