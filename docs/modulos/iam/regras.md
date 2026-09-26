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

---

### RN-IAM-017 — A regra de comissão vale a partir da alteração, nunca para trás

**Enunciado.** Alterar `base_comissao` ou `desconto_afeta_comissao` afeta os atendimentos daí em
diante. Fechamentos já realizados não são recalculados.

**Motivo.** A configuração é lida ao vivo no cálculo. Se um fechamento consultasse a regra atual em
vez da vigente na época, trocar a base recalcularia comissões já pagas — e o profissional veria o
extrato de março mudar em maio, sem que nada tivesse acontecido em março.

**Obrigação para a Fase 2, registrada aqui porque o módulo ainda não existe:** o fechamento
(`RT-FIN-*`) precisa **gravar a regra vigente no próprio lançamento**, e o cálculo precisa ler dali,
não da configuração. Enquanto isso não existir, a trilha de auditoria de
`CONFIGURACAO_ALTERADA` é a única resposta para "qual base valia em março?" — pergunta que aparece
na primeira vez que alguém contesta o extrato.

**Onde é garantida.** Hoje: auditoria com antes/depois da regra. Na Fase 2: cópia da regra no
lançamento.
**Rotinas.** RT-CFG-001, RT-FIN-* · **Teste.**
`ConfiguracaoIT.trilha_guarda_a_regra_de_comissao`
**Configurável?** É a própria configuração
**Origem.** 2026-08-30

---

### RN-IAM-018 — Alterar a configuração invalida o cache em todas as instâncias e nesta

**Enunciado.** Salvar a configuração publica um `NOTIFY` para as demais instâncias **e** limpa o
cache local ao commitar.

**Motivo.** O ouvinte recebe o aviso de forma assíncrona, inclusive o que a própria instância
mandou — e existe uma janela em que quem escreveu continua lendo o valor velho. Em RT-CFG-001 isso
significa salvar o DDD padrão e o cadastro seguinte ainda recusar o telefone: a pessoa conclui que
não salvou. Quem clicou em salvar precisa ver o efeito.

A limpeza local é **depois do commit**: antes, uma leitura concorrente repovoaria o cache com o
valor antigo e ele ficaria até o TTL — o oposto do pretendido.

**Onde é garantida.** `InvalidadorDeCache.limparLocalAoCommitar`.
**Rotinas.** RT-CFG-001, RT-INF-007 · **Teste.**
`ConfiguracaoIT.define_o_ddd_e_destrava_o_cadastro` (só passa com a limpeza síncrona no commit)
**Configurável?** Não
**Origem.** 2026-08-30

---

### RN-IAM-019 — O agente age em nome de uma pessoa da equipe, resolvida pelo telefone na hora da
ação

**Enunciado.** O agente conversacional não tem permissão própria (`BOT` é vazio). Ele age **em
nome de** um usuário do salão cujo telefone é o do remetente, com as permissões desse usuário
(com o teto do canal, RN-IAM-020 e RN-IAM-021). A resolução acontece **no instante de executar**,
nunca guardada com a mensagem. Durante a ação, o contexto de segurança carrega as autoridades do
usuário efetivo, e a auditoria registra `ator = BOT` com `usuario_id` da pessoa.

**Motivo.** É a frase de docs/05 — *o bot não é usuário privilegiado* — em código, e é o que
elimina o *confused deputy*: mandar mensagem ao agente e conseguir o que o próprio login não
permite. A permissão é imposta pelo **mesmo** `@PreAuthorize` de cada caso de uso, e não por uma
checagem paralela no agente: a segunda cópia da autorização é a que alguém esquece de atualizar.

Resolver na hora da ação é a razão de o JWT carregar o perfil e não as permissões (RT-IAM-006):
quem é desativado entre escrever e o agente agir não age com as permissões de antes.

`emNomeDe` recebe o **telefone**, e não um `usuarioId`: com um id, quem chama escolheria em nome de
quem agir. O telefone vem de uma mensagem assinada pela Meta, que atesta o remetente.

E recusa rodar com alguém já autenticado no contexto — agir em nome de uma pessoa dentro da sessão
de outra é o confused deputy exato que a regra existe para impedir.

**Onde é garantida.** `ResolvedorDeUsuarioEfetivo` (`UsuarioEfetivoApi`) · `EmNomeDe` +
`UsuarioAtual.ehAgente` · `AuditoriaJdbc.ator`.
**Rotinas.** RT-CNV-002 · **Teste.** `UsuarioEfetivoIT.identifica_recepcao`,
`.pode_o_que_a_pessoa_pode`, `.nao_pode_o_que_a_pessoa_nao_pode`, `.resolve_na_hora_da_acao`,
`.auditoria_diz_bot`, `.contexto_restaurado`, `.recusa_com_pessoa_autenticada`
**Configurável?** Não · **Origem.** 2026-09-12

---

### RN-IAM-020 — Perfil que exige segundo fator não age pelo canal; conta que não é pessoa também
não

