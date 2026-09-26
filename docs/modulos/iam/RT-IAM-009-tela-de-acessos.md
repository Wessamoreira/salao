---
id: RT-IAM-009
titulo: "Tela de acessos: usuário, perfil e exigência de segundo fator"
modulo: iam
fase: 7
perfil: completo
status: implementado
depende_de: [RT-IAM-002, RT-IAM-006, RT-IAM-007, RT-CNV-002, RT-INF-010]
permissoes: [usuario:manage]
eventos: []
regras: [RN-IAM-013, RN-IAM-014, RN-IAM-015, RN-IAM-016, RN-IAM-022, RN-IAM-024, RN-IAM-025]
atualizado_em: 2026-09-25
---

# RT-IAM-009 — Quem entra no sistema

## 1. Objetivo

Criar o login da recepção sem `curl` — e mostrar, numa tela só, quem entra, com que perfil, e
quem ainda vai ter de configurar o segundo fator.

## 2. Contexto de negócio

Hoje o salão só tem os logins que o provisionamento criou. Dar acesso à recepção exige uma chamada
HTTP na mão, e é o que impede o sistema de sair de uma pessoa só.

Nove endpoints de usuário estão entregues e testados desde a Fase 0. Nenhum tem tela.

E, como no catálogo e na equipe, a falta de tela escondeu buracos — dois deles com efeito
permanente:

**Um e-mail digitado errado queima o endereço.** O índice de e-mail é global (V7). Se o acesso da
Ana foi criado como `ana@salão.com`, ela nunca entra, e criar de novo com o endereço certo é a
saída — mas o errado fica ocupado para sempre, e ninguém consegue corrigi-lo. Não existe editar
usuário: só criar, promover, ativar e desativar.

**Quem esquece a senha fica de fora.** O administrador pode resetar o segundo fator de alguém
(RT-IAM-007 fechou essa pendência de propósito), mas não pode definir uma senha nova. A metade
mais provável do problema — esquecer a senha é muito mais comum do que perder o celular — não tem
caminho de volta que não seja `update` no banco.

## 3. Atores e permissões

| Ator | Permissão exigida | Escopo | Observação |
|---|---|---|---|
| Dono | `usuario:manage` | tenant | tudo desta tela |
| Gerente | — | — | não entra: `usuario:manage` não está no perfil dele, de propósito |

O menu "Acessos" aparece no grupo **Sistema**, ao lado de Configurações — é onde se mexe no
sistema, não no dia. Quem tem `usuario:manage` já é quem o servidor deixa fazer tudo que a tela
oferece; a tela não decide nada (RN-IAM-013).

## 4. Pré-condições

- [x] Estabelecimento provisionado com um administrador (RT-IAM-001)

## 5. Fluxo principal

1. O dono abre **Acessos**. Vê quem entra: nome, e-mail, perfil, segundo fator e último acesso.
2. **Novo acesso**: nome, e-mail, perfil e senha inicial.
3. Escolhe o perfil numa lista em que cada um diz, em uma linha, para que serve — e marca os que
   exigem segundo fator.
4. Gera a senha inicial num clique, copia, e manda para a pessoa.
5. A pessoa entra. Se o perfil exigir segundo fator, o próprio sistema a leva para a configuração
   antes de qualquer outra coisa (RN-IAM-014) — não é etapa desta tela.

## 6. Fluxos alternativos

**A1 — Corrigir nome ou e-mail.** RN-IAM-024. Trocar o e-mail encerra as sessões da pessoa;
corrigir só o nome, não.

**A2 — E-mail repetido.** O servidor recusa com a mesma frase esteja o e-mail neste salão ou em
outro (RT-IAM-007) — e a tela mostra a frase dele, sem tentar adivinhar onde está o conflito.

**A3 — Esqueci a senha.** O administrador define uma nova (RN-IAM-025), a tela mostra a senha uma
vez para ser copiada, e todas as sessões daquela pessoa caem.

**A4 — Perdi o celular do segundo fator.** Resetar o segundo fator (RT-IAM-007). A tela diz o que
isso significa antes de fazer: a próxima entrada dela vai reconfigurar o aplicativo.

**A5 — Sobre a própria conta, não.** Rebaixar-se, desativar-se ou trocar a própria senha por aqui
é recusado (RN-IAM-015 e RN-IAM-025). A tela **mostra o motivo na linha**, em vez de um botão
desabilitado sem explicação.

**A6 — O último administrador.** O servidor recusa (RN-IAM-015) e a tela mostra a frase dele:
"Promova outro administrador antes de remover este."

**A7 — Telefone do agente.** Ligar um telefone a uma conta dá a quem escreve daquele número as
permissões dela (RN-IAM-022). A tela trata isso como o que é — um gesto de permissão —, não como
um campo de cadastro.

