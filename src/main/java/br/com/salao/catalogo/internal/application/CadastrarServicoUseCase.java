package br.com.salao.catalogo.internal.application;

import br.com.salao.catalogo.api.BlocoDoServico;
import br.com.salao.catalogo.api.ErrosDoCatalogo;
import br.com.salao.catalogo.api.SequenciaDeBlocos;
import br.com.salao.catalogo.api.ServicoResumo;
import br.com.salao.catalogo.internal.infra.RecursosJdbc;
import br.com.salao.catalogo.internal.infra.ServicosJdbc;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CAT-001 — cadastrar serviço.
 *
 * <p>A permissão é {@code config:manage} e não {@code usuario:manage}: o cardápio é decisão do
 * dono, e mudar a duração de um serviço muda a agenda de todo mundo. Não é operação de balcão.
 */
public class CadastrarServicoUseCase {

    private static final Logger log = LoggerFactory.getLogger(CadastrarServicoUseCase.class);

    private final ServicosJdbc servicos;
    private final RecursosJdbc recursos;
    private final AuditoriaApi auditoria;

    public CadastrarServicoUseCase(ServicosJdbc servicos, RecursosJdbc recursos,
                                   AuditoriaApi auditoria) {
        this.servicos = servicos;
        this.recursos = recursos;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.CONFIG_GERENCIAR + "')")
    @Transactional
    public ServicoResumo executar(String nome, String descricao, List<EtapaBruta> etapas) {
        String limpo = texto(nome);
        if (limpo == null) {
            throw new ErroDeDominio(ErrosDoCatalogo.DADOS_INVALIDOS, "o nome é obrigatório");
        }
        SequenciaDeBlocos blocos = montar(etapas);

        try {
            UUID id = servicos.inserir(TenantContext.obrigatorio(), limpo, texto(descricao),
                    blocos, UsuarioAtual.idOuNulo());

            auditoria.registrar(RegistroDeAuditoria.criacao("servico", id,
                    Map.of("nome", limpo,
                            "duracaoTotal", blocos.duracaoTotal(),
                            "minutosDoProfissional", blocos.minutosDoProfissional(),
                            "recursosExigidos", blocos.recursosExigidos().size())));
            log.info("Serviço cadastrado: {} ({} min, {} do profissional)", limpo,
                    blocos.duracaoTotal(), blocos.minutosDoProfissional());

            return servicos.porId(id).orElseThrow();
        } catch (DuplicateKeyException e) {
            // Dois "Escova" no cardápio fazem a recepção escolher o errado metade das vezes, e o
            // extrato de comissão fica dividido entre os dois.
            throw new ErroDeDominio(ErrosDoCatalogo.NOME_JA_CADASTRADO,
                    "já existe um serviço com este nome");
        }
    }

    /**
     * Constrói o domínio a partir das etapas cruas, traduzindo a recusa.
     *
     * <p>Quem chama nunca constrói {@link SequenciaDeBlocos}: ela recusa sequência inválida com
     * {@code IllegalArgumentException}, e essa exceção vinda da borda viraria 500.
     */
    SequenciaDeBlocos montar(List<EtapaBruta> etapas) {
        if (etapas == null || etapas.isEmpty()) {
            throw new ErroDeDominio(ErrosDoCatalogo.SEQUENCIA_INVALIDA,
                    "o serviço precisa de ao menos uma etapa");
        }
        SequenciaDeBlocos sequencia;
        try {
            sequencia = new SequenciaDeBlocos(etapas.stream()
                    .map(etapa -> new BlocoDoServico(etapa.tipo(), etapa.duracaoMinutos(),
                            etapa.recursosExigidos()))
                    .toList());
        } catch (IllegalArgumentException e) {
            throw new ErroDeDominio(ErrosDoCatalogo.SEQUENCIA_INVALIDA, e.getMessage());
        }
        conferirRecursos(sequencia.recursosExigidos());
        return sequencia;
    }

    /**
     * RT-CAT-003 — os recursos exigidos existem, estão ativos e são deste salão.
     *
     * <p>As três perguntas numa consulta só, e a terceira é a que importa: no PostgreSQL as
     * checagens de chave estrangeira rodam como o dono da tabela e <strong>não passam pela
     * RLS</strong>. A FK de {@code bloco_exige_recurso} aceitaria um recurso de outro salão sem
     * reclamar — é aqui, numa consulta comum sujeita à política de tenant, que ele não volta.
     */
    private void conferirRecursos(Set<UUID> exigidos) {
        if (exigidos.isEmpty()) {
            return;
        }
        Set<UUID> encontrados = recursos.idsAtivos(exigidos);
        var faltando = new LinkedHashSet<>(exigidos);
        faltando.removeAll(encontrados);
        if (!faltando.isEmpty()) {
            // TreeSet só para a mensagem sair estável: um erro que muda de texto a cada execução
            // é um erro que ninguém consegue procurar no log.
            var ordenados = new TreeSet<String>();
            faltando.forEach(id -> ordenados.add(id.toString()));
            throw new ErroDeDominio(ErrosDoCatalogo.RECURSO_NAO_ENCONTRADO,
                    "recurso inexistente ou inativo: " + String.join(", ", ordenados),
                    Map.of("recursos", List.copyOf(ordenados)));
        }
    }

    private String texto(String bruto) {
        if (bruto == null) {
            return null;
        }
        String limpo = bruto.strip().replaceAll("\\s{2,}", " ");
        return limpo.isEmpty() ? null : limpo;
    }
}