**Enunciado.** Não vira usuário efetivo: perfil para o qual `MapaDePermissoes.exigeMfa` é
verdadeiro (hoje ADMIN e GERENTE), mesmo com MFA inscrito; `PAINEL` e `BOT`; conta inativa; conta
com bloqueio por tentativas em curso. Todos recebem a mesma recusa,
`ER-IAM-CONTATO_NAO_IDENTIFICADO`.

**Motivo.** Na web, perfil que exige MFA **sem MFA na sessão não acessa nada** (RN-IAM-014). O
WhatsApp não tem como apresentar segundo fator. Herdar as permissões do dono pelo celular seria
contornar o MFA pela porta lateral — e um "teto" de permissões ainda seria mais do que a própria
web concede sem o código. MFA *inscrito* não é MFA *apresentado*: ter o autenticador configurado
não diz nada sobre quem está com o celular agora.

A regra é sobre `exigeMfa`, e não sobre uma lista de perfis escrita aqui: se o mapa der
`financeiro:read:all` à recepção, ela passa a exigir MFA na web e a ser recusada no canal no mesmo
dia, sem ninguém lembrar.

Bloqueio em curso é sinal de ataque à conta; o canal não pode ser a porta lateral dele.

Uma recusa só, para todos os motivos, pela razão de `CREDENCIAIS_INVALIDAS`: distinguir diria a
quem sonda que o número é da equipe, e com qual perfil. O motivo existe (`MotivoDaRecusa`) para o
diagnóstico, nunca para a resposta ao remetente.

**Onde é garantida.** `PoliticaDoUsuarioEfetivo.avaliar`.
**Rotinas.** RT-CNV-002 · **Teste.** `PoliticaDoUsuarioEfetivoTest.segundo_fator_para_todo_perfil`,
`.contas_que_nao_sao_pessoas`, `.inativo`, `.bloqueio`,
`UsuarioEfetivoIT.admin_recusado_mesmo_com_mfa`, `.inativo`, `.bloqueado`
**Configurável?** Não · **Origem.** 2026-09-12

---

### RN-IAM-021 — O canal não carrega permissão cujo escopo o sistema não consegue impor

**Enunciado.** As permissões do usuário efetivo são as do perfil **menos** as de escopo próprio
(sufixo `:own`). Nunca mais do que o perfil tem.

**Motivo.** `agenda:read:own` promete "só a própria agenda", mas nenhum caso de uso confere o
dono: não existe vínculo entre `usuario` e `profissional` para saber qual é a própria. Na web isso
é pendência conhecida; pelo agente, é um prompt injection pedindo "mostre a agenda de todo mundo" e
recebendo (R-10). Devolver o `:own` ao canal só faz sentido no dia em que o vínculo existir e os
casos de uso conferirem o dono — antes disso seria trocar o nome do buraco.

O teto só tira. Uma política que acrescentasse permissão seria o confused deputy por outra porta.

**Onde é garantida.** `PoliticaDoUsuarioEfetivo.permissoesPeloCanal`.
**Rotinas.** RT-CNV-002 · **Teste.** `PoliticaDoUsuarioEfetivoTest.sem_escopo_proprio`,
`.profissional`, `.nunca_mais_que_o_perfil`, `UsuarioEfetivoIT.profissional_sem_escopo_proprio`
**Configurável?** Não · **Origem.** 2026-09-12

---

### RN-IAM-022 — Telefone do usuário: só dígitos, único por salão, comparação exata

**Enunciado.** `usuario.telefone` é opcional, em E.164 só dígitos, normalizado a partir do que se
digita com as regras de `Telefone`. É único por estabelecimento, **inclusive entre contas
inativas**. A identificação compara o número exato do remetente — sem aproximação, nem "com ou sem
o nono dígito". Ligar ou tirar o telefone de uma conta exige `usuario:manage`, e a auditoria guarda
o número anterior e o novo, inteiros.

**Motivo.** Dois usuários com o mesmo telefone tornariam impossível saber com as permissões de
quem o agente age: a ambiguidade é recusada no cadastro, não resolvida na conversa. Por salão, e não
global, porque quem trabalha em dois salões tem duas contas e um WhatsApp só — e o webhook já sabe
o salão antes de perguntar o remetente (RN-CNV-003). Incluindo inativos, porque um índice
`where ativo` faria reativar uma conta falhar por unicidade, num caso de uso que não tem nada a ver
com telefone.

Comparação exata porque aproximação em identidade que concede permissão é como nasce a
personificação: o número parecido é, por definição, outro número.

Ligar telefone parece cadastro e é gesto de permissão: dá a quem escreve daquele número as
permissões daquela conta. Daí `usuario:manage`, e daí a trilha com o número inteiro — ela existe
para responder "quem apontou este telefone para esta conta", e mascarada não responde.

