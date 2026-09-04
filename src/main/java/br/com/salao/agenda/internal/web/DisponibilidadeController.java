package br.com.salao.agenda.internal.web;

import br.com.salao.agenda.api.HorarioDisponivel;
import br.com.salao.agenda.internal.application.ConsultarDisponibilidadeUseCase;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RT-AGD-001 — que horário posso oferecer.
 *
 * <p>{@code profissional} é opcional, e é o caso comum de quem marca por telefone: a cliente quer
 * o horário, e só depois escolhe com quem.
 */
@RestController
@RequestMapping("/api/v1/disponibilidade")
public class DisponibilidadeController {

    private final ConsultarDisponibilidadeUseCase consultar;

    public DisponibilidadeController(ConsultarDisponibilidadeUseCase consultar) {
        this.consultar = consultar;
    }

    @GetMapping
    public List<HorarioDisponivel> consultar(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @RequestParam UUID servico,
            @RequestParam(required = false) UUID profissional) {
        return consultar.consultar(data, servico, profissional);
    }
}
