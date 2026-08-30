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
| `ER-IAM-SEGUNDO_FATOR_INVALIDO` | 401 | Desafio, TOTP ou código de recuperação inválidos (RN-IAM-011/012) | "Código de verificação inválido." |
| `ER-IAM-MFA_NAO_INSCRITO` | 422 | Confirmar ou desativar sem inscrição | "Configure o segundo fator antes." |
| `ER-IAM-EMAIL_JA_CADASTRADO` | 409 | E-mail já em uso (índice global) | "Este e-mail já está em uso." |
| `ER-IAM-OPERACAO_SOBRE_SI_MESMO` | 422 | RN-IAM-015 | "Peça a outro administrador para fazer isso na sua conta." |
| `ER-IAM-ULTIMO_ADMINISTRADOR` | 422 | RN-IAM-015 | "Promova outro administrador antes de remover este." |
| `ER-IAM-SENHA_ATUAL_INCORRETA` | 422 | Troca de senha | "A senha atual não confere." |

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

---

### RN-IAM-009 — Logout nunca falha e nunca revela

**Enunciado.** Logout com refresh desconhecido, já revogado, vazio ou ausente termina em sucesso
(204), com a mesma resposta do logout bem-sucedido.

**Motivo.** Dois. De produto: não há nada que o usuário possa fazer a respeito, e sair de uma
sessão que já não existe é o resultado que ele queria. De segurança: responder diferente para
token válido e inválido faria do logout um **oráculo para testar tokens**.

**Onde é garantida.** `EncerrarSessaoUseCase.encerrar` — retorno silencioso em todos os casos.
**Rotinas.** RT-IAM-004
**Testes.** `EncerramentoIT.logout_nunca_falha`, `AutenticacaoWebIT.logout_sem_cookie`
**Configurável?** Não · **Origem.** 2026-08-29

---

### RN-IAM-010 — Inscrever no segundo fator não o ativa

**Enunciado.** Gerar o segredo TOTP apenas o guarda. O MFA só passa a valer depois que o usuário
apresenta um código válido.

**Motivo.** Ativar na inscrição trancaria para fora quem digitasse o segredo errado no
autenticador — e o único jeito de voltar seria um administrador.

**Onde é garantida.** `mfa_credencial.confirmado_em` nulo até `SegundoFatorUseCase.confirmar`.
**Rotinas.** RT-IAM-005 · **Teste.** `SegundoFatorIT.inscricao_nao_ativa`
**Configurável?** Não · **Origem.** 2026-08-29

---

### RN-IAM-011 — Código TOTP não pode ser reapresentado

**Enunciado.** A janela usada com sucesso é registrada, e só se aceita contador estritamente
maior. Código de recuperação é de uso único.

**Motivo.** Um TOTP vale trinta segundos; sem isso, quem o interceptasse poderia reapresentá-lo
dentro da janela.

**Onde é garantida.** `UPDATE` condicional em `MfaJdbc.consumirContador` — o banco arbitra.
**Rotinas.** RT-IAM-005 · **Teste.** `SegundoFatorIT.codigo_nao_pode_ser_reapresentado`
**Erro.** `ER-IAM-SEGUNDO_FATOR_INVALIDO` (401) · **Configurável?** Não · **Origem.** 2026-08-29

---

### RN-IAM-012 — O desafio de segundo fator não é credencial de acesso

**Enunciado.** Token com a claim `escopo` é recusado no `Authorization`; e `concluirLogin` só
aceita token com `escopo = mfa-pendente`.

**Motivo.** O desafio é um JWT assinado por nós. Sem a primeira barreira, apresentá-lo no
`Authorization` daria acesso a quem passou só pela senha — o MFA viraria teatro. Sem a segunda, um
access token antigo ainda válido permitiria pular a senha.

**Onde é garantida.** `SegurancaConfig.validadorDeEscopo` e
`SegundoFatorUseCase.decodificarDesafio`.
**Rotinas.** RT-IAM-005 · **Teste.** `SegundoFatorIT.access_token_nao_serve_de_desafio`
**Configurável?** Não · **Origem.** 2026-08-29

---

### RN-IAM-013 — Autorização olha permissão, nunca perfil

**Enunciado.** Nenhum ponto do sistema decide por perfil. Todo controle usa permissão no formato
`recurso:acao:escopo`, e o perfil é apenas um conjunto nomeado delas.

**Motivo.** Um `if (perfil == ADMIN)` espalhado torna impossível atender "a recepção daqui também
pode dar desconto até 5%" sem alterar e reimplantar o sistema — e esse pedido chega de todo salão.

**Onde é garantida.** `MapaDePermissoes` + `ConversorDePermissoes` + `@PreAuthorize` sobre
permissões. As flags de `/me/capabilities` descrevem efeito (`podeVerValorDeOutros`), não perfil.
**Rotinas.** RT-IAM-006 e toda rotina com autorização
**Testes.** `MapaDePermissoesTest`, `CapacidadesIT.flags_descrevem_efeito`
**Configurável?** Hoje não; o mapa sai para o banco quando o primeiro salão pedir
**Origem.** 2026-08-29

