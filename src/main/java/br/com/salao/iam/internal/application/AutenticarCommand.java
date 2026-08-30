package br.com.salao.iam.internal.application;

/** RT-IAM-002 — entrada do login. */
public record AutenticarCommand(String email, String senha) {
}
