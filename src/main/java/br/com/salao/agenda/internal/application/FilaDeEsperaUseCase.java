package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.EsperaNaFila;
import br.com.salao.agenda.api.ErrosDaAgenda;
import br.com.salao.agenda.internal.infra.FilaDeEsperaJdbc;
import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.cliente.api.ClienteApi;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
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
 * <h2>O que esta rotina NÃO faz</h2>
 *
 * <p>Não avisa ninguém. A chamada automática do plano — notificar o primeiro da fila e dar um
 * prazo antes de passar ao seguinte — depende de {@code notificacao}, que não existe. Registrar
 * uma "oferta enviada" sem enviar nada seria gravar uma mentira que o relatório depois trataria
 * como verdade.
 *
 * <p>O que existe é {@link #candidatosA}: cancelou um horário, veja quem cabe nele. É como um
 * salão pequeno de fato trabalha — a recepção liga —, e é o insumo exato de que a automação vai
 * precisar quando houver como avisar.
 */
public class FilaDeEsperaUseCase {

    private static final Logger log = LoggerFactory.getLogger(FilaDeEsperaUseCase.class);

    private final FilaDeEsperaJdbc fila;
    private final ClienteApi clientes;
    private final ServicoApi catalogo;
    private final ProfissionalApi equipe;
    private final EstabelecimentoApi estabelecimentos;
    private final AuditoriaApi auditoria;

    public FilaDeEsperaUseCase(FilaDeEsperaJdbc fila, ClienteApi clientes, ServicoApi catalogo,
                               ProfissionalApi equipe, EstabelecimentoApi estabelecimentos,
                               AuditoriaApi auditoria) {
        this.fila = fila;
        this.clientes = clientes;
        this.catalogo = catalogo;
        this.equipe = equipe;
        this.estabelecimentos = estabelecimentos;
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

        UUID id;
        try {
            id = fila.entrar(TenantContext.obrigatorio(), pedido, UsuarioAtual.idOuNulo());
        } catch (DuplicateKeyException e) {
            // Duas entradas seriam duas ligações pela mesma vaga, e a segunda é a que faz o salão
            // parecer desorganizado justamente com quem já está esperando.
            throw new ErroDeDominio(ErrosDaAgenda.JA_ESTA_NA_FILA,
                    "esta cliente já está na fila para este serviço");
        }
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
                    aceitaAte, horaDe, horaAte, observacao, null);
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

    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_LER_TODAS + "')"
            + " or hasAuthority('" + Permissao.AGENDA_LER_PROPRIA + "')")
    @Transactional(readOnly = true)
    public List<EsperaNaFila> naFila() {
        return fila.naFila();
    }

    /**
     * Quem cabe nesta vaga, na ordem de quem chegou primeiro.
     *
     * <p>Chamada depois de um cancelamento: a recepção vê a lista e liga. Não há disparo
     * automático — ver o javadoc da classe.
     */
    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_LER_TODAS + "')"
            + " or hasAuthority('" + Permissao.AGENDA_LER_PROPRIA + "')")
    @Transactional(readOnly = true)
    public List<EsperaNaFila> candidatosA(UUID servicoId, UUID profissionalId, Instant inicio) {
        ZoneId fuso = estabelecimentos.fusoDe(TenantContext.obrigatorio());
        return fila.candidatosA(servicoId, profissionalId, inicio.atZone(fuso).toLocalDate())
                .stream()
                // A faixa de hora fica no domínio: é relógio de parede, e um `where` sobre isso
                // exigiria converter o instante dentro do SQL com o fuso vindo de outra tabela.
                .filter(espera -> espera.serve(servicoId, profissionalId, inicio, fuso))
                .toList();
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
