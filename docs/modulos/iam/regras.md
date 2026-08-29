# Regras de negócio — módulo `iam`

---

### RN-IAM-001 — Fuso do estabelecimento é identificador IANA, nunca offset fixo

**Enunciado.** O fuso é gravado como identificador IANA (`America/Sao_Paulo`). Offset fixo
(`-03:00`) é rejeitado.

**Motivo.** Offset não conhece horário de verão. Se o Brasil voltar a adotá-lo, um estabelecimento
gravado como `-03:00` teria a agenda inteira deslocada uma hora — e ninguém perceberia até uma
cliente chegar no horário errado.

**Onde é garantida.** Domínio: `NovoEstabelecimento.comFuso`.
**Rotinas.** RT-IAM-001 · **Teste.** `NovoEstabelecimentoTest.recusa_offset_fixo`
**Erro.** `ER-IAM-DADOS_INVALIDOS` (422) · **Configurável?** Não
**Origem.** 2026-08-29

---

### RN-IAM-002 — Provisionar estabelecimento é operação de plataforma, não de tenant

**Enunciado.** Criar um estabelecimento passa exclusivamente pela role `salao_manutencao`. A role
da aplicação não tem `insert` em `estabelecimento`.

**Motivo.** É a única operação legitimamente cross-tenant do sistema. Se `salao_app` conseguisse
executá-la, qualquer falha de autorização na aplicação viraria criação de tenant. E afrouxar a
policy para permitir desfaria o isolamento inteiro por causa de um caso que acontece uma vez por
cliente.

**Onde é garantida.**
- Banco: `revoke insert on estabelecimento from salao_app` (V6), e a policy `manutencao`
  com `with check (true)` só para `salao_manutencao`
- Aplicação: `ProvisionarEstabelecimentoUseCase` usa `ConexaoDeManutencao`

**Rotinas.** RT-IAM-001
**Teste.** `ProvisionarEstabelecimentoIT.aplicacao_nao_cria_estabelecimento`
**Configurável?** Não · **ADR.** [0010](../../adr/0010-role-de-manutencao.md)
**Origem.** 2026-08-29

---

### RN-IAM-003 — Configuração do tenant é lida pela API do módulo, nunca por join

**Enunciado.** Nenhum módulo consulta a tabela `estabelecimento`. Fuso e política de comissão vêm
de `EstabelecimentoApi`.

**Motivo.** É a fronteira que torna a extração futura barata, e o único jeito de a configuração
poder ser cacheada num lugar só.

**Onde é garantida.** `ApplicationModules.verify()` — `estabelecimento` é tabela do módulo `iam`,
e o Modulith reprova o build se outro módulo alcançar o `internal`.
**Rotinas.** RT-IAM-001 e toda rotina que precise de fuso
**Configurável?** Não · **Origem.** 2026-08-29

---

## Erros do módulo

| Código | HTTP | Quando | Texto sugerido |
|---|---|---|---|
| `ER-IAM-DADOS_INVALIDOS` | 422 | Nome vazio, fuso ou moeda inválidos | "Confira os dados do salão: {motivo}." |
