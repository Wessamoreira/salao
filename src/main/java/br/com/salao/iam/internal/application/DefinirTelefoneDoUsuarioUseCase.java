package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.ConfiguracaoDoEstabelecimento;
import br.com.salao.iam.api.ErrosDoIam;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.iam.internal.infra.UsuariosJdbc;
import br.com.salao.shared.erro.CampoInvalido;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.erro.ErrosDaInfra;
import br.com.salao.shared.telefone.Telefone;
import br.com.salao.shared.telefone.TelefoneInvalidoException;
import br.com.salao.shared.tenant.TenantContext;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CNV-002 — o telefone pelo qual o agente reconhece esta pessoa.
 *
 * <h2>É um gesto de permissão, e por isso é {@code usuario:manage}</h2>
 *
 * <p>Parece cadastro, e não é só isso: ligar um telefone a uma conta dá a quem escreve daquele
 * número as permissões daquela conta pelo agente. Apontar o número errado para a recepção entrega
 * a agenda do salão a um estranho. Por isso a mesma permissão de criar usuário e mudar perfil — e
 * não "qualquer um edita o próprio telefone".
 *
 * <p>A auditoria guarda o número inteiro, e não mascarado: esta trilha existe para responder "quem
 * apontou este telefone para esta conta, e qual era o anterior". Com o número mascarado, ela não
 * responde a única pergunta que tem.
 */
public class DefinirTelefoneDoUsuarioUseCase {

    private final UsuariosJdbc usuarios;
    private final EstabelecimentoApi estabelecimentos;
    private final AuditoriaApi auditoria;

    public DefinirTelefoneDoUsuarioUseCase(UsuariosJdbc usuarios,
                                           EstabelecimentoApi estabelecimentos,
                                           AuditoriaApi auditoria) {
        this.usuarios = usuarios;
        this.estabelecimentos = estabelecimentos;
        this.auditoria = auditoria;
    }

    /** @param informado em qualquer formato; vazio remove o telefone da conta */
    @PreAuthorize("hasAuthority('" + Permissao.USUARIO_GERENCIAR + "')")
    @Transactional
    public void executar(UUID usuarioId, String informado) {
        UUID tenant = TenantContext.obrigatorio();
        var atual = usuarios.porId(usuarioId).orElseThrow(() ->
                new ErroDeDominio(ErrosDaInfra.NAO_ENCONTRADO, "Usuário não encontrado."));

        String telefone = normalizar(informado, tenant);

        // A checagem vem ANTES, e o `catch` fica sem consulta: depois de violar constraint o
        // Postgres aborta a transação, e buscar o dono ali falharia com 25P02 (lição de RT-EST-009).
        // Quem garante é o índice único da V51; esta consulta existe para a mensagem nomear o
        // colega — dentro do mesmo salão, onde isso não é vazamento, é orientação.
        if (telefone != null) {
            usuarios.porTelefone(telefone)
                    .filter(dono -> !dono.id().equals(usuarioId))
                    .ifPresent(dono -> {
                        throw new ErroDeDominio(ErrosDoIam.TELEFONE_EM_USO,
                                "Este telefone já está na conta de " + dono.nome() + ".");
                    });
        }
        try {
            usuarios.definirTelefone(usuarioId, telefone);
        } catch (DuplicateKeyException e) {
            throw new ErroDeDominio(ErrosDoIam.TELEFONE_EM_USO,
                    "Este telefone acabou de ser ligado a outra conta.");
        }

        // `singletonMap` e não `Map.of`: remover o telefone grava nulo, e a trilha precisa
        // distinguir "não tinha" de "foi removido".
        auditoria.registrar(RegistroDeAuditoria.alteracao("USUARIO_TELEFONE_DEFINIDO", "usuario",
                usuarioId, Collections.singletonMap("telefone", atual.telefone()),
                Collections.singletonMap("telefone", telefone)));
    }

    private String normalizar(String informado, UUID tenant) {
        if (informado == null || informado.isBlank()) {
            return null;
        }
        ConfiguracaoDoEstabelecimento config = estabelecimentos.configuracao(tenant).orElseThrow();
        try {
            return Telefone.de(informado, config.paisPadrao(), config.dddPadrao()).e164();
        } catch (TelefoneInvalidoException e) {
            throw new ErroDeDominio(ErrosDoIam.TELEFONE_INVALIDO, e.getMessage(), Map.of(),
                    List.of(new CampoInvalido("telefone", "INVALIDO", e.getMessage())));
        }
    }
}
