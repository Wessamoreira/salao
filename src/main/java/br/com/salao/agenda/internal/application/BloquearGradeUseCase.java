package br.com.salao.agenda.internal.application;

import br.com.salao.agenda.api.BloqueioDaGrade;
import br.com.salao.agenda.api.ErrosDaAgenda;
import br.com.salao.agenda.api.Janela;
import br.com.salao.agenda.api.MotivoDoBloqueioDeAgenda;
import br.com.salao.agenda.internal.infra.AgendaJdbc;
import br.com.salao.catalogo.api.RecursoApi;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tenant.TenantContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-AGD-007 — arrastar na grade para bloquear.
 *
 * <h2>Não é o bloqueio de jornada</h2>
 *
 * <p>{@code equipe} já tem um (RT-EQP-003), e a diferença é de natureza:
 *
 * <ul>
 *   <li><strong>Bloqueio de jornada</strong> molda <em>quando a pessoa trabalha</em> — férias,
 *       feriado, curso. Entra no cálculo de disponibilidade e é <strong>forçável</strong>: um admin
 *       marca por cima com justificativa.</li>
 *   <li><strong>Bloqueio de grade</strong> (este) <em>ocupa</em> um pedaço, como um atendimento.
 *       Vale para recurso, e <strong>não é forçável</strong>: o banco recusa.</li>
 * </ul>
 *
 * <p>A distinção importa porque "a Ana está em curso" é informação sobre a agenda dela, e o dono
 * pode decidir marcar assim mesmo. "O lavatório 2 está quebrado" não é opinião — marcar por cima
 * produziria uma cliente com a cabeça ensaboada e nenhum lugar para lavar.
 *
 * <h2>Sem formulário</h2>
 *
 * <p>O gesto é arrastar: o período vem do que foi desenhado, e só o motivo precisa ser escolhido.
 * Descrição é opcional. Uma tela com seis campos obrigatórios faria a recepção anotar no papel — que
 * é o que este sistema inteiro existe para evitar.
 */
public class BloquearGradeUseCase {

    private static final Logger log = LoggerFactory.getLogger(BloquearGradeUseCase.class);

    private final AgendaJdbc agenda;
    private final ProfissionalApi equipe;
    private final RecursoApi recursos;
    private final AuditoriaApi auditoria;

    public BloquearGradeUseCase(AgendaJdbc agenda, ProfissionalApi equipe, RecursoApi recursos,
                                AuditoriaApi auditoria) {
        this.agenda = agenda;
        this.equipe = equipe;
        this.recursos = recursos;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_ESCREVER_TODAS + "')")
    @Transactional
    public BloqueioDaGrade executar(UUID profissionalId, UUID recursoId,
                                    MotivoDoBloqueioDeAgenda motivo, String descricao,
                                    Instant inicio, Instant fim) {
        BloqueioDaGrade pedido = montar(profissionalId, recursoId, motivo, descricao, inicio, fim);
        conferirAlvos(pedido);

        UUID id;
        try {
            id = agenda.inserirBloqueio(TenantContext.obrigatorio(), pedido,
                    UsuarioAtual.idOuNulo());
        } catch (DataIntegrityViolationException e) {
            // A mesma constraint dos atendimentos. Bloquear por cima de um horário marcado é
            // recusado — e é o comportamento certo: quem quer o horário livre cancela primeiro,
            // avisando a cliente, em vez de fazê-la chegar e descobrir.
            throw new ErroDeDominio(ErrosDaAgenda.CONFLITO_HORARIO,
                    "há atendimento marcado neste horário; cancele antes de bloquear");
        }

        auditoria.registrar(RegistroDeAuditoria.criacao("bloqueio", id, trilha(pedido)));
        log.info("Grade bloqueada por {} de {} a {}", motivo, inicio, fim);
        return agenda.bloqueioPorId(id).orElseThrow();
    }

