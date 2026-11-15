package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.HorarioDisponivel;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CNV-010 / ADR-0011 — que horários estão livres, para quem não tem conta.
 *
 * <p><strong>Sem {@code @PreAuthorize}, e isso é a decisão, não um esquecimento.</strong> Quem
 * pergunta é uma pessoa sem conta e sem perfil; não há principal a conferir. Está na lista
 * {@code PUBLICOS_DO_BALCAO} do {@code ArquiteturaTest}, que também garante que só a ferramenta do
 * balcão o alcança.
 *
 * <h2>O recorte é um TIPO, não um filtro</h2>
 *
 * <p>A conta é a mesma da equipe — copiá-la faria duas matemáticas de horário divergirem, e a que
 * diverge em silêncio oferece à cliente um horário que o cadastro vai recusar. O que muda é o que
 * <em>sai</em>: {@link HorarioAberto} não tem id de profissional nem
 * {@code minutosDoProfissional}, que é quanto o salão consegue vender daquele intervalo para outra
 * pessoa — informação de dentro do negócio, não do balcão.
 *
 * <p>Um DTO recortado, e não uma filtragem na saída: campo que não existe no tipo não vaza no dia
 * em que alguém acrescenta um ao DTO de cima.
 */
public class HorariosDoBalcaoUseCase {

    private final CalculoDeDisponibilidade calculo;

    public HorariosDoBalcaoUseCase(CalculoDeDisponibilidade calculo) {
        this.calculo = calculo;
    }

    /** @param profissional como a cliente o chama — apelido, ou nome quando não há apelido */
    public record HorarioAberto(Instant inicio, Instant fim, String profissional) {
    }

    @Transactional(readOnly = true)
    public List<HorarioAberto> executar(LocalDate data, UUID servicoId) {
        return calculo.calcular(data, servicoId, null).stream()
                .map(h -> new HorarioAberto(h.inicio(), h.fim(), h.profissional()))
                .toList();
    }

    /** Só o que aquela pessoa tem livre — "você tem um profissional que já te atende?". */
    @Transactional(readOnly = true)
    public List<HorarioAberto> executar(LocalDate data, UUID servicoId, String comQuem) {
        return calculo.calcular(data, servicoId, null).stream()
                .filter(h -> h.profissional().equalsIgnoreCase(comQuem.strip()))
                .map(h -> new HorarioAberto(h.inicio(), h.fim(), h.profissional()))
                .toList();
    }
}
