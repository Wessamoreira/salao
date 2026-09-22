package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.EsperaNaFila;
import br.com.salao.agenda.api.ErrosDaAgenda;
import br.com.salao.agenda.internal.infra.FilaDeEsperaJdbc;
import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.catalogo.api.ServicoResumo;
import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.equipe.api.ProfissionalResumo;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tenant.TenantContext;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-AGD-010 — "me avisa se abrir vaga".
 *
 * <h2>A fila expira sozinha</h2>
 *
 * <p>Ninguém precisa limpar entradas velhas para a fila funcionar: a busca por candidatos filtra
 * pelo intervalo de dias que a cliente aceitou, então uma espera cuja janela já passou simplesmente
 * para de aparecer.
 *
 * <p>Isso é de propósito. Uma fila que dependesse de um job de limpeza para dar a resposta certa
 * daria a resposta errada exatamente quando o job falhasse — e ninguém percebe um job que não
 * roda até alguém ser chamado para um horário de duas semanas atrás.
 *
 * <h2>Quem cabe numa vaga não é daqui</h2>
 *
 * <p>Esta classe guarda a espera. Quem cabe na vaga que abriu é {@code ConsultarVagasAbertasUseCase},
 * e o que a cliente respondeu é {@code RegistrarChamadaDaFilaUseCase} — a segunda metade da rotina,
 * de 22/09/2026.
 */
public class FilaDeEsperaUseCase {

    private static final Logger log = LoggerFactory.getLogger(FilaDeEsperaUseCase.class);

    private final FilaDeEsperaJdbc fila;
    private final ClienteApi clientes;
    private final ServicoApi catalogo;
    private final ProfissionalApi equipe;
    private final AuditoriaApi auditoria;

    public FilaDeEsperaUseCase(FilaDeEsperaJdbc fila, ClienteApi clientes, ServicoApi catalogo,
                               ProfissionalApi equipe, AuditoriaApi auditoria) {
        this.fila = fila;
        this.clientes = clientes;
        this.catalogo = catalogo;
        this.equipe = equipe;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_ESCREVER_TODAS + "')")
    @Transactional
    public EsperaNaFila entrar(UUID clienteId, UUID servicoId, UUID profissionalId,
                               LocalDate aceitaDe, LocalDate aceitaAte, LocalTime horaDe,
                               LocalTime horaAte, String observacao) {
        EsperaNaFila pedido = montar(clienteId, servicoId, profissionalId, aceitaDe, aceitaAte,
                horaDe, horaAte, observacao);
        conferirAlvos(pedido);

        UUID id = EntradaNaFila.gravar(fila, TenantContext.obrigatorio(), pedido);
        auditoria.registrar(RegistroDeAuditoria.criacao("fila_espera", id,
                Map.of("servicoId", servicoId.toString(), "de", aceitaDe.toString(),
                        "ate", aceitaAte.toString())));
        return fila.porId(id).orElseThrow();
    }

    private EsperaNaFila montar(UUID clienteId, UUID servicoId, UUID profissionalId,
                                LocalDate aceitaDe, LocalDate aceitaAte, LocalTime horaDe,
                                LocalTime horaAte, String observacao) {
        try {
            return new EsperaNaFila(null, clienteId, servicoId, profissionalId, aceitaDe,
                    aceitaAte, horaDe, horaAte, observacao, null, null);
        } catch (IllegalArgumentException e) {
            // Sétima vez: o domínio recusa com IllegalArgumentException, e sem tradutor isso é 500.
            throw new ErroDeDominio(ErrosDaAgenda.ESPERA_INVALIDA, e.getMessage());
        }
    }

