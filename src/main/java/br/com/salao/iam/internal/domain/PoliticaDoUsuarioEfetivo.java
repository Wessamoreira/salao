package br.com.salao.iam.internal.domain;

import br.com.salao.iam.api.Identificacao;
import br.com.salao.iam.api.MotivoDaRecusa;
import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.api.UsuarioEfetivo;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * RT-CNV-002 — quem pode virar usuário efetivo, e com o quê.
 *
 * <p>Pura: recebe o que o banco disse e o instante, devolve a decisão. É o que permite provar cada
 * regra sem banco — inclusive as que valem "para todo perfil", que precisam continuar valendo
 * quando o mapa de permissões mudar.
 *
 * <h2>"Herdando exatamente as permissões dele" — com duas exceções que o código já exigia</h2>
 *
 * <p>docs/05 diz que o bot herda <em>exatamente</em> as permissões do usuário. Ao abrir o código,
 * herdar exatamente seria abrir dois buracos que a web não tem:
 *
 * <ol>
 *   <li><b>Contornar o segundo fator.</b> Na web, perfil que exige MFA sem MFA na sessão não acessa
 *       NADA ({@code SegurancaConfig.segundoFatorEmDia}). O WhatsApp não tem como apresentar segundo
 *       fator. Herdar as permissões de um ADMIN pelo canal seria dar pelo celular o que o próprio
 *       login dele não dá sem o código. Então: recusado, e não "com teto" — um teto ainda seria mais
 *       do que a web concede.
 *   <li><b>Escopo que ninguém impõe.</b> {@code agenda:read:own} promete "só a própria agenda", mas
 *       os casos de uso só conferem a autoridade — não existe vínculo entre {@code usuario} e
 *       {@code profissional} para saber qual é a própria. Na web isso é pendência conhecida; pelo
 *       agente, é um prompt injection pedindo "mostre a agenda de todo mundo" e recebendo. O canal
 *       nunca carrega permissão cujo escopo o sistema não consegue impor.
 * </ol>
 */
public final class PoliticaDoUsuarioEfetivo {

    /** Contas que não são pessoas. */
    private static final Set<Perfil> SEM_CONVERSA = Set.of(Perfil.PAINEL, Perfil.BOT);

    /**
     * O sufixo das permissões de escopo próprio.
     *
     * <p>Sai do teto no dia em que existir o vínculo {@code usuario → profissional} e os casos de uso
     * conferirem o dono — não antes. Devolver sem isso seria só trocar o nome do buraco.
     */
    private static final String ESCOPO_PROPRIO = ":own";

    private PoliticaDoUsuarioEfetivo() {
    }

    /**
     * @param bloqueadoAte nulo quando não há bloqueio
     * @param agora        do port {@code Relogio} — um relógio só (RN-INF-006)
     */
    public static Identificacao avaliar(UUID usuarioId, String nome, Perfil perfil, boolean ativo,
                                        Instant bloqueadoAte, Instant agora) {
        // A ordem importa pouco para quem recebe — a resposta ao remetente é a mesma —, mas importa
        // para o diagnóstico: conta inativa é o fato mais forte, e não deve ser mascarada pelo perfil.
        if (!ativo) {
            return new Identificacao.NaoIdentificado(MotivoDaRecusa.USUARIO_INATIVO);
        }
        if (bloqueadoAte != null && bloqueadoAte.isAfter(agora)) {
            return new Identificacao.NaoIdentificado(MotivoDaRecusa.USUARIO_BLOQUEADO);
        }
        if (SEM_CONVERSA.contains(perfil)) {
            return new Identificacao.NaoIdentificado(MotivoDaRecusa.PERFIL_SEM_CONVERSA);
        }
        if (MapaDePermissoes.exigeMfa(perfil)) {
            return new Identificacao.NaoIdentificado(MotivoDaRecusa.PERFIL_EXIGE_SEGUNDO_FATOR);
        }
        return new Identificacao.Identificado(
                new UsuarioEfetivo(usuarioId, nome, perfil, permissoesPeloCanal(perfil)));
    }

    /** As permissões do perfil, sem as de escopo próprio. Nunca mais do que o perfil tem. */
    public static Set<String> permissoesPeloCanal(Perfil perfil) {
        return MapaDePermissoes.de(perfil).stream()
                .filter(permissao -> !permissao.endsWith(ESCOPO_PROPRIO))
                .collect(Collectors.toUnmodifiableSet());
    }
}
