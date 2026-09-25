package br.com.salao.iam.internal;

import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoCommand;
import br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase;
import br.com.salao.iam.internal.domain.Emails;
import br.com.salao.shared.manutencao.ConexaoDeManutencao;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * O primeiro acesso, na máquina de quem desenvolve.
 *
 * <h2>Por que isto existe</h2>
 *
 * Provisionar um estabelecimento é a única operação do sistema sem porta: não tem endpoint (seria
 * uma rota que cria tenant sem autenticação), não tem tela (RT-IAM-009 ainda não existe) e não
 * tem comando de linha. Até 24/09/2026 o único lugar que chamava
 * {@link ProvisionarEstabelecimentoUseCase} era um teste — ou seja, <strong>não havia como entrar
 * no sistema recém-subido</strong> sem escrever Java.
 *
 * <h2>Duas situações, e as duas acontecem</h2>
 *
 * Banco vazio: cria o salão e o administrador. Banco que já tem salão — o caso de quem
 * desenvolve há semanas — ela <strong>não mexe em nada do que existe</strong>: só garante que o
 * acesso de desenvolvimento configurado está lá, criando-o no primeiro estabelecimento se
 * faltar. Nenhuma senha de usuário existente é trocada, e subir duas vezes não cria dois salões
 * nem dois usuários.
 *
 * <h2>O que ela NÃO é</h2>
 *
 * Não é onboarding de cliente. Só roda com o perfil {@code dev}. O caminho de produção continua
 * sendo outro, e é RT-IAM-009 que vai resolvê-lo.
 *
 * <h2>A senha aparece no log</h2>
 *
 * De propósito, e só aqui: é uma credencial de desenvolvimento, gerada a partir de configuração
 * local, num perfil que não existe em produção. Esconder obrigaria a procurá-la no código — e o
 * objetivo desta classe é exatamente que ninguém precise procurar nada.
 */
@Component
@Profile("dev")
class SementeDeDesenvolvimento implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SementeDeDesenvolvimento.class);

    private final ConexaoDeManutencao plataforma;
    private final ProvisionarEstabelecimentoUseCase provisionar;
    private final PasswordEncoder codificadorDeSenha;
    private final String nomeDoSalao;
    private final String email;
    private final String senha;
    private final String emailDaRecepcao;

    SementeDeDesenvolvimento(ConexaoDeManutencao plataforma,
                             ProvisionarEstabelecimentoUseCase provisionar,
                             PasswordEncoder codificadorDeSenha,
                             @Value("${app.semente.salao:Salão de Desenvolvimento}")
                             String nomeDoSalao,
                             @Value("${app.semente.email:dono@salao.local}") String email,
                             @Value("${app.semente.senha:salao-dev-2026}") String senha,
                             @Value("${app.semente.email-recepcao:recepcao@salao.local}")
                             String emailDaRecepcao) {
        this.plataforma = plataforma;
        this.provisionar = provisionar;
        this.codificadorDeSenha = codificadorDeSenha;
        this.nomeDoSalao = nomeDoSalao;
        this.email = email;
        this.senha = senha;
        this.emailDaRecepcao = emailDaRecepcao;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        Optional<UUID> existente = plataforma.jdbc()
                .sql("select id from estabelecimento order by criado_em limit 1")
                .query(UUID.class)
                .optional();

        boolean salaoNovo = existente.isEmpty();
        UUID id = salaoNovo
                ? provisionar.executar(new ProvisionarEstabelecimentoCommand(
                        nomeDoSalao, null, "America/Sao_Paulo", "BRL",
                        null, null, null, "11",
                        "Dono do Salão", email, senha))
                : garantirAcesso(existente.get(), "Dono do Salão", email, "ADMIN");

        /*
         * O segundo acesso existe por causa de uma regra do próprio produto: ADMIN e GERENTE
         * veem o financeiro do salão e por isso o segundo fator é OBRIGATÓRIO — o servidor
         * recusa as requisições deles enquanto o token não trouxer o MFA (RN-IAM-006). Quem só
         * quer abrir a agenda e mexer numa comanda entra por aqui, sem autenticador nenhum.
         */
        garantirAcesso(id, "Recepção", emailDaRecepcao, "RECEPCAO");

        log.info("""

                ┌───────────────────────────────────────────────────────────────
                │ Semente de desenvolvimento: {} ({})
                │
                │   ADMIN     {}
                │   RECEPÇÃO  {}
                │   senha     {}   (as duas)
                │
                │ O ADMIN vê o financeiro, então o segundo fator é obrigatório para
                │ ele: o primeiro login pede para ativar o autenticador. A recepção
                │ entra direto — e alcança agenda, fila, comandas e clientes.
                │ Só existe no perfil dev.
                └───────────────────────────────────────────────────────────────""",
                salaoNovo ? "salão criado" : "acesso garantido no salão que já existia",
                id, email, emailDaRecepcao, senha);
    }

    /**
     * O acesso de desenvolvimento dentro de um salão que já existe.
     *
     * <p>Insere só se faltar. Trocar a senha de um usuário que já está lá seria mexer em dado que
     * não é desta classe — quem esqueceu a própria senha tem outro caminho, e ele não pode ser um
     * runner que roda sozinho a cada subida.
     */
    private UUID garantirAcesso(UUID estabelecimentoId, String nome, String email, String perfil) {
        String normalizado = Emails.normalizar(email);
        boolean existe = plataforma.jdbc()
                .sql("select exists(select 1 from usuario where email_normalizado = :email)")
                .param("email", normalizado)
                .query(Boolean.class)
                .single();
        if (existe) {
            log.info("Semente de desenvolvimento: o acesso {} já existe; nada a fazer.", email);
            return estabelecimentoId;
        }

        plataforma.jdbc().sql("""
                insert into usuario
                    (estabelecimento_id, nome, email, email_normalizado, senha_hash, perfil)
                values (:estabelecimento, :nome, :email, :normalizado, :hash, cast(:perfil as text))
                """)
                .param("estabelecimento", estabelecimentoId)
                .param("nome", nome)
                .param("perfil", perfil)
                .param("email", email.trim())
                .param("normalizado", normalizado)
                .param("hash", codificadorDeSenha.encode(senha))
                .update();
        return estabelecimentoId;
    }
}
