package br.com.salao.catalogo.internal.application;

import br.com.salao.catalogo.api.ErrosDoCatalogo;
import br.com.salao.catalogo.api.SequenciaDeBlocos;
import br.com.salao.catalogo.api.ServicoResumo;
import br.com.salao.catalogo.internal.infra.RecursosJdbc;
import br.com.salao.catalogo.internal.infra.ServicosJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CAT-005 — corrigir um serviço que já existe.
 *
 * <h2>Por que só agora</h2>
 *
 * O cardápio nasceu em RT-CAT-001 podendo ser criado e desativado, e nada mais. Enquanto não
 * havia tela, a falta não doía: ninguém digita errado num JSON que escreveu com calma. Com a tela,
 * a primeira letra trocada num nome vira um serviço que não tem conserto — e a saída seria
 * cadastrar outro e aposentar o velho, espalhando o histórico da agenda por dois cardápios.
 *
 * <h2>O que a edição NÃO faz (RN-CAT-015)</h2>
 *
 * Não mexe no que já foi marcado. O agendamento materializa os blocos dele quando é criado
 * (RT-AGD-002): a coloração de ontem continua com a pausa de 40 minutos que valia ontem, e a de
 * amanhã também — porque também já foi marcada. A receita nova vale para o que for marcado daqui
 * em diante, e é isso que a tela diz antes de salvar.
 *
 * <p>Alternativa descartada: reescrever os agendamentos futuros junto. Ela transforma uma correção
 * de digitação em movimento de agenda — muda horário de cliente que já foi avisada, e pode criar
 * conflito com o agendamento seguinte, que não tem por que ceder espaço.
 *
 * <h2>Concorrência</h2>
 *
 * A tela lê o serviço inteiro para editar, e duas pessoas podem tê-lo lido. O {@code versao} que
 * veio da leitura acompanha a gravação: quem chegou depois é recusado com {@code 409} em vez de
 * apagar a correção do outro em silêncio. É o mesmo padrão de RT-CLI-007 e RT-EST-001.
 */
public class EditarServicoUseCase {

    private static final Logger log = LoggerFactory.getLogger(EditarServicoUseCase.class);

    private final ServicosJdbc servicos;
    private final MontadorDeReceita receita;
    private final AuditoriaApi auditoria;

    public EditarServicoUseCase(ServicosJdbc servicos, RecursosJdbc recursos,
                                AuditoriaApi auditoria) {
        this.servicos = servicos;
        this.receita = new MontadorDeReceita(recursos);
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.CONFIG_GERENCIAR + "')")
    @Transactional
    public ServicoResumo executar(UUID id, String nome, String descricao, List<EtapaBruta> etapas,
                                  int versao) {
        ServicoResumo antes = servicos.porId(id)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoCatalogo.SERVICO_NAO_ENCONTRADO,
                        "serviço " + id + " não encontrado"));

        String limpo = texto(nome);
        if (limpo == null) {
            throw new ErroDeDominio(ErrosDoCatalogo.DADOS_INVALIDOS, "o nome é obrigatório");
        }
        SequenciaDeBlocos blocos = receita.montar(etapas);

        int linhas;
        try {
            linhas = servicos.atualizar(id, limpo, texto(descricao), versao);
        } catch (DuplicateKeyException e) {
            throw new ErroDeDominio(ErrosDoCatalogo.NOME_JA_CADASTRADO,
                    "já existe um serviço com este nome");
        }
        if (linhas == 0) {
            throw new ErroDeDominio(ErrosDoCatalogo.CONFLITO_DE_VERSAO,
                    "o serviço foi alterado por outra pessoa; recarregue e tente de novo");
        }
        servicos.gravarBlocos(TenantContext.obrigatorio(), id, blocos);

        auditoria.registrar(RegistroDeAuditoria.alteracao("SERVICO_ALTERADO", "servico", id,
                Map.of("nome", antes.nome(),
                        "duracaoTotal", antes.duracaoTotal(),
                        "minutosDoProfissional", antes.minutosDoProfissional()),
                Map.of("nome", limpo,
                        "duracaoTotal", blocos.duracaoTotal(),
                        "minutosDoProfissional", blocos.minutosDoProfissional())));
        log.info("Serviço {} alterado: {} min, {} do profissional", id, blocos.duracaoTotal(),
                blocos.minutosDoProfissional());

        return servicos.porId(id).orElseThrow();
    }

    private String texto(String bruto) {
        if (bruto == null) {
            return null;
        }
        String limpo = bruto.strip().replaceAll("\\s{2,}", " ");
        return limpo.isEmpty() ? null : limpo;
    }
}