**A8 — A própria senha.** Fora desta tela, no menu da conta: `/seguranca/senha` exige a senha
atual e vale para qualquer perfil, inclusive os que não entram em Acessos. `POST /v1/me/senha`
existia desde a Fase 0 e **não tinha por onde ser chamado** — ninguém no sistema conseguia trocar
a própria senha.

## 7. Regras de negócio aplicadas

| ID | Resumo em uma linha | Garantida em |
|---|---|---|
| RN-IAM-013 | Autorização olha permissão, nunca perfil | `@PreAuthorize` nos casos de uso |
| RN-IAM-014 | A obrigatoriedade de MFA é imposta pelo backend | `PoliticaDoUsuarioEfetivo` |
| RN-IAM-015 | Não se opera sobre si mesmo; o salão nunca fica sem administrador | `GestaoDeUsuarios` |
| RN-IAM-016 | Mudança de acesso encerra as sessões do usuário | casos de uso de gestão |
| RN-IAM-022 | Telefone do usuário é gesto de permissão | `DefinirTelefoneDoUsuarioUseCase` |
| **RN-IAM-024** | **Nome e e-mail se corrigem; e-mail novo encerra as sessões** | `EditarUsuarioUseCase` |
| **RN-IAM-025** | **O administrador define a senha de outra pessoa, nunca a própria** | `DefinirSenhaDoUsuarioUseCase` |

## 8. O que este documento decide de desenho

**O perfil se escolhe lendo o que ele faz, não decorando o nome.** "GERENTE" não diz se vê o
financeiro do salão. A lista de perfis chega do servidor com rótulo, uma linha de resumo, a marca
de quem exige segundo fator e — sob demanda — o que o perfil pode, em frases.

**As frases vêm do servidor, e não de um dicionário no front.** Pela mesma razão que os menus vêm
(RT-IAM-006): uma cópia do mapa de permissões em JavaScript diverge na primeira permissão nova, em
silêncio. No servidor, um teste prova que **toda** constante de `Permissao` tem frase — garantia
que um dicionário no front não teria.

**A senha inicial é uma frase, e a tela a gera.** O administrador digita a senha de outra pessoa,
e o que ele digita sozinho é `Salao@2026`. Quatro palavras sorteadas com `crypto.getRandomValues`
dão cerca de 32 bits — pouco para um hash roubado, muito para o que protege esta conta de
verdade: o bloqueio progressivo depois de quatro erros (RN-IAM-005) e, nos perfis que mandam no
dinheiro, o segundo fator obrigatório. Uma senha que se dita ao telefone e se digita sem errar
vale mais do que dezesseis caracteres aleatórios que a pessoa vai colar num bilhete.

**A senha aparece uma vez, e a tela diz isso.** Depois de criada, não há como lê-la de novo — o
banco guarda o hash. A tela mostra e-mail e senha juntos para copiar, com a frase, em vez de
deixar a pessoa descobrir isso ao recarregar.

**"Nunca entrou" é informação, e a lista mostra.** Uma conta criada há duas semanas sem nenhum
acesso quase sempre quer dizer que a senha não chegou. É o tipo de coisa que uma planilha não
conta e que o sistema sabe.

**O que não dá para fazer diz por quê, na linha.** A conta de quem está olhando aparece marcada
como "você", com a explicação de que a própria conta se mexe em outro lugar — e não como um botão
cinza sem motivo.

## 9. Contrato de API

```http
GET    /api/v1/perfis                       ← NOVO
GET    /api/v1/usuarios
POST   /api/v1/usuarios
PUT    /api/v1/usuarios/{id}                ← NOVO (RN-IAM-024)
POST   /api/v1/usuarios/{id}/senha          ← NOVO (RN-IAM-025)
PATCH  /api/v1/usuarios/{id}/perfil
PATCH  /api/v1/usuarios/{id}/ativacao
POST   /api/v1/usuarios/{id}/resetar-mfa
PUT    /api/v1/usuarios/{id}/telefone
```

**O catálogo de perfis**

```http
GET /api/v1/perfis
[
  {
    "perfil": "RECEPCAO",
    "rotulo": "Recepção",
    "resumo": "O balcão: agenda de todos, comanda e cliente. Não vê comissão nem custo.",
    "exigeMfa": false,
    "podeFazer": ["ver a agenda de todo o salão", "dar desconto de até 10%", "…"]
  }
]
```

`BOT` não entra na lista: não é perfil atribuível a pessoas (RT-IAM-007), e oferecê-lo numa tela
seria convidar o erro que o servidor recusa.

**Erros**

| Código | Quando |
|---|---|
| `ER-IAM-DADOS_INVALIDOS` | 422 — nome ou e-mail vazio, senha com menos de 12 caracteres |
| `ER-IAM-EMAIL_JA_CADASTRADO` | 409 — e-mail em uso, sem dizer onde |
| `ER-IAM-OPERACAO_SOBRE_SI_MESMO` | 422 — rebaixar, desativar ou trocar a senha da própria conta |
| `ER-IAM-ULTIMO_ADMINISTRADOR` | 422 — o salão ficaria sem administrador |
| `ER-IAM-TELEFONE_EM_USO` | 409 — o telefone já é de outra conta deste salão |