    /** Cliente, serviço e profissional deste salão — as três checagens que não têm FK. */
    private void conferirAlvos(EsperaNaFila pedido) {
        if (!clientes.existe(pedido.clienteId())) {
            throw new ErroDeDominio(ErrosDaAgenda.CLIENTE_NAO_ENCONTRADO,
                    "cliente " + pedido.clienteId() + " não encontrado");
        }
        if (catalogo.porId(pedido.servicoId()).isEmpty()) {
            throw new ErroDeDominio(ErrosDaAgenda.SERVICO_NAO_ENCONTRADO,
                    "serviço " + pedido.servicoId() + " não encontrado");
        }
        if (pedido.profissionalId() != null && !equipe.estaAtivo(pedido.profissionalId())) {
            throw new ErroDeDominio(ErrosDaAgenda.PROFISSIONAL_NAO_EXECUTA,
                    "profissional " + pedido.profissionalId() + " não encontrado ou inativo");
        }
    }

    /**
     * Quem está esperando, do mais antigo para o mais novo, com os nomes que a tela escreve.
     *
     * <p>Os nomes vêm em lote dos módulos donos — um por cliente, serviço e profissional
     * <em>distintos</em>, e não um por linha: vinte pessoas esperando escova são uma consulta de
     * serviço, não vinte.
     */
    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_LER_TODAS + "')"
            + " or hasAuthority('" + Permissao.AGENDA_LER_PROPRIA + "')")
    @Transactional(readOnly = true)
    public List<EsperaComNomes> naFila() {
        List<EsperaNaFila> esperas = fila.naFila();
        Map<UUID, String> nomesDeClientes = clientes.nomesDe(
                esperas.stream().map(EsperaNaFila::clienteId).collect(Collectors.toSet()));
        var nomesDeServicos = new HashMap<UUID, String>();
        var nomesDeProfissionais = new HashMap<UUID, String>();
        return esperas.stream()
                .map(espera -> new EsperaComNomes(espera,
                        nomesDeClientes.get(espera.clienteId()),
                        nomesDeServicos.computeIfAbsent(espera.servicoId(), id -> catalogo.porId(id)
                                .map(ServicoResumo::nome).orElse(null)),
                        espera.profissionalId() == null ? null
                                : nomesDeProfissionais.computeIfAbsent(espera.profissionalId(),
                                        id -> equipe.porId(id).map(FilaDeEsperaUseCase::apelido)
                                                .orElse(null))))
                .toList();
    }

    /** Como a recepção chama a profissional: o apelido, quando há. */
    static String apelido(ProfissionalResumo profissional) {
        return profissional.apelido() == null || profissional.apelido().isBlank()
                ? profissional.nome()
                : profissional.apelido();
    }

    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_ESCREVER_TODAS + "')")
    @Transactional
    public void marcarAtendida(UUID esperaId) {
        sair(esperaId, true);
    }

    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_ESCREVER_TODAS + "')")
    @Transactional
    public void registrarDesistencia(UUID esperaId) {
        sair(esperaId, false);
    }

    /**
     * As duas saídas da fila, e a diferença importa.
     *
     * <p>"Conseguiu horário" é o sistema funcionando; "cansou de esperar" é a fila sendo longa
     * demais. Um campo só, ou um {@code delete}, apagaria justamente o número que diz se a fila
     * está servindo para alguma coisa.
     */
    private void sair(UUID esperaId, boolean atendida) {
        EsperaNaFila antes = fila.porId(esperaId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDaAgenda.ESPERA_NAO_ENCONTRADA,
                        "espera " + esperaId + " não encontrada"));
        int alteradas = atendida ? fila.marcarAtendida(esperaId) : fila.marcarDesistencia(esperaId);
        if (alteradas == 0) {
            throw new ErroDeDominio(ErrosDaAgenda.ESPERA_NAO_ENCONTRADA,
                    "esta espera já saiu da fila");
        }
        auditoria.registrar(RegistroDeAuditoria.alteracao(
                atendida ? "FILA_ATENDIDA" : "FILA_DESISTENCIA", "fila_espera", esperaId,
                Map.of("naFila", true),
                Map.of("clienteId", antes.clienteId().toString(), "atendida", atendida)));
        log.info("Espera {} saiu da fila: {}", esperaId, atendida ? "atendida" : "desistiu");
    }
}
