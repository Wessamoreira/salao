package br.com.salao.conversacional.internal.application;

import br.com.salao.iam.api.UsuarioEfetivo;
import br.com.salao.shared.agente.Ferramenta;
import br.com.salao.shared.agente.FerramentaDoBalcao;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * RT-CNV-004 / RN-CNV-010 — quais ferramentas existem, e quais este usuário pode usar.
 *
 * <h2>A lista permitida é calculada no servidor, das permissões</h2>
 *
 * <p>docs/05: "a defesa é a allowlist de tools resolvida no servidor pelo perfil do usuário efetivo —
 * nunca uma instrução no system prompt". Uma ferramenta é oferecida quando o usuário tem TODAS as
 * permissões que ela declara. Ferramenta sem permissão declarada nunca é oferecida: quem não disse o
 * que exige é tratado como perigoso, não como livre.
 *
 * <p>Desde RT-CNV-005 a de escrita também é oferecida — e o que o modelo alcança dela é só a
 * SIMULAÇÃO. O caminho até a gravação não passa pelo modelo (RN-CNV-014).
 *
 * <h2>Falha na subida, não na conversa</h2>
 *
 * <p>Nome repetido ou fora do formato derruba a aplicação ao iniciar. Dois beans com o mesmo nome
 * fariam o modelo pedir um e o sistema executar o outro — sem erro nenhum.
 */
public class CatalogoDeFerramentas {

    private static final Pattern NOME = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    private final Map<String, Ferramenta> porNome = new LinkedHashMap<>();

    public CatalogoDeFerramentas(List<Ferramenta> ferramentas) {
        for (Ferramenta ferramenta : ferramentas.stream()
                .sorted(Comparator.comparing(Ferramenta::nome)).toList()) {
            if (ferramenta.nome() == null || !NOME.matcher(ferramenta.nome()).matches()) {
                throw new IllegalStateException(
                        "ferramenta com nome inválido: " + ferramenta.nome());
            }
            if (porNome.putIfAbsent(ferramenta.nome(), ferramenta) != null) {
                throw new IllegalStateException(
                        "duas ferramentas com o nome " + ferramenta.nome());
            }
        }
    }

    public Optional<Ferramenta> porNome(String nome) {
        return Optional.ofNullable(nome == null ? null : porNome.get(nome));
    }

    /**
     * RT-CNV-010 — dois catálogos, e o compilador obriga a dizer qual.
     *
     * <p>À equipe, o que as permissões dela liberam — e nunca uma ferramenta do balcão. À visitante,
     * só as do balcão — e nunca uma da equipe. Os dois filtros são explícitos de propósito: o
     * segundo já seria garantido por {@code permissoesExigidas()} vazio, e o primeiro por a
     * visitante não ter permissão nenhuma, mas depender disso faria a separação ser consequência de
     * outra regra em vez de decisão desta.
     */
    public List<Ferramenta> permitidasPara(QuemFala quem) {
        return switch (quem) {
            case QuemFala.DaEquipe(UsuarioEfetivo usuario) -> porNome.values().stream()
                    .filter(f -> !(f instanceof FerramentaDoBalcao))
                    .filter(f -> podeUsar(usuario, f))
                    .toList();
            case QuemFala.Visitante() -> porNome.values().stream()
                    .filter(FerramentaDoBalcao.class::isInstance)
                    .toList();
        };
    }

    /**
     * A primeira tranca, numa pergunta só — a oferta e a confirmação (RT-CNV-005) perguntam aqui, e
     * não cada uma do seu jeito.
     */
    public static boolean podeUsar(UsuarioEfetivo usuario, Ferramenta ferramenta) {
        return ferramenta.permissoesExigidas() != null
                && !ferramenta.permissoesExigidas().isEmpty()
                && usuario.permissoes().containsAll(ferramenta.permissoesExigidas());
    }
}