**Onde é garantida.** `V51__telefone_do_usuario.sql` (check e índice
`um_telefone_por_usuario_no_salao`) · `DefinirTelefoneDoUsuarioUseCase` ·
`ResolvedorDeUsuarioEfetivo.identificarPorTelefone`.
**Rotinas.** RT-CNV-002 · **Teste.** `UsuarioEfetivoIT.telefone_em_uso`,
`.mesmo_telefone_em_dois_saloes`, `.remover_telefone`, `.telefone_invalido`, `.sem_permissao`,
`.auditoria_do_telefone`, `.sem_nono_digito`, `.isolamento`
**Configurável?** Não · **Origem.** 2026-09-12

### RN-IAM-023 — Contato para clientes: telefone normalizado ou link https, opcional

**Enunciado.** `estabelecimento.contato_para_clientes` é opcional. Telefone sai normalizado como todo
telefone do sistema, com o DDD padrão do salão. Link, só `https://`, com host de verdade, até 200
caracteres. `http://` é recusado com mensagem própria.

**Motivo.** É o que o aviso de IA oferece a quem escreve para o número do assistente (RN-CNV-027), e é o
que a cliente toca. Um link `http` poderia ser trocado no caminho.

**Onde é garantida.** `DadosDoEstabelecimento` · check `contato_para_clientes_valido` (`V57`).
**Rotinas.** RT-CFG-001, RT-CNV-008 · **Teste.** `DadosDoEstabelecimentoTest.contato_*`,
`ConfiguracaoIT.contato_para_clientes`
**Configurável?** É a própria configuração · **Origem.** 2026-09-19

---

### RN-IAM-024 — Nome e e-mail se corrigem; e-mail novo encerra as sessões

**Enunciado.** `usuario:manage` corrige nome e e-mail de uma conta. Corrigir **só o nome** não
mexe nas sessões; trocar o e-mail revoga todas as sessões daquela pessoa. Perfil, ativação e senha
não passam por aqui: cada um tem caminho próprio, com trava própria.

**Motivo.** O índice de e-mail é global (V7). Sem esta operação, um e-mail digitado errado no
cadastro deixa a pessoa sem conseguir entrar **e** ocupa o endereço certo para sempre — a única
saída seria `update` no banco. O caso é frequente o bastante (é um campo digitado uma vez, por
outra pessoa) para não depender de acesso ao banco.

As sessões caem no e-mail porque o e-mail é o identificador de entrada: a sessão aberta foi obtida
com uma credencial que não existe mais. No nome não caem porque nome não é credencial — derrubar a
recepção no meio do expediente para consertar um acento seria punição por conserto.

**Onde é garantida.** `EditarUsuarioUseCase` · `UsuariosJdbc.editar`.
**Rotinas.** RT-IAM-009 · **Teste.** `TelaDeAcessosIT.corrigir_nome_nao_derruba_sessao`,
`.trocar_email_derruba_sessoes`, `.email_repetido_nao_diz_onde`, `.sem_permissao_nao_edita`
**Erro.** `ER-IAM-EMAIL_JA_CADASTRADO` (409) · **Configurável?** Não
**Origem.** 2026-09-25

---

### RN-IAM-025 — O administrador define a senha de outra pessoa, nunca a própria

**Enunciado.** `usuario:manage` define uma senha nova para outra conta, sem apresentar a senha
antiga dela, e todas as sessões daquela pessoa caem. Sobre a **própria** conta a operação é
recusada: o caminho é `PUT /v1/me/senha`, que exige a senha atual.

**Motivo.** Esquecer a senha é mais comum do que perder o celular, e o segundo já tinha conserto
(`resetar-mfa`, RT-IAM-007). Sem o primeiro, quem esquece a senha fica de fora do sistema e a
saída é mexer no banco.

A recusa sobre si mesmo é o que mantém de pé a exigência da senha atual. Sem ela, quem sentasse no
computador do balcão com a sessão do dono aberta trocaria a senha do dono **sem saber a senha do
dono** — exatamente o cenário que RT-IAM-007 cita ao exigi-la. É a mesma trava de RN-IAM-015,
aplicada a uma operação que não é rebaixamento nem desativação, e por isso escrita aqui.

É operação perigosa e o desenho reconhece, como em `resetar-mfa`: exige `usuario:manage`, encerra
as sessões do alvo, audita e registra em log de aviso. Confirmar a identidade de quem pediu é de
quem administra — nenhum sistema resolve isso sozinho.

**Onde é garantida.** `DefinirSenhaDoUsuarioUseCase` (`GestaoDeUsuarios.recusarSobreSiMesmo`).
**Rotinas.** RT-IAM-009 · **Teste.** `TelaDeAcessosIT.define_senha_de_outra_pessoa`,
`.senha_curta_recusada`, `.nao_define_a_propria_senha`, `.sem_permissao_nao_define_senha`
**Erro.** `ER-IAM-OPERACAO_SOBRE_SI_MESMO` (422) · **Configurável?** Não
**Origem.** 2026-09-25
