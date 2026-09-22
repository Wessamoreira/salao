package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.internal.domain.CandidatasDaVaga;
import br.com.salao.agenda.internal.domain.CandidatasDaVaga.Candidata;
import br.com.salao.agenda.internal.domain.ChamadaDaFila;
import br.com.salao.agenda.internal.infra.VagasAbertasJdbc;
import br.com.salao.agenda.internal.infra.VagasAbertasJdbc.Vaga;
import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.catalogo.api.ServicoResumo;
import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.cliente.api.ClienteResumo;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.NomesDeUsuariosApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-AGD-010 — as vagas que abriram e quem da fila cabe em cada uma.
 *
 * <p>Só aparecem vagas com alguém que cabe. A que ninguém da fila preenche não pede ação desta tela
 * — a grade da agenda já mostra o buraco —, e listá-la seria ruído no lugar em que a recepção
 * procura quem ligar.
 *
 * <p>{@code agenda:read:all}, e não a leitura da própria agenda: a lista traz o telefone de clientes
 * que esperam por qualquer profissional.
 */
public class ConsultarVagasAbertasUseCase {

    private final VagasAbertasJdbc vagas;
    private final CalculoDeCandidatas calculo;
    private final ClienteApi clientes;
    private final ServicoApi catalogo;
    private final ProfissionalApi equipe;
    private final NomesDeUsuariosApi usuarios;
    private final EstabelecimentoApi estabelecimentos;
    private final Relogio relogio;

    public ConsultarVagasAbertasUseCase(VagasAbertasJdbc vagas, CalculoDeCandidatas calculo,
                                        ClienteApi clientes, ServicoApi catalogo,
                                        ProfissionalApi equipe, NomesDeUsuariosApi usuarios,
                                        EstabelecimentoApi estabelecimentos, Relogio relogio) {
        this.vagas = vagas;
        this.calculo = calculo;
        this.clientes = clientes;
        this.catalogo = catalogo;
        this.equipe = equipe;
        this.usuarios = usuarios;
        this.estabelecimentos = estabelecimentos;
        this.relogio = relogio;
    }

    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_LER_TODAS + "')")
    @Transactional(readOnly = true)
    public List<VagaComCandidatas> executar() {
        Instant agora = relogio.agora();
        ZoneId fuso = estabelecimentos.fusoDe(TenantContext.obrigatorio());

        List<Vaga> abertas = vagas.abertas(agora);
        Map<UUID, List<Candidata>> candidatas = calculo.de(abertas, agora, fuso);
        List<Vaga> comQuemLigar = abertas.stream()
                .filter(vaga -> !candidatas.get(vaga.id()).isEmpty())
                .toList();

        List<ChamadaDaFila> chamadas = vagas.chamadasDas(comQuemLigar.stream().map(Vaga::id).toList());
        Map<UUID, String> quemRegistrou = usuarios.nomesDe(chamadas.stream()
                .map(ChamadaDaFila::registradaPor).filter(Objects::nonNull)
                .collect(Collectors.toSet()));

        var nomes = new Nomes();
        var resultado = new ArrayList<VagaComCandidatas>();
        for (Vaga vaga : comQuemLigar) {
            List<Candidata> daVaga = candidatas.get(vaga.id());
            Set<UUID> jaChamadas = chamadas.stream()
                    .filter(chamada -> chamada.vagaId().equals(vaga.id()))
                    .map(ChamadaDaFila::esperaId)
                    .collect(Collectors.toSet());
            List<VagaComCandidatas.Candidata> linhas = daVaga.stream()
                    .map(candidata -> linha(candidata, chamadasDe(chamadas, vaga.id(),
                            candidata.espera().id(), quemRegistrou),
                            CandidatasDaVaga.puladaAoChamar(daVaga, candidata.espera().id(),
                                    jaChamadas).isPresent(),
                            nomes))
                    .toList();
            resultado.add(new VagaComCandidatas(vaga.id(), vaga.profissionalId(),
                    nomes.profissional(vaga.profissionalId()), vaga.inicio(), vaga.fim(),
                    vaga.origem(), linhas));
        }
        return List.copyOf(resultado);
    }

    private VagaComCandidatas.Candidata linha(Candidata candidata,
                                              List<VagaComCandidatas.Chamada> chamadas,
                                              boolean exigeMotivo, Nomes nomes) {
        var espera = candidata.espera();
        Optional<ClienteResumo> cliente = nomes.cliente(espera.clienteId());
        return new VagaComCandidatas.Candidata(espera.id(), candidata.posicao(),
                espera.clienteId(), cliente.map(ClienteResumo::comoChamar).orElse(null),
                cliente.map(ClienteResumo::telefone).orElse(null), espera.servicoId(),
                nomes.servico(espera.servicoId()), espera.aceitaDe(), espera.aceitaAte(),
                espera.horaDe(), espera.horaAte(), espera.criadoEm(), candidata.horarios(),
                chamadas, exigeMotivo);
    }

    private static List<VagaComCandidatas.Chamada> chamadasDe(List<ChamadaDaFila> chamadas, UUID vagaId,
                                                             UUID esperaId,
                                                             Map<UUID, String> quemRegistrou) {
        return chamadas.stream()
                .filter(c -> c.vagaId().equals(vagaId) && c.esperaId().equals(esperaId))
                .map(c -> new VagaComCandidatas.Chamada(c.resultado(), c.registradaEm(),
                        c.registradaPor() == null ? null : quemRegistrou.get(c.registradaPor()),
                        c.motivoDoPulo()))
                .toList();
    }

    /**
     * Os nomes da leitura, cada um buscado uma vez: a mesma cliente em três vagas é uma consulta.
     *
     * <p>A cliente vem inteira, e não só o nome, porque a recepção precisa do telefone para ligar —
     * é a única tela da agenda que o carrega.
     */
    private final class Nomes {

        private final Map<UUID, Optional<ClienteResumo>> clientesPorId = new HashMap<>();
        private final Map<UUID, String> servicos = new HashMap<>();
        private final Map<UUID, String> profissionais = new HashMap<>();

        Optional<ClienteResumo> cliente(UUID id) {
            return clientesPorId.computeIfAbsent(id, clientes::porId);
        }

        String servico(UUID id) {
            return servicos.computeIfAbsent(id,
                    chave -> catalogo.porId(chave).map(ServicoResumo::nome).orElse(null));
        }

        String profissional(UUID id) {
            return profissionais.computeIfAbsent(id, chave -> equipe.porId(chave)
                    .map(FilaDeEsperaUseCase::apelido).orElse(null));
        }
    }
}
