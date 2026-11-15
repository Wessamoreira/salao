package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.DisponibilidadeApi;
import br.com.salao.agenda.api.HorarioDisponivel;
import br.com.salao.iam.api.Permissao;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * RT-AGD-001 — que horário posso oferecer.
 *
 * <p>A conta mora em {@code CalculoDeDisponibilidade}, compartilhada com o caso de uso do balcão
 * (ADR-0011). Aqui fica o que é desta porta: a permissão.
 *
 * <h2>Ler a agenda dos outros exige permissão</h2>
 *
 * <p>{@code agenda:read:all} ou {@code config:manage}. Quem só tem {@code agenda:read:own}
 * consegue consultar, mas a rotina não filtra por dono — está registrado como pendência, porque o
 * filtro certo depende do vínculo usuário↔profissional, que ainda não existe.
 */
public class ConsultarDisponibilidadeUseCase implements DisponibilidadeApi {

    private final CalculoDeDisponibilidade calculo;

    public ConsultarDisponibilidadeUseCase(CalculoDeDisponibilidade calculo) {
        this.calculo = calculo;
    }

    @Override
    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_LER_TODAS + "')"
            + " or hasAuthority('" + Permissao.AGENDA_LER_PROPRIA + "')"
            + " or hasAuthority('" + Permissao.CONFIG_GERENCIAR + "')")
    public List<HorarioDisponivel> consultar(LocalDate data, UUID servicoId, UUID profissionalId) {
        return calculo.calcular(data, servicoId, profissionalId);
    }
}
