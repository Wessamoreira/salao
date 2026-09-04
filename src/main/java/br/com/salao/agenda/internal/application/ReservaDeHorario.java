package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.BlocoDoAgendamento;
import br.com.salao.agenda.api.ErrosDaAgenda;
import br.com.salao.agenda.api.Janela;
import br.com.salao.agenda.internal.domain.MaterializadorDeBlocos;
import br.com.salao.agenda.internal.domain.MaterializadorDeBlocos.BlocoNoTempo;
import br.com.salao.agenda.internal.infra.AgendaJdbc;
import br.com.salao.agenda.internal.infra.AgendaJdbc.UnidadeOcupada;
import br.com.salao.catalogo.api.Recurso;
import br.com.salao.catalogo.api.RecursoApi;
import br.com.salao.catalogo.api.SequenciaDeBlocos;
import br.com.salao.equipe.api.IntervaloDeTrabalho;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * RT-AGD-002 / RT-AGD-003 — transformar "este serviço, com esta pessoa, a esta hora" em linhas.
 *
 * <p>Criar e reagendar fazem exatamente o mesmo trabalho: materializar a receita no tempo, conferir
 * jornada e passado, e alocar as unidades de recurso. A única diferença é o que acontece depois —
 * inserir ou substituir. Duas cópias divergiriam, e a que divergisse seria a de reagendar: menos
 * usada, menos testada, e a que silenciosamente deixaria de checar a jornada.
 */
final class ReservaDeHorario {

    private final ProfissionalApi equipe;
    private final RecursoApi recursos;
    private final AgendaJdbc agenda;
    private final Relogio relogio;

    ReservaDeHorario(ProfissionalApi equipe, RecursoApi recursos, AgendaJdbc agenda,
                     Relogio relogio) {
        this.equipe = equipe;
        this.recursos = recursos;
        this.agenda = agenda;
        this.relogio = relogio;
    }

    /** O que sai da reserva: onde os blocos caem e qual o período total. */
    record Reserva(List<BlocoDoAgendamento> blocos, Janela periodo) {
    }

    Reserva montar(UUID profissionalId, SequenciaDeBlocos receita, java.time.Instant inicio,
                   ZoneId fuso, Forcamentos forcamentos) {
        List<BlocoNoTempo> noTempo = MaterializadorDeBlocos.materializar(receita, inicio);
        Janela periodo = MaterializadorDeBlocos.periodoTotal(noTempo);

        validarNaoRetroativo(periodo, forcamentos);
        validarJornada(profissionalId, noTempo, fuso, forcamentos);

        return new Reserva(alocar(profissionalId, noTempo, periodo), periodo);
    }

    /** Os dois "sim, eu sei" que o balcão pode dar, cada um com justificativa obrigatória. */
    record Forcamentos(boolean foraJornada, boolean passado, String justificativa) {

        void exigirJustificativa() {
            if (justificativa == null || justificativa.isBlank()) {
                // Forçar sem dizer por quê é o mesmo que não ter regra.
                throw new ErroDeDominio(ErrosDaAgenda.FORA_DA_JORNADA,
                        "forçar exige justificativa, que vai para a trilha");
            }
        }
    }

    /** RN-AGD-008 — a recepção lança o que ficou no papel, mas dizendo que é isso. */
    private void validarNaoRetroativo(Janela periodo, Forcamentos forcamentos) {
        if (!periodo.inicio().isBefore(relogio.agora())) {
            return;
        }
        if (!forcamentos.passado()) {
            throw new ErroDeDominio(ErrosDaAgenda.DATA_PASSADA,
                    "o horário " + periodo.inicio() + " já passou");
        }
        forcamentos.exigirJustificativa();
    }

    /**
     * RN-AGD-002 — todo bloco que ocupa o profissional dentro da jornada, menos as exceções.
     *
     * <p>Só os blocos ATIVO. A pausa pode cair no almoço: a cliente fica com a química agindo e a
     * profissional almoça — que é exatamente o que acontece no salão.
     */
    private void validarJornada(UUID profissionalId, List<BlocoNoTempo> blocos, ZoneId fuso,
                                Forcamentos forcamentos) {
        if (forcamentos.foraJornada()) {
            forcamentos.exigirJustificativa();
            return;
        }
        var livres = new ArrayList<Janela>();
        // Dois dias: um serviço pode atravessar a meia-noite, e a jornada é por dia da semana.
        for (LocalDate data : diasTocados(blocos, fuso)) {
            for (IntervaloDeTrabalho intervalo
                    : equipe.intervalosEm(List.of(profissionalId), data)
                            .getOrDefault(profissionalId, List.of())) {
                livres.add(new Janela(data.atTime(intervalo.inicio()).atZone(fuso).toInstant(),
                        data.atTime(intervalo.fim()).atZone(fuso).toInstant()));
            }
        }
        for (BlocoNoTempo bloco : blocos) {
            if (bloco.profissionalOcupado() && livres.stream().noneMatch(bloco.janela()::cabeEm)) {
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

    /**
     * Transforma cada bloco em linha, alocando a unidade de cada recurso exigido (RN-AGD-014).
     *
     * <p>Bloco com mais de um recurso vira mais de uma linha com a mesma {@code ordem}: a primeira
     * carrega o profissional, as demais só o recurso. É o caso raro — uma cliente usa uma cadeira e
     * um lavatório, não dois de cada — e agrupar por {@code ordem} devolve a linha do tempo.
     */
    private List<BlocoDoAgendamento> alocar(UUID profissionalId, List<BlocoNoTempo> noTempo,
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
            UUID profissional = bloco.profissionalOcupado() ? profissionalId : null;
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
}
