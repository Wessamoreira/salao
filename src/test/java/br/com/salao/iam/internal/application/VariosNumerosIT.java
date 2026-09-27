package br.com.salao.iam.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.salao.iam.api.Identificacao;
import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.ResultadoDeAutenticacao;
import br.com.salao.iam.api.UsuarioEfetivo;
import br.com.salao.iam.api.UsuarioEfetivoApi;
import br.com.salao.iam.api.UsuarioResumo;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.AbstractPostgresIT;
import br.com.salao.shared.tenant.TenantContext;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * RT-IAM-010 — dois números por pessoa: o celular pessoal e o do balcão.
 *
 * <p>Classe à parte de {@code UsuarioEfetivoIT}, que prova o que um telefone concede. Aqui o
 * assunto é a QUANTIDADE e a lista: quem o agente reconhece, quantas vagas existem e o que a trilha
 * guarda quando a lista muda.
 *
 * <p>Sem {@code @WithMockUser}: {@code emNomeDe} recusa rodar com alguém já autenticado no contexto
 * (RN-IAM-019), e a gestão roda dentro de {@link #comoGestor}.
 */
class VariosNumerosIT extends AbstractPostgresIT {

    private static final String SENHA = "senha-bem-comprida-1";

    @Autowired private ProvisionarEstabelecimentoUseCase provisionar;
    @Autowired private AutenticarUseCase autenticar;
    @Autowired private CriarUsuarioUseCase criar;
    @Autowired private ListarUsuariosUseCase listar;
    @Autowired private DefinirTelefonesDoUsuarioUseCase definirTelefones;
    @Autowired private UsuarioEfetivoApi usuariosEfetivos;

    private record Salao(UUID tenant, UUID adminId) {
    }

    @AfterEach
    void limparContexto() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ o que a rotina promete

    @Test
    @DisplayName("a pessoa é reconhecida pelos dois números — o pessoal e o do balcão")
    void reconhecida_pelos_dois() {
        Salao salao = salao();
        UUID bia = pessoa(salao, "Bia", Perfil.RECEPCAO);
        ligar(salao, bia, "11988887777", "1133334444");

        var pelosDois = List.of(identificar(salao, "5511988887777"),
                identificar(salao, "551133334444"));

        assertThat(pelosDois).allSatisfy(i -> assertThat(i)
                .isInstanceOfSatisfying(Identificacao.Identificado.class,
                        ok -> assertThat(ok.usuario()).extracting(UsuarioEfetivo::usuarioId)
                                .isEqualTo(bia)));
    }

    @Test
    @DisplayName("cada linha traz os números DELA, na ordem em que foram ligados")
    void lista_mostra_os_dois() {
        // Duas pessoas com número, de propósito: com uma só, uma consulta que ignorasse o dono
        // devolveria exatamente a mesma lista, e o teste não veria diferença. Num salão de cinco
        // pessoas isso seria o telefone de todas em todas as linhas.
        Salao salao = salao();
        UUID bia = pessoa(salao, "Bia", Perfil.RECEPCAO);
        UUID carla = pessoa(salao, "Carla", Perfil.RECEPCAO);
        ligar(salao, bia, "11988887777", "1133334444");
        ligar(salao, carla, "11955554444");

        var lista = TenantContext.obter(salao.tenant(), () -> comoGestor(() -> listar.executar()));

        assertThat(lista).filteredOn(u -> u.id().equals(bia))
                .as("a pessoa aparece UMA vez, com os dois números dela")
                .singleElement().extracting(UsuarioResumo::telefones)
                .isEqualTo(List.of("5511988887777", "551133334444"));
        assertThat(lista).filteredOn(u -> u.id().equals(carla))
                .as("e o número de uma não aparece na linha da outra")
                .singleElement().extracting(UsuarioResumo::telefones)
                .isEqualTo(List.of("5511955554444"));
    }

    @Test
    @DisplayName("lista vazia tira os números: a conta deixa de falar com o agente")
    void lista_vazia_desliga() {
        Salao salao = salao();
        UUID bia = pessoa(salao, "Bia", Perfil.RECEPCAO);
        ligar(salao, bia, "11988887777", "1133334444");

        ligar(salao, bia);

        assertThat(identificar(salao, "5511988887777"))
                .isInstanceOf(Identificacao.NaoIdentificado.class);
        assertThat(identificar(salao, "551133334444"))
                .isInstanceOf(Identificacao.NaoIdentificado.class);
    }

    // ------------------------------------------------------------------ o teto, e quem o garante

    @Test
    @DisplayName("o terceiro número é impossível: a vaga é forma no banco, não contagem no código")
    void terceiro_numero_recusado() throws SQLException {
        Salao salao = salao();
        UUID bia = pessoa(salao, "Bia", Perfil.RECEPCAO);
        ligar(salao, bia, "11988887777", "1133334444");

        // Pelo caso de uso não há como pedir três — o teste vai ao banco de propósito: a regra tem
        // de valer para quem chegar por baixo, que é o ponto de ela ser forma e não `if`.
        Throwable pelaBorda = catchThrowable(() -> {
            try (var ps = comoOwner().prepareStatement("""
                    insert into telefone_do_usuario
                        (estabelecimento_id, usuario_id, telefone, posicao)
                    values (?, ?, '5511911112222', 3)""")) {
                ps.setObject(1, salao.tenant());
                ps.setObject(2, bia);
                ps.executeUpdate();
            }
        });

        assertThat(pelaBorda).as("o check da vaga recusa a terceira posição").isNotNull();
    }

    @Test
    @DisplayName("tirar o primeiro e pôr outro reaproveita a vaga que ficou livre")
    void vaga_livre_e_reaproveitada() {
        Salao salao = salao();
        UUID bia = pessoa(salao, "Bia", Perfil.RECEPCAO);
        ligar(salao, bia, "11988887777", "1133334444");

        ligar(salao, bia, "1133334444", "11955554444");

        assertThat(identificar(salao, "5511988887777")).as("o antigo deixou de valer")
                .isInstanceOf(Identificacao.NaoIdentificado.class);
        assertThat(identificar(salao, "5511955554444"))
                .isInstanceOf(Identificacao.Identificado.class);
        assertThat(identificar(salao, "551133334444")).as("o que ficou continua valendo")
                .isInstanceOf(Identificacao.Identificado.class);
    }

    // ------------------------------------------------------------------ o que é recusado

    @Test
    @DisplayName("um número de outra conta é recusado, nomeando o colega")
    void numero_de_outra_conta() {
        Salao salao = salao();
        UUID bia = pessoa(salao, "Bia", Perfil.RECEPCAO);
        UUID carla = pessoa(salao, "Carla", Perfil.RECEPCAO);
        ligar(salao, bia, "11988887777");

        Throwable erro = catchThrowable(() -> ligar(salao, carla, "11988887777"));

        assertThat(erro).isInstanceOf(ErroDeDominio.class)
                .hasMessageContaining("Bia");
        assertThat(identificar(salao, "5511988887777")).as("nada mudou de dono")
                .isInstanceOfSatisfying(Identificacao.Identificado.class,
                        ok -> assertThat(ok.usuario().usuarioId()).isEqualTo(bia));
    }

    @Test
    @DisplayName("um dono por número é do BANCO: quem chega por baixo também esbarra")
    void um_dono_por_numero_no_banco() throws SQLException {
        // Par do teste acima: lá o caso de uso recusa e nomeia o colega, o que é orientação. Aqui a
        // pergunta é outra — se o `if` sumisse, o banco ainda recusaria? Sem esta prova, o índice
        // único existe e nada garante que continua existindo.
        Salao salao = salao();
        UUID bia = pessoa(salao, "Bia", Perfil.RECEPCAO);
        UUID carla = pessoa(salao, "Carla", Perfil.RECEPCAO);
        ligar(salao, bia, "11988887777");

        Throwable pelaBorda = catchThrowable(() -> {
            try (var ps = comoOwner().prepareStatement("""
                    insert into telefone_do_usuario
                        (estabelecimento_id, usuario_id, telefone, posicao)
                    values (?, ?, '5511988887777', 1)""")) {
                ps.setObject(1, salao.tenant());
                ps.setObject(2, carla);
                ps.executeUpdate();
            }
        });

        assertThat(pelaBorda).as("o índice único recusa o segundo dono").isNotNull();
    }

    @Test
    @DisplayName("só dígitos é do BANCO: o número com máscara não entra nem por baixo")
    void so_digitos_no_banco() throws SQLException {
        // Pelo caso de uso nunca chega máscara — `Telefone` normaliza antes. É justamente por isso
        // que o check precisa de prova própria: a comparação com o remetente do WhatsApp é literal,
        // e um número com máscara no banco nunca casaria. A recepcionista seria tratada como
        // desconhecida, sem erro nenhum, indistinguível de um número certo olhando o cadastro.
        Salao salao = salao();
        UUID bia = pessoa(salao, "Bia", Perfil.RECEPCAO);

        Throwable pelaBorda = catchThrowable(() -> {
            try (var ps = comoOwner().prepareStatement("""
                    insert into telefone_do_usuario
                        (estabelecimento_id, usuario_id, telefone, posicao)
                    values (?, ?, '(11) 98888-7777', 1)""")) {
                ps.setObject(1, salao.tenant());
                ps.setObject(2, bia);
                ps.executeUpdate();
            }
        });

        assertThat(pelaBorda).as("o check recusa o que não é dígito").isNotNull();
    }

    @Test
    @DisplayName("o mesmo número duas vezes no mesmo pedido não gasta as duas vagas em silêncio")
    void repetido_no_mesmo_pedido() {
        Salao salao = salao();
        UUID bia = pessoa(salao, "Bia", Perfil.RECEPCAO);

        Throwable erro = catchThrowable(() -> ligar(salao, bia, "11988887777", "(11) 98888-7777"));

        assertThat(erro).isInstanceOf(ErroDeDominio.class)
                .hasMessageContaining("duas vezes");
    }

    @Test
    @DisplayName("sem usuario:manage não se liga número nenhum — é gesto de permissão")
    void sem_permissao() {
        Salao salao = salao();
        UUID bia = pessoa(salao, "Bia", Perfil.RECEPCAO);

        Throwable erro = catchThrowable(() -> TenantContext.executar(salao.tenant(),
                () -> comAutoridades(() -> {
                    definirTelefones.executar(bia, List.of("11988887777"));
                    return null;
                }, Permissao.AGENDA_ESCREVER_TODAS, Permissao.CLIENTE_ESCREVER)));

        assertThat(erro).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("um salão não vê o número do outro: o mesmo WhatsApp em dois lugares")
    void isolamento() {
        Salao primeiro = salao();
        Salao segundo = salao();
        UUID bia = pessoa(primeiro, "Bia", Perfil.RECEPCAO);
        UUID ana = pessoa(segundo, "Ana", Perfil.RECEPCAO);
        ligar(primeiro, bia, "11988887777", "1133334444");
        ligar(segundo, ana, "11988887777");

        assertThat(identificar(primeiro, "5511988887777"))
                .isInstanceOfSatisfying(Identificacao.Identificado.class,
                        ok -> assertThat(ok.usuario().usuarioId()).isEqualTo(bia));
        assertThat(identificar(segundo, "5511988887777"))
                .isInstanceOfSatisfying(Identificacao.Identificado.class,
                        ok -> assertThat(ok.usuario().usuarioId()).isEqualTo(ana));
        assertThat(identificar(segundo, "551133334444")).as("o segundo número não atravessou")
                .isInstanceOf(Identificacao.NaoIdentificado.class);
    }

    // ------------------------------------------------------------------ a trilha

    @Test
    @DisplayName("a trilha guarda as duas listas, inteiras — é a pergunta que ela existe para responder")
    void auditoria_das_listas() throws SQLException {
        Salao salao = salao();
        UUID bia = pessoa(salao, "Bia", Perfil.RECEPCAO);
        ligar(salao, bia, "11988887777");

        ligar(salao, bia, "11988887777", "1133334444");

        var conexao = comoApp(salao.tenant());
        try (var ps = conexao.prepareStatement("""
                select antes->>'telefones' as antes, depois->>'telefones' as depois
                  from auditoria where acao = 'USUARIO_TELEFONE_DEFINIDO' and entidade_id = ?
                 order by id desc limit 1
                """)) {
            ps.setObject(1, bia);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("antes")).isEqualTo("[\"5511988887777\"]");
                assertThat(rs.getString("depois"))
                        .isEqualTo("[\"5511988887777\", \"551133334444\"]");
            }
        } finally {
            conexao.rollback();
        }
    }

    // ------------------------------------------------------------------ apoio

    private Salao salao() {
        String email = "dona-" + UUID.randomUUID() + "@salao.test";
        UUID tenant = provisionar.executar(ProvisionarEstabelecimentoCommand.comPadroes(
                "Salão " + UUID.randomUUID(), null, "Dona", email, SENHA));
        UUID admin = ((ResultadoDeAutenticacao.Autenticado)
                autenticar.executar(new AutenticarCommand(email, SENHA)))
                .sessao().acesso().usuarioId();
        return new Salao(tenant, admin);
    }

    private UUID pessoa(Salao salao, String nome, Perfil perfil) {
        return TenantContext.obter(salao.tenant(), () -> comoGestor(() ->
                criar.executar(nome, nome.toLowerCase() + "-" + UUID.randomUUID() + "@salao.test",
                        SENHA, perfil)));
    }

    /** A lista inteira, como a tela a manda. Sem argumento nenhum, desliga a conta do agente. */
    private void ligar(Salao salao, UUID usuarioId, String... telefones) {
        TenantContext.executar(salao.tenant(), () -> comoGestor(() -> {
            definirTelefones.executar(usuarioId, List.of(telefones));
            return null;
        }));
    }

    private Identificacao identificar(Salao salao, String telefone) {
        return TenantContext.obter(salao.tenant(),
                () -> usuariosEfetivos.identificarPorTelefone(telefone));
    }

    private <T> T comoGestor(Supplier<T> acao) {
        return comAutoridades(acao, Permissao.USUARIO_GERENCIAR);
    }

    private <T> T comAutoridades(Supplier<T> acao, String... autoridades) {
        var anterior = SecurityContextHolder.getContext();
        var contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alguem", null,
                Arrays.stream(autoridades).map(SimpleGrantedAuthority::new).toList()));
        SecurityContextHolder.setContext(contexto);
        try {
            return acao.get();
        } finally {
            SecurityContextHolder.setContext(anterior);
        }
    }
}
