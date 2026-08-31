package br.com.salao.cliente.internal.application;

import br.com.salao.cliente.api.ClienteResumo;
import br.com.salao.cliente.api.ErrosDoCliente;
import br.com.salao.cliente.internal.domain.Nomes;
import br.com.salao.cliente.internal.infra.ClientesJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.CampoInvalido;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.erro.ErrosDaInfra;
import br.com.salao.shared.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CLI-007 — corrigir a ficha.
 *
 * <h2>Por que existe</h2>
 *
 * <p>Faltava. As seis rotinas anteriores sabiam cadastrar, achar, juntar, consentir e anonimizar —
 * e não sabiam consertar um telefone digitado errado, que é o erro mais comum do balcão. A lacuna
 * só ficou evidente quando a tela (RT-CLI-006) precisou de um botão que não existia.
 *
 * <h2>Substitui os campos, não os mescla</h2>
 *
 * <p>Recebe todos os campos editáveis, sempre. Distinguir "veio nulo" de "não veio" em JSON exige
 * envelope ou {@code Optional} e é fonte conhecida de bug — e a tela manda o formulário inteiro de
 * qualquer jeito. Campo apagado é campo vazio, dito explicitamente.
 */
public class AtualizarClienteUseCase {

    private static final Logger log = LoggerFactory.getLogger(AtualizarClienteUseCase.class);

    private final ClientesJdbc clientes;
    private final EstabelecimentoApi estabelecimentos;
    private final AuditoriaApi auditoria;
    private final NormalizadorDeTelefone telefones;

    public AtualizarClienteUseCase(ClientesJdbc clientes, EstabelecimentoApi estabelecimentos,
                                   AuditoriaApi auditoria) {
        this.clientes = clientes;
        this.estabelecimentos = estabelecimentos;
        this.auditoria = auditoria;
        this.telefones = new NormalizadorDeTelefone(estabelecimentos);
    }

    @PreAuthorize("hasAuthority('" + Permissao.CLIENTE_ESCREVER + "')")
    @Transactional
    public ClienteResumo executar(UUID id, int versao, DadosDoCliente dados) {
        ClienteResumo antes = clientes.porId(id).orElseThrow(() -> new ErroDeDominio(
                ErrosDoCliente.CLIENTE_NAO_ENCONTRADO, "cliente " + id + " não encontrado"));

        String nome = Nomes.opcional(dados.nome());
        if (nome == null) {
            throw new ErroDeDominio(ErrosDoCliente.NOME_OBRIGATORIO,
                    "o nome do cliente é obrigatório", Map.of(),
                    List.of(new CampoInvalido("nome", "OBRIGATORIO", "Informe o nome")));
        }
        String telefone = telefones.normalizar(dados.telefone(), TenantContext.obrigatorio());

        int alteradas = clientes.atualizar(id, versao, nome, Nomes.opcional(dados.apelido()),
                telefone, Nomes.opcional(dados.email()), Nomes.opcional(dados.observacao()));

        if (alteradas == 0) {
            // A ficha existe (a leitura acima achou), então o que não bate é a versão: alguém
            // salvou entre a abertura da tela e este clique.
            throw new ErroDeDominio(ErrosDaInfra.VERSAO_DESATUALIZADA,
                    "a ficha foi alterada por outra pessoa; recarregue antes de salvar");
        }

        // Quais campos mudaram, nunca os valores. A trilha é append-only e a anonimização não a
        // alcança (RN-CLI-007): guardar aqui o telefone antigo criaria justamente a segunda base
        // de dado pessoal que a rotina de anonimização não sabe limpar.
        auditoria.registrar(RegistroDeAuditoria.alteracao("CLIENTE_ALTERADO", "cliente", id,
                null, Map.of("campos", camposAlterados(antes, nome, dados, telefone))));
        log.info("Cliente {} alterado", id);

        return clientes.porId(id).orElseThrow();
    }

    private String camposAlterados(ClienteResumo antes, String nome, DadosDoCliente dados,
                                   String telefone) {
        var mudou = new TreeSet<String>();
        if (!nome.equals(antes.nome())) {
            mudou.add("nome");
        }
        if (diferente(Nomes.opcional(dados.apelido()), antes.apelido())) {
            mudou.add("apelido");
        }
        if (diferente(telefone, antes.telefone())) {
            mudou.add("telefone");
        }
        if (diferente(Nomes.opcional(dados.email()), antes.email())) {
            mudou.add("email");
        }
        return String.join(",", mudou);
    }

    private boolean diferente(String novo, String antigo) {
        return novo == null ? antigo != null : !novo.equals(antigo);
    }
}