---

### RN-IAM-014 — A obrigatoriedade de MFA é imposta pelo backend

**Enunciado.** Usuário de perfil que exige segundo fator e não o tem ativo é recusado em toda a
API, exceto os endpoints de MFA, `/me/**` e `POST /auth/logout-all`.

**Motivo.** Como flag em `/me/capabilities`, seria só uma instrução para o front respeitar — e
quem chamasse a API diretamente entraria sem segundo fator. Esconder botão é UX, não segurança.

As exceções têm cada uma o seu motivo: as duas primeiras são o caminho para sair do bloqueio; a
terceira é ação de segurança, e bloqueá-la reduziria a segurança em nome de uma regra de segurança.

**Onde é garantida.** `SegurancaConfig.segundoFatorEmDia` (`AuthorizationManager`).
**Rotinas.** RT-IAM-005, RT-IAM-006
**Testes.** `AutorizacaoWebIT.admin_sem_mfa_e_bloqueado`,
`AutorizacaoWebIT.caminho_de_saida_permanece_aberto`
**Erro.** 403 · **Configurável?** Via `MapaDePermissoes.exigeMfa` · **Origem.** 2026-08-29

---

### RN-IAM-015 — O salão não pode ficar sem administrador

**Enunciado.** Ninguém rebaixa ou desativa a própria conta. E não se remove o último administrador
ativo, seja rebaixando ou desativando.

**Motivo.** Operar sobre si mesmo é quase sempre engano, e quem perceberia o erro é justamente
quem acabou de perder o acesso. Sem administrador ativo, a única saída seria alterar o banco à mão.

**Onde é garantida.** `GestaoDeUsuarios.recusarSobreSiMesmo` e `exigirOutroAdministrador`,
compartilhadas entre os casos de uso.
**Rotinas.** RT-IAM-007
**Testes.** `GestaoDeUsuariosIT.nao_opera_sobre_si_mesmo`,
`GestaoDeUsuariosIT.ultimo_administrador_e_protegido`
**Erros.** `ER-IAM-OPERACAO_SOBRE_SI_MESMO`, `ER-IAM-ULTIMO_ADMINISTRADOR` (422)
**Configurável?** Não · **Origem.** 2026-08-29

---

### RN-IAM-016 — Mudança de acesso encerra as sessões do usuário

**Enunciado.** Rebaixar perfil, desativar, trocar senha e resetar o segundo fator revogam todas as
sessões do usuário afetado.

**Motivo.** O access token vale 15 minutos e não é revogável. Sem encerrar as sessões, o token com
o perfil antigo continuaria valendo — irrelevante numa promoção, e exatamente a janela indesejada
num rebaixamento. Na troca de senha, cai também a sessão de quem trocou: trocar senha é o que se
faz ao suspeitar de acesso alheio.

**Onde é garantida.** `RefreshTokensJdbc.revogarTodasDoUsuario`, chamada por cada caso de uso.
**Rotinas.** RT-IAM-007
**Testes.** `GestaoDeUsuariosIT.desativar_encerra_sessoes`, `.rebaixar_encerra_sessoes`,
`.trocar_senha`
**Configurável?** Não · **Origem.** 2026-08-29

---

### RN-IAM-017 — A trilha de auditoria é imutável por permissão

**Enunciado.** A role da aplicação tem apenas `INSERT` e `SELECT` em `auditoria`. `UPDATE` e
`DELETE` foram revogados.

**Motivo.** Convenção depende de ninguém escrever o comando; permissão revogada faz o comando não
funcionar. Uma trilha alterável não serve para o que ela existe.

**Onde é garantida.** `revoke update, delete on auditoria from salao_app` (V3).
**Rotinas.** RT-IAM-008 · **Teste.** `AuditoriaIT.trilha_e_imutavel`, que assere a razão
(`permission denied`) e não apenas que houve exceção
**Configurável?** Não · **Origem.** 2026-08-29

---

### RN-IAM-018 — Auditoria commita junto com o fato

**Enunciado.** O registro é gravado na mesma transação da alteração que descreve
(`propagation = MANDATORY`). Se o negócio falha, a auditoria some junto.

**Motivo.** Registro de alteração que não commitou é uma mentira na trilha — e trilha que mente é
pior que trilha ausente, porque alguém vai acreditar nela. `MANDATORY` em vez de `REQUIRED` faz
quem esqueceu descobrir na hora.

**Onde é garantida.** `AuditoriaJdbc.registrar`.
**Rotinas.** RT-IAM-008 e toda rotina que audita
**Testes.** `AuditoriaIT.rollback_nao_deixa_rastro`, `AuditoriaIT.exige_transacao`
**Configurável?** Não · **Origem.** 2026-08-29
