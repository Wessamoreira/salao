package br.com.salao.equipe.internal.application;

import br.com.salao.equipe.api.ErrosDaEquipe;
import br.com.salao.equipe.api.ProfissionalResumo;
import br.com.salao.equipe.api.TipoDeVinculo;
import br.com.salao.equipe.internal.domain.Documento;
import br.com.salao.equipe.internal.domain.DocumentoInvalidoException;
import br.com.salao.equipe.internal.infra.ProfissionaisJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.CampoInvalido;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.telefone.Telefone;
import br.com.salao.shared.telefone.TelefoneInvalidoException;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EQP-001 — cadastrar profissional.
 *
 * <h2>O vínculo decide o documento</h2>
 *
 * <p>Parceiro é pessoa jurídica pela Lei 13.352/2016, e portanto CNPJ. Empregado e autônomo são
 * pessoa física, e portanto CPF. Não é preferência de cadastro: um "parceiro" com CPF é, na
 * prática, a descrição de um vínculo empregatício disfarçado — exatamente o que a lei existe para
 * evitar, e o que uma fiscalização procura.
 *
 * <h2>Contrato ausente avisa, não bloqueia</h2>
 *
 * <p>A parceria sem contrato escrito é o maior risco jurídico do modelo. Ainda assim o cadastro
 * passa: bloquear faria o salão simplesmente não cadastrar o profissional — e aí perde-se o
 * profissional <em>e</em> continua sem o contrato. Registrar a ausência é o que permite avisar.
 *
 * <p>É o mesmo raciocínio do duplicado de cliente (RN-CLI-016): recusar cadastro legítimo ensina a
 * contornar.
 */
public class CadastrarProfissionalUseCase {

    private static final Logger log = LoggerFactory.getLogger(CadastrarProfissionalUseCase.class);

    private final ProfissionaisJdbc profissionais;
    private final AuditoriaApi auditoria;
    private final CamposDaFicha campos;

    public CadastrarProfissionalUseCase(ProfissionaisJdbc profissionais, AuditoriaApi auditoria,
                                        EstabelecimentoApi estabelecimentos) {
        this.profissionais = profissionais;
        this.auditoria = auditoria;
        this.campos = new CamposDaFicha(estabelecimentos);
    }

    @PreAuthorize("hasAuthority('" + Permissao.USUARIO_GERENCIAR + "')")
    @Transactional
    public ProfissionalResumo executar(DadosDoProfissional dados) {
        String nome = campos.nomeObrigatorio(dados.nome());
        if (dados.vinculo() == null) {
            throw new ErroDeDominio(ErrosDaEquipe.DADOS_INVALIDOS, "o vínculo é obrigatório");
        }

        Documento documento = documentoDe(dados.documento());
        exigirDocumentoCompativel(documento, dados.vinculo());
        BigDecimal comissao = campos.comissao(dados.comissaoPercentual());

        try {
            UUID id = profissionais.inserir(TenantContext.obrigatorio(), nome,
                    campos.texto(dados.apelido()), documento, dados.vinculo(),
                    campos.telefone(dados.telefone()), campos.texto(dados.email()), comissao, dados.contratoInicio(), dados.contratoFim(),
                    dados.contratoAssinadoEm(), UsuarioAtual.idOuNulo());

            // Sem documento nem contato na trilha: é dado pessoal, e a auditoria é append-only
            // (mesma razão de RN-CLI-007). O vínculo e a comissão entram porque são a decisão
            // administrativa que alguém pode precisar justificar depois.
            auditoria.registrar(RegistroDeAuditoria.criacao("profissional", id,
                    Map.of("vinculo", dados.vinculo().name(),
                            // Nulo é "usa a cascata" (RT-FIN-002), e a trilha precisa
                            // dizer isso — não pode virar "0" no registro.
                            "comissao", String.valueOf(comissao))));
            log.info("Profissional cadastrado: {} ({})", id, dados.vinculo());

            return profissionais.porId(id).orElseThrow();
        } catch (DuplicateKeyException e) {
            // Diferente do telefone do cliente (RN-CLI-005), aqui a unicidade é real: duas fichas
            // com o mesmo CNPJ dividiriam a comissão de um só em dois extratos.
            throw new ErroDeDominio(ErrosDaEquipe.DOCUMENTO_JA_CADASTRADO,
                    "já existe um profissional com este documento neste salão");
        }
    }


    private String texto(String bruto) {
        if (bruto == null) {
            return null;
        }
        String limpo = bruto.strip().replaceAll("\\s{2,}", " ");
        return limpo.isEmpty() ? null : limpo;
    }

    private Documento documentoDe(String informado) {
        try {
            return Documento.de(informado);
        } catch (DocumentoInvalidoException e) {
            throw new ErroDeDominio(ErrosDaEquipe.DOCUMENTO_INVALIDO, e.getMessage(), Map.of(),
                    List.of(new CampoInvalido("documento", "INVALIDO", e.getMessage())));
        }
    }

    /** RN-EQP-002 — o vínculo determina o tipo de documento, e é aqui que os dois se conferem. */
    private void exigirDocumentoCompativel(Documento documento, TipoDeVinculo vinculo) {
        if (vinculo.exigePessoaJuridica() != documento.ehPessoaJuridica()) {
            String esperado = vinculo.exigePessoaJuridica() ? "CNPJ" : "CPF";
            throw new ErroDeDominio(ErrosDaEquipe.DOCUMENTO_NAO_COMBINA_COM_VINCULO,
                    vinculo + " exige " + esperado, Map.of("esperado", esperado),
                    List.of(new CampoInvalido("documento", "INCOMPATIVEL",
                            "Para " + vinculo.name().toLowerCase() + ", informe " + esperado)));
        }
    }
}
