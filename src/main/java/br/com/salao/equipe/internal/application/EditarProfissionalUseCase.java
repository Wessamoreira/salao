package br.com.salao.equipe.internal.application;

import br.com.salao.equipe.api.ErrosDaEquipe;
import br.com.salao.equipe.api.ProfissionalResumo;
import br.com.salao.equipe.internal.infra.ProfissionaisJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EQP-005 — corrigir a ficha de quem já está na equipe.
 *
 * <h2>Por que só agora</h2>
 *
 * A equipe nasceu em RT-EQP-001 podendo ser cadastrada, ativada e desativada, e nada mais.
 * Enquanto não havia tela a falta não doía; com a tela, um telefone que mudou não tinha conserto,
 * e a saída seria cadastrar a pessoa de novo e desativar a primeira — partindo o extrato de
 * comissão em duas fichas com o mesmo nome.
 *
 * <h2>O que NÃO se edita (RN-EQP-017)</h2>
 *
 * Documento e vínculo. Os dois são a identidade jurídica da relação: o vínculo determina o tipo
 * de documento (RN-EQP-002), e trocar o documento é trocar de pessoa jurídica. Permitir a edição
 * faria uma empresa herdar o histórico de comissão de outra sem que nada na trilha dissesse o
 * que aconteceu — e o extrato de um mês fechado passaria a apontar para alguém que não o
 * executou. Quem muda de vínculo entra como ficha nova, com a antiga desativada.
 *
 * <h2>Concorrência</h2>
 *
 * A tela lê a ficha inteira para editar. A {@code versao} lida acompanha a gravação: quem chegou
 * depois é recusado em vez de apagar a correção do outro — numa ficha que carrega a comissão, que
 * é quanto a pessoa recebe.
 */
public class EditarProfissionalUseCase {

    private static final Logger log = LoggerFactory.getLogger(EditarProfissionalUseCase.class);

    private final ProfissionaisJdbc profissionais;
    private final CamposDaFicha campos;
    private final AuditoriaApi auditoria;

    public EditarProfissionalUseCase(ProfissionaisJdbc profissionais,
                                     EstabelecimentoApi estabelecimentos,
                                     AuditoriaApi auditoria) {
        this.profissionais = profissionais;
        this.campos = new CamposDaFicha(estabelecimentos);
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.USUARIO_GERENCIAR + "')")
    @Transactional
    public ProfissionalResumo executar(UUID id, DadosEditaveis dados, int versao) {
        ProfissionalResumo antes = profissionais.porId(id)
                .orElseThrow(() -> new ErroDeDominio(ErrosDaEquipe.PROFISSIONAL_NAO_ENCONTRADO,
                        "profissional " + id + " não encontrado"));

        String nome = campos.nomeObrigatorio(dados.nome());
        BigDecimal comissao = campos.comissao(dados.comissaoPercentual());

        int linhas = profissionais.atualizar(id, nome, campos.texto(dados.apelido()),
                campos.telefone(dados.telefone()), campos.texto(dados.email()), comissao,
                dados.contratoInicio(), dados.contratoFim(), dados.contratoAssinadoEm(), versao);
        if (linhas == 0) {
            throw new ErroDeDominio(ErrosDaEquipe.CONFLITO_DE_VERSAO,
                    "a ficha foi alterada por outra pessoa; recarregue e tente de novo");
        }

        // Sem contato na trilha: é dado pessoal, e a auditoria é append-only. A comissão entra
        // porque é a decisão administrativa que alguém pode precisar justificar depois — e nula
        // quer dizer "usa a cascata" (RT-FIN-002), não zero.
        auditoria.registrar(RegistroDeAuditoria.alteracao("PROFISSIONAL_ALTERADO", "profissional",
                id,
                Map.of("comissao", String.valueOf(antes.comissaoPercentual())),
                Map.of("comissao", String.valueOf(comissao))));
        log.info("Profissional {} alterado", id);

        return profissionais.porId(id).orElseThrow();
    }

    /** O que a tela pode mudar. Documento e vínculo não estão aqui, e é a regra (RN-EQP-017). */
    public record DadosEditaveis(String nome, String apelido, String telefone, String email,
                                 BigDecimal comissaoPercentual, LocalDate contratoInicio,
                                 LocalDate contratoFim, LocalDate contratoAssinadoEm) {
    }

}