    /**
     * Traduz as recusas do domínio.
     *
     * <p>{@link BloqueioDaGrade} e {@link Janela} recusam entrada inválida com
     * {@code IllegalArgumentException} — correto no domínio, e 500 na borda. É a mesma família de
     * defeito de sempre; o tradutor entrou junto com o código pela quinta vez seguida.
     */
    private BloqueioDaGrade montar(UUID profissionalId, UUID recursoId,
                                   MotivoDoBloqueioDeAgenda motivo, String descricao,
                                   Instant inicio, Instant fim) {
        try {
            return new BloqueioDaGrade(null, profissionalId, recursoId,
                    recursoId == null ? null : 1, motivo, descricao, new Janela(inicio, fim));
        } catch (IllegalArgumentException e) {
            throw new ErroDeDominio(ErrosDaAgenda.BLOQUEIO_INVALIDO, e.getMessage());
        }
    }

    /**
     * O profissional e o recurso existem neste salão.
     *
     * <p>Não há chave estrangeira para nenhum dos dois — são de outros módulos (RN-EQP-015) — e,
     * por RN-CAT-011, ela também não separaria os salões. Esta checagem é a única barreira.
     */
    private void conferirAlvos(BloqueioDaGrade pedido) {
        if (pedido.profissionalId() != null && !equipe.estaAtivo(pedido.profissionalId())) {
            throw new ErroDeDominio(ErrosDaAgenda.BLOQUEIO_INVALIDO,
                    "profissional " + pedido.profissionalId() + " não encontrado ou inativo");
        }
        if (pedido.recursoId() != null && recursos.porId(pedido.recursoId()).isEmpty()) {
            throw new ErroDeDominio(ErrosDaAgenda.BLOQUEIO_INVALIDO,
                    "recurso " + pedido.recursoId() + " não encontrado");
        }
    }

    private Map<String, Object> trilha(BloqueioDaGrade pedido) {
        var dados = new LinkedHashMap<String, Object>();
        dados.put("motivo", pedido.motivo().name());
        dados.put("inicio", pedido.janela().inicio().toString());
        dados.put("fim", pedido.janela().fim().toString());
        if (pedido.profissionalId() != null) {
            dados.put("profissionalId", pedido.profissionalId().toString());
        }
        if (pedido.recursoId() != null) {
            dados.put("recursoId", pedido.recursoId().toString());
        }
        return dados;
    }

    // ------------------------------------------------------------------ leitura e remoção

    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_LER_TODAS + "')"
            + " or hasAuthority('" + Permissao.AGENDA_LER_PROPRIA + "')")
    @Transactional(readOnly = true)
    public List<BloqueioDaGrade> listar(Instant inicio, Instant fim) {
        try {
            return agenda.bloqueiosEm(new Janela(inicio, fim));
        } catch (IllegalArgumentException e) {
            throw new ErroDeDominio(ErrosDaAgenda.BLOQUEIO_INVALIDO, e.getMessage());
        }
    }

    /**
     * Apagar, e não desativar.
     *
     * <p>Um bloqueio não sustenta histórico nenhum: nenhum atendimento aponta para ele, nenhuma
     * comissão o usa. É a mesma decisão de RT-EQP-003 — <em>apaga-se o que não sustenta nada</em>.
     * O {@code on delete cascade} leva a linha da grade junto, e o horário volta no mesmo instante.
     */
    @PreAuthorize("hasAuthority('" + Permissao.AGENDA_ESCREVER_TODAS + "')")
    @Transactional
    public void remover(UUID bloqueioId) {
        BloqueioDaGrade antes = agenda.bloqueioPorId(bloqueioId)
                .orElseThrow(() -> new ErroDeDominio(ErrosDaAgenda.BLOQUEIO_NAO_ENCONTRADO,
                        "bloqueio " + bloqueioId + " não encontrado"));
        agenda.apagarBloqueio(bloqueioId);
        auditoria.registrar(RegistroDeAuditoria.alteracao("BLOQUEIO_REMOVIDO", "bloqueio",
                bloqueioId, trilha(antes), Map.of()));
    }
}
