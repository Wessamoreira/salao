package br.com.salao.iam.api;

/**
 * RT-IAM-002 — perfis do sistema.
 *
 * <p>Perfil é um <strong>conjunto nomeado de permissões</strong>, não um {@code if} espalhado.
 * A autorização sempre olha a permissão, nunca o perfil — é o que permite um salão pedir "a
 * recepção também pode dar desconto até 5%" sem tocar em código.
 */
public enum Perfil {
    ADMIN, GERENTE, PROFISSIONAL, RECEPCAO, PAINEL, BOT
}