## 10. Dados

Nenhuma migration: `nome`, `email`, `email_normalizado` e `senha_hash` já existem em `usuario`.

**Sem controle de versão nas edições**, ao contrário das fichas de serviço e de profissional. O
módulo `iam` já decidiu assim: `alterarPerfil`, `definirAtivacao` e `trocarSenha` incrementam
`versao` sem conferi-la. São cinco a vinte contas, editadas raramente, e um conflito aqui
significaria dois administradores corrigindo o mesmo nome no mesmo segundo. Seguir o idioma do
módulo vale mais do que repetir o de outro.

## 11. Testes

| O que prova | Onde |
|---|---|
| Toda permissão do catálogo tem frase, e toda a ordem de leitura é coberta | `CatalogoDePerfisTest` |
| `BOT` não aparece no catálogo | `CatalogoDePerfisTest` |
| Corrigir o e-mail encerra as sessões; corrigir só o nome, não (RN-IAM-024) | `TelaDeAcessosIT` |
| E-mail repetido é recusado sem dizer onde | `TelaDeAcessosIT` |
| O administrador define a senha de outra pessoa e as sessões dela caem | `TelaDeAcessosIT` |
| A própria senha não se troca por aqui (RN-IAM-025) | `TelaDeAcessosIT` |
| Quem não gerencia usuário não edita nem define senha | `TelaDeAcessosIT` |
| A tela lista, cria, corrige, promove, desativa e reseta o segundo fator | `Acessos.test.tsx` |
| A senha gerada tem o mínimo do servidor e não se repete | `senhaSugerida.test.ts` |
| A própria conta explica por que não se mexe nela | `Acessos.test.tsx` |
| Quem nunca entrou não é cobrada por segundo fator | `Acessos.test.tsx` |
| Só a caixa do e-mail não promete desconexão | `Acessos.test.tsx` |
| "Copiado" só quando copiou de verdade | `Acessos.test.tsx` |
| Sem violação estrutural de acessibilidade | `Acessos.test.tsx` |

## 12. O que foi construído

| Peça | Onde |
|---|---|
| Catálogo de perfis com rótulo, resumo e frases | `CatalogoDePerfis` · `GET /v1/perfis` |
| Corrigir nome e e-mail | `EditarUsuarioUseCase` · `PUT /v1/usuarios/{id}` |
| Administrador define senha nova | `DefinirSenhaDoUsuarioUseCase` · `POST /v1/usuarios/{id}/senha` |
| Menu "Acessos" para quem tem `usuario:manage` | `ConsultarCapacidadesUseCase` |
| A tela | `frontend/src/telas/acessos/` · rota `/acessos` |
| Trocar a própria senha | `MinhaSenha` · rota `/seguranca/senha`, no menu da conta |
| Prévia sem backend | `npm run previa:acessos` |

**Testes.** 11 de integração (`TelaDeAcessosIT`), 5 de unidade (`CatalogoDePerfisTest`) e 24 de
tela (`Acessos.test.tsx` e `senhaSugerida.test.ts`), incluindo acessibilidade com axe.

**Rodada de mutação: 13 mutações, 13 mortas** — e duas delas acharam defeito, não teste fraco.
A frase "nunca entrou" estava escrita em dois lugares (o gancho e a tela), e o ramo do gancho era
código morto: virou um lugar só. E a ordem das pendências estava errada — quem **nunca entrou**
aparecia sendo cobrada por segundo fator, que ela não tem como configurar antes de conseguir
entrar. A senha é que não chegou, e é isso que a linha diz agora.

## 13. Pendências

- [ ] **Recuperar a senha sozinha** ("esqueci minha senha" na tela de entrada) não existe: hoje
      quem esquece depende de um administrador. Exige o módulo `notificacao`, como o convite.
- [ ] **Convite por e-mail** em vez de o administrador definir e comunicar a senha. Continua sendo
      a fraqueza conhecida de RT-IAM-007; a tela melhora a senha, não o canal por onde ela viaja.
- [ ] **Forçar a troca no primeiro acesso** — só faz sentido junto do convite.
- [ ] **Vincular usuário a profissional**: `PROFISSIONAL` é um perfil sem ligação com o cadastro de
      quem executa serviço, e enquanto for assim `agenda:*:own` não tem como filtrar. É o que
      bloqueia dar login individual a cada profissional.
- [ ] **Ver a trilha de auditoria pela tela** (pendência de RT-IAM-008): estas operações já são
      auditadas, e não há por onde lê-las que não seja SQL.
- [ ] **Sessões abertas de uma pessoa** não são visíveis nem encerráveis uma a uma; só caem em
      bloco, como efeito das operações de acesso.
