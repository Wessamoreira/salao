package br.com.salao.cliente.internal.application;

import br.com.salao.cliente.api.ErrosDoCliente;
import br.com.salao.cliente.internal.domain.Nomes;
import br.com.salao.cliente.internal.infra.ClientesJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.CampoInvalido;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.UsuarioAtual;
import br.com.salao.shared.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CLI-001 — cadastrar cliente.
 *
 * <h2>Um caminho só, não dois</h2>
 *
 * <p>O cadastro rápido (nome + telefone, R-UX-04) e o cadastro completo são <strong>o mesmo</strong>
 * caso de uso, porque a diferença entre eles é só quantos campos vieram preenchidos. Dois caminhos
 * separados divergiriam na primeira regra nova — e a regra acabaria valendo só no completo,
 * justamente o menos usado.
 *
 * <h2>Telefone é opcional, e isso é decisão de negócio</h2>
 *
 * <p>Cliente que passa e pergunta o preço, criança que vem com a mãe, a senhora que não tem
 * celular: existem e precisam de ficha. Exigir telefone faria a recepção digitar
 * {@code 00000-0000}, que é pior que o vazio — parece dado e não é.
 */
public class CadastrarClienteUseCase {

    private static final Logger log = LoggerFactory.getLogger(CadastrarClienteUseCase.class);

    private final ClientesJdbc clientes;
    private final AuditoriaApi auditoria;
    private final NormalizadorDeTelefone telefones;

    public CadastrarClienteUseCase(ClientesJdbc clientes, EstabelecimentoApi estabelecimentos,
                                   AuditoriaApi auditoria) {
        this.clientes = clientes;
        this.auditoria = auditoria;
        this.telefones = new NormalizadorDeTelefone(estabelecimentos);
    }

    @PreAuthorize("hasAuthority('" + Permissao.CLIENTE_ESCREVER + "')")
    @Transactional
    public UUID executar(DadosDoCliente dados) {
        UUID tenant = TenantContext.obrigatorio();

        String nome = Nomes.opcional(dados.nome());
        if (nome == null) {
            throw new ErroDeDominio(ErrosDoCliente.NOME_OBRIGATORIO,
                    "o nome do cliente é obrigatório", Map.of(),
                    List.of(new CampoInvalido("nome", "OBRIGATORIO", "Informe o nome")));
        }

        String telefone = telefones.normalizar(dados.telefone(), tenant);

        UUID id = clientes.inserir(tenant, nome, Nomes.opcional(dados.apelido()), telefone,
                Nomes.opcional(dados.email()), Nomes.opcional(dados.observacao()),
                UsuarioAtual.idOuNulo());

        // Sem nenhum dado pessoal: `entidade_id` já diz de quem se trata, e a trilha responde
        // "quem fez o quê". Ver RN-CLI-007.
        auditoria.registrar(RegistroDeAuditoria.criacao("cliente", id, Map.of()));
        log.info("Cliente cadastrado: {}", id);
        return id;
    }

}
