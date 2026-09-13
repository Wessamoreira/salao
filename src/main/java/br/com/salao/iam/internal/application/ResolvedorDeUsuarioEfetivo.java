package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.ErrosDoIam;
import br.com.salao.iam.api.Identificacao;
import br.com.salao.iam.api.MotivoDaRecusa;
import br.com.salao.iam.api.UsuarioEfetivo;
import br.com.salao.iam.api.UsuarioEfetivoApi;
import br.com.salao.iam.internal.domain.PoliticaDoUsuarioEfetivo;
import br.com.salao.iam.internal.infra.UsuariosJdbc;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.seguranca.EmNomeDe;
import br.com.salao.shared.tempo.Relogio;
import java.util.Collection;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * RT-CNV-002 — em nome de quem o agente age, e o contexto de segurança em que ele age.
 *
 * <p>Não se chama {@code ...UseCase} de propósito: não há principal para um {@code @PreAuthorize}
 * conferir — ele é justamente quem monta o principal. A tranca aqui é a política
 * ({@link PoliticaDoUsuarioEfetivo}), e a de cada ação continua sendo o {@code @PreAuthorize} do
 * caso de uso que o agente chamar.
 *
 * <h2>Por que o contexto de segurança, e não uma checagem própria</h2>
 *
 * <p>O agente poderia conferir {@link UsuarioEfetivo#pode} antes de cada ação. Seria uma segunda
 * implementação da autorização, e a segunda cópia é a que alguém esquece de atualizar. Montando o
 * {@code Authentication} com as autoridades do usuário efetivo, TODO {@code @PreAuthorize} já escrito
 * no sistema passa a valer para o agente sem mudar uma linha — que é o que RT-IAM-007 previu ao pôr
 * a autorização no caso de uso e não no controller.
 */
public class ResolvedorDeUsuarioEfetivo implements UsuarioEfetivoApi {

    private static final Logger log = LoggerFactory.getLogger(ResolvedorDeUsuarioEfetivo.class);

    private final UsuariosJdbc usuarios;
    private final Relogio relogio;

    public ResolvedorDeUsuarioEfetivo(UsuariosJdbc usuarios, Relogio relogio) {
        this.usuarios = usuarios;
        this.relogio = relogio;
    }

    /**
     * Comparação EXATA, depois de tirar o que não é dígito.
     *
     * <p>Sem aproximação — nem "com ou sem o nono dígito", que o WhatsApp às vezes omite em número
     * brasileiro antigo. Aproximação em identidade que concede permissão é como nasce a
     * personificação: o número parecido é, por definição, outro número. Ver as pendências da rotina.
     */
    @Override
    public Identificacao identificarPorTelefone(String telefone) {
        String digitos = telefone == null ? "" : telefone.replaceAll("\\D", "");
        if (digitos.isEmpty()) {
            return new Identificacao.NaoIdentificado(MotivoDaRecusa.TELEFONE_DESCONHECIDO);
        }
        return usuarios.porTelefone(digitos)
                .map(u -> PoliticaDoUsuarioEfetivo.avaliar(
                        u.id(), u.nome(), u.perfil(), u.ativo(), u.bloqueadoAte(), relogio.agora()))
                .orElseGet(() -> new Identificacao.NaoIdentificado(
                        MotivoDaRecusa.TELEFONE_DESCONHECIDO));
    }

    @Override
    public <T> T emNomeDe(String telefone, Function<UsuarioEfetivo, T> acao) {
        var anterior = SecurityContextHolder.getContext();
        var presente = anterior.getAuthentication();
        /*
         * Ninguém autenticado no contexto — nem pessoa, nem o próprio agente.
         *
         * Pessoa: agir em nome de alguém dentro da requisição de OUTRA pessoa é o confused deputy
         * exato que esta classe existe para impedir, e o resultado seria uma ação com as permissões
         * de uma atribuída à outra. O agente: aninhar um `emNomeDe` dentro de outro trocaria de
         * identidade no meio da ação. Nos dois casos é bug de quem chamou, e falhar alto é melhor.
         */
        if (presente != null && presente.isAuthenticated()
                && !(presente instanceof AnonymousAuthenticationToken)) {
            throw new IllegalStateException(
                    "emNomeDe chamado com alguém já autenticado no contexto de segurança");
        }

        // Resolvido AGORA, e não recebido pronto: quem foi desativado entre a mensagem e a ação não
        // age com as permissões de antes.
        var identificacao = identificarPorTelefone(telefone);
        if (!(identificacao instanceof Identificacao.Identificado(UsuarioEfetivo usuario))) {
            var motivo = ((Identificacao.NaoIdentificado) identificacao).motivo();
            // O motivo vai para o log; o telefone, não — é dado pessoal, e o log sai do perímetro.
            log.warn("Canal recusou agir: motivo={}", motivo);
            throw new ErroDeDominio(ErrosDoIam.CONTATO_NAO_IDENTIFICADO,
                    "Este contato não pode agir por este canal.");
        }

        var contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(new AutenticacaoEmNomeDe(
                new EmNomeDe(usuario.usuarioId()),
                usuario.permissoes().stream()
                        .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p))
                        .toList()));
        SecurityContextHolder.setContext(contexto);
        try {
            return acao.apply(usuario);
        } finally {
            // No `finally`: uma ação que falha não pode deixar a thread com a identidade de alguém.
            // Com virtual threads o carrier é reaproveitado, e o próximo trabalho herdaria o
            // contexto — a pior forma de vazamento, porque só aparece sob carga.
            SecurityContextHolder.setContext(anterior);
        }
    }

    /** O {@code Authentication} do agente: principal {@link EmNomeDe}, autoridades do usuário. */
    private static final class AutenticacaoEmNomeDe extends AbstractAuthenticationToken {

        private final EmNomeDe principal;

        AutenticacaoEmNomeDe(EmNomeDe principal, Collection<GrantedAuthority> autoridades) {
            super(autoridades);
            this.principal = principal;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public Object getPrincipal() {
            return principal;
        }
    }
}
