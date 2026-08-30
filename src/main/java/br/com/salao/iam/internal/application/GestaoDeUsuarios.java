package br.com.salao.iam.internal.application;

import br.com.salao.iam.api.ErrosDoIam;
import br.com.salao.iam.api.Perfil;
import br.com.salao.iam.internal.infra.UsuariosJdbc;
import br.com.salao.shared.erro.ErroDeDominio;
import java.util.UUID;

/**
 * RT-IAM-007 — as duas travas que impedem o salão de se trancar para fora.
 *
 * <p>Compartilhadas entre os casos de uso de gestão, e não copiadas em cada um: uma verificação
 * duplicada é uma verificação que alguém vai esquecer de atualizar. Não é caso de uso — é política
 * de domínio, sem estado e sem transação.
 */
final class GestaoDeUsuarios {

    private GestaoDeUsuarios() {
    }

    /**
     * Rebaixar ou desativar a própria conta é quase sempre engano — e o estrago é imediato, porque
     * quem perceberia o erro é justamente quem acabou de perder o acesso.
     */
    static void recusarSobreSiMesmo(UUID alvo, UUID quemPede) {
        if (alvo.equals(quemPede)) {
            throw new ErroDeDominio(ErrosDoIam.OPERACAO_SOBRE_SI_MESMO,
                    "Peça a outro administrador para fazer isso na sua conta.");
        }
    }

    /**
     * Impede remover o último administrador ativo.
     *
     * <p>Sem esta trava, um salão com dois administradores em que um rebaixa o outro e depois é
     * desativado fica sem ninguém que possa administrá-lo — e a única saída seria alterar o banco
     * à mão. É barato de evitar e caro de consertar.
     */
    static void exigirOutroAdministrador(UsuariosJdbc usuarios, UUID alvo, Perfil perfilAtual) {
        if (perfilAtual == Perfil.ADMIN && usuarios.outrosAdminsAtivos(alvo) == 0) {
            throw new ErroDeDominio(ErrosDoIam.ULTIMO_ADMINISTRADOR,
                    "Promova outro administrador antes de remover este.");
        }
    }
}
