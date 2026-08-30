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
| `ER-IAM-CREDENCIAIS_INVALIDAS` | 401 | Senha errada, e-mail inexistente ou usuário inativo (RN-IAM-006) | "E-mail ou senha incorretos." |
| `ER-IAM-ACESSO_BLOQUEADO` | 429 | Bloqueio progressivo ativo (RN-IAM-005) | "Muitas tentativas. Tente novamente em alguns minutos." |
| `ER-IAM-SESSAO_EXPIRADA` | 401 | Refresh desconhecido, expirado, revogado ou reusado (RN-IAM-007) | "Sua sessão expirou. Entre novamente." |

---

### RN-IAM-004 — E-mail identifica o usuário globalmente, não por estabelecimento

**Enunciado.** `usuario.email_normalizado` tem índice único **global**. Duas pessoas em
estabelecimentos diferentes não podem compartilhar e-mail.

**Motivo.** O login é só e-mail e senha — a pessoa não escolhe o salão numa lista antes de entrar.
Para isso funcionar, o e-mail precisa determinar o estabelecimento sozinho.

**Preço assumido.** Quem trabalhe em dois salões do mesmo sistema precisa de dois e-mails. A
alternativa seria identificar o estabelecimento por subdomínio ou por um campo a mais na tela —
mais infraestrutura e mais fricção para um caso raro. **Se ele deixar de ser raro, é esta a
decisão a revisitar.**

**Onde é garantida.** Índice `usuario_email_unico` (V7).
**Rotinas.** RT-IAM-002, RT-IAM-007 · **Teste.** `AutenticacaoIT.logins_nao_se_misturam`
**Configurável?** Não · **Origem.** 2026-08-29

---

### RN-IAM-005 — Bloqueio progressivo, com teto

**Enunciado.** Até 4 falhas consecutivas não bloqueiam. A partir da 5ª, o bloqueio começa em 30s e
dobra a cada falha, limitado a 15 minutos. Um login bem-sucedido zera o contador.

**Motivo.** Bloqueio fixo tem os dois defeitos ao mesmo tempo: curto demais não atrapalha um ataque
automatizado; longo demais transforma a recepcionista que errou a senha duas vezes num chamado de
suporte no meio do expediente. **O teto existe para o bloqueio não virar negação de serviço**
contra um usuário legítimo cujo e-mail alguém resolveu atacar — sem ele, o ataque falharia em
entrar e teria sucesso em derrubar.

**Onde é garantida.** Domínio: `PoliticaDeBloqueio`. Contagem no **banco**, não em memória: em
memória, duas instâncias contariam metade cada, e subir uma terceira afrouxaria a proteção.

**Rotinas.** RT-IAM-002 · **Testes.** `PoliticaDeBloqueioTest` (4) e
`AutenticacaoIT.bloqueia_apos_falhas_consecutivas`
**Erro.** `ER-IAM-ACESSO_BLOQUEADO` (429) · **Configurável?** Ainda não — ver pendências
**Origem.** 2026-08-29

---

### RN-IAM-006 — Falha de login nunca revela se o e-mail existe

**Enunciado.** Senha errada, e-mail inexistente e usuário inativo devolvem o mesmo código, a mesma
mensagem e custam o mesmo tempo.

**Motivo.** Distinguir entrega de graça a resposta para "este e-mail existe aqui?", que é o
primeiro passo de qualquer ataque de credenciais. O custo de tempo importa tanto quanto a
mensagem: sem conferir a senha contra um hash descartável quando o e-mail não existe, a resposta
voltaria em milissegundos contra centenas — e essa diferença é mensurável de fora.

**Onde é garantida.** `AutenticarUseCase` — código único `ER-IAM-CREDENCIAIS_INVALIDAS` e
`hashDeReferencia`.
**Rotinas.** RT-IAM-002 · **Teste.** `AutenticacaoIT.nao_permite_enumerar_usuarios`
**Configurável?** Não · **Origem.** 2026-08-29

---

### RN-IAM-007 — Refresh é de uso único, e reapresentá-lo revoga a família

**Enunciado.** Cada renovação invalida o refresh usado e emite outro na mesma família. Um refresh
já rotacionado que reapareça — fora da janela de tolerância — revoga **todos** os tokens da
família.

**Motivo.** Um token de uso único que volta só tem uma explicação: duas partes têm o mesmo token.
Não há como saber qual é a legítima, então as duas perdem. Sem rotação, um refresh roubado
funcionaria trinta dias em silêncio.

**Onde é garantida.** `RenovarAcessoUseCase` + `UPDATE` condicional em `RefreshTokensJdbc`.
**Rotinas.** RT-IAM-003 · **Testes.** `RenovacaoIT.reuso_revoga_a_familia`,
`RenovacaoIT.corrida_e_arbitrada_pelo_banco`
**Erro.** `ER-IAM-SESSAO_EXPIRADA` (401) · **Configurável?** Só a janela de tolerância
**Origem.** 2026-08-29

---

### RN-IAM-008 — Corrida e reenvio não são vazamento

**Enunciado.** Reapresentação dentro da janela de tolerância (10s), e perda da corrida no `UPDATE`
condicional, são recusadas **sem** revogar a família.

**Motivo.** Cliente com rede instável reenvia. Tratar isso como vazamento derrubaria a sessão de
quem só teve internet ruim — um falso positivo caro, e frequente. Recusar já basta: quem fez a
primeira requisição já recebeu o par novo.

**Onde é garantida.** `RenovarAcessoUseCase.tratarReapresentacao`.
**Rotinas.** RT-IAM-003 · **Teste.** `RenovacaoIT.corrida_e_arbitrada_pelo_banco`
**Configurável?** Sim — `app.auth.refresh.tolerancia-de-reenvio`
**Origem.** 2026-08-29
