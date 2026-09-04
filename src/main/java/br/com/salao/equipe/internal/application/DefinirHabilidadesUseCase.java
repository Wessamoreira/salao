package br.com.salao.equipe.internal.application;

import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.equipe.api.ErrosDaEquipe;
import br.com.salao.equipe.api.Habilidades;
import br.com.salao.equipe.internal.infra.ProfissionaisJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tenant.TenantContext;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EQP-004 — definir o que um profissional executa.
 *
 * <h2>O regime e a lista mudam juntos</h2>
 *
 * <p>Um endpoint só, e não dois. Separá-los criaria o instante em que o profissional está
 * restrito e ainda sem nenhum serviço — que é exatamente o estado que {@link Habilidades} recusa.
 * Uma escrita atômica não tem esse intervalo.
 *
 * <h2>A checagem do serviço é a única barreira que existe</h2>
 *
 * <p>Não há chave estrangeira de {@code habilidade} para {@code servico}: ela costuraria dois
 * módulos e deixaria um {@code on delete} do catálogo mexer em dado da equipe. E, mesmo que
 * existisse, não separaria os salões — as checagens de integridade referencial do PostgreSQL não
 * passam pela RLS (RN-CAT-011).
 *
 * <p>Então esta consulta ao {@link ServicoApi}, que passa pela política de tenant, é o que impede
 * tanto o serviço inexistente quanto o serviço alheio.
 */
public class DefinirHabilidadesUseCase {

    private static final Logger log = LoggerFactory.getLogger(DefinirHabilidadesUseCase.class);

    private final ProfissionaisJdbc profissionais;
    private final ServicoApi catalogo;
    private final AuditoriaApi auditoria;

    public DefinirHabilidadesUseCase(ProfissionaisJdbc profissionais, ServicoApi catalogo,
                                     AuditoriaApi auditoria) {
        this.profissionais = profissionais;
        this.catalogo = catalogo;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.USUARIO_GERENCIAR + "')")
    @Transactional
    public Habilidades executar(UUID profissionalId, boolean executaTodos, Set<UUID> servicos) {
        if (profissionais.porId(profissionalId).isEmpty()) {
            throw new ErroDeDominio(ErrosDaEquipe.PROFISSIONAL_NAO_ENCONTRADO,
                    "profissional " + profissionalId + " não encontrado");
        }
        Habilidades antes = profissionais.habilidadesDe(profissionalId);
        Habilidades novas = montar(executaTodos, servicos);
        conferirServicos(novas.servicos());

        profissionais.substituirHabilidades(TenantContext.obrigatorio(), profissionalId, novas,
                UsuarioAtual.idOuNulo());

        auditoria.registrar(RegistroDeAuditoria.alteracao("HABILIDADES_DEFINIDAS", "profissional",
                profissionalId,
                Map.of("executaTodos", antes.executaTodos(), "servicos", antes.servicos().size()),
                Map.of("executaTodos", novas.executaTodos(), "servicos", novas.servicos().size())));

        if (antes.executaTodos() && !novas.executaTodos()) {
            // Vale um registro visível: é a passagem do regime aberto para o restrito, e a partir
            // dela todo serviço novo do cardápio nasce indisponível para esta pessoa.
            log.info("Profissional {} passou a executar apenas {} serviço(s)", profissionalId,
                    novas.servicos().size());
        }
        return profissionais.habilidadesDe(profissionalId);
    }

    /**
     * Traduz a recusa do domínio.
     *
     * <p>{@link Habilidades} recusa "restrito e sem nenhum serviço" com
     * {@code IllegalArgumentException} — correto no domínio, e 500 na borda. Sétima ocorrência da
     * família; o tradutor entrou junto com o código pela terceira vez seguida.
     */
    Habilidades montar(boolean executaTodos, Set<UUID> servicos) {
        try {
            return new Habilidades(executaTodos, servicos);
        } catch (IllegalArgumentException e) {
            throw new ErroDeDominio(ErrosDaEquipe.HABILIDADES_VAZIAS, e.getMessage());
        }
    }

    private void conferirServicos(Set<UUID> servicos) {
        if (servicos.isEmpty()) {
            return;
        }
        var faltando = new LinkedHashSet<>(servicos);
        faltando.removeAll(catalogo.idsExistentes(servicos));
        if (!faltando.isEmpty()) {
            var ordenados = new TreeSet<String>();
            faltando.forEach(id -> ordenados.add(id.toString()));
            throw new ErroDeDominio(ErrosDaEquipe.SERVICO_NAO_ENCONTRADO,
                    "serviço inexistente neste salão: " + String.join(", ", ordenados),
                    Map.of("servicos", List.copyOf(ordenados)));
        }
    }
}
