package br.com.salao.shared.seguranca;

import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * RT-CLI-001 — quem está agindo agora, resolvido do contexto de segurança.
 *
 * <p>Extraído de {@code AuditoriaJdbc} quando o segundo lugar precisou da mesma resposta (o
 * {@code criado_por} do cliente). Duas implementações da pergunta "quem é o usuário atual"
 * divergiriam na primeira mudança — e a divergência apareceria numa trilha de auditoria dizendo
 * uma coisa e numa coluna de autoria dizendo outra, meses depois, sem quem conciliar.
 *
 * <p>Devolve {@code null} sem usuário: job agendado, migração e consumidor de evento agem
 * legitimamente sem ninguém logado. Quem precisa de um usuário obrigatoriamente é quem checa —
 * silenciar isso aqui esconderia o caso do job.
 */
public final class UsuarioAtual {

    private UsuarioAtual() {
    }

    public static UUID idOuNulo() {
        var autenticacao = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacao != null && autenticacao.getPrincipal() instanceof Jwt jwt) {
            try {
                return UUID.fromString(jwt.getSubject());
            } catch (IllegalArgumentException e) {
                // Subject que não é UUID vem de token de escopo diferente (ex.: desafio de MFA).
                // Não é autoria de pessoa, e forçar seria gravar lixo na coluna.
                return null;
            }
        }
        return null;
    }
}
