---
id: RT-IAM-003
titulo: Refresh rotativo com detecção de reuso
modulo: iam
fase: 0
perfil: completo
status: implementado
depende_de: [RT-IAM-002]
permissoes: []
eventos: []
regras: [RN-IAM-007, RN-IAM-008]
atualizado_em: 2026-08-29
---

# RT-IAM-003 — Refresh rotativo com detecção de reuso

## 1. Objetivo

Manter a sessão viva além dos 15 minutos do access token, e **detectar quando um token vazou**.

## 2. A rotação é o que torna o vazamento detectável

Um refresh de uso único deixa rastro: depois de trocado, nunca deveria voltar. Quando volta, só há
uma explicação — **duas partes têm o mesmo token**.

Não dá para saber qual delas é a legítima. Por isso a resposta é derrubar a **família inteira** e
exigir login: o atacante perde o acesso e o usuário legítimo perde a sessão. Entre errar para um
lado e para o outro, este é o lado certo.

Sem rotação, um refresh roubado funcionaria por trinta dias em silêncio e nada no sistema teria
como notar.

## 3. Decisões

### Opaco, e não JWT

Um JWT vale enquanto não expira; invalidá-lo antes exigiria uma lista de revogação consultada a
cada uso — ou seja, o banco, que é o que o JWT existia para evitar. Como o refresh **precisa** ser
revogável por natureza, guardar uma referência opaca é mais simples e mais honesto. De quebra,
interceptá-lo não revela usuário, estabelecimento nem perfil.

### SHA-256, não Argon2 — o oposto da senha

| | Senha | Refresh token |
|---|---|---|
| Origem | Escolhida por humano, pouca entropia | 256 bits de CSPRNG |
| Ameaça | Dicionário e força bruta | Nenhuma — não há padrão a explorar |
| Hash | **Lento** (Argon2id) | **Rápido** (SHA-256) |

E há um impedimento prático: Argon2 usa sal por linha, o que torna impossível procurar por hash.
Toda validação viraria varredura da tabela inteira.

### Família por login

Cada login abre uma família nova. Entrar no computador **não** derruba a sessão do celular: são
cadeias independentes, e o reuso detectado numa não afeta a outra.

### O banco arbitra a corrida

```sql
update refresh_token set usado_em = now()
 where id = :id and usado_em is null and revogado_em is null
```

Duas renovações simultâneas com o mesmo token: exatamente uma atualiza, a outra recebe zero
linhas. Sem essa cláusula, ambas rotacionariam e a cadeia se dividiria em duas famílias válidas —
o oposto do que a detecção de reuso garante. Mesma filosofia da exclusion constraint da agenda.

**A ordem dentro da transação importa:** marcar o antigo como usado vem **antes** de criar o novo.
Ao contrário, se a marcação perdesse a corrida, o token recém-criado já estaria valendo e ficaria
órfão — válido, sem ninguém do outro lado esperando por ele.

### A janela de tolerância

Cliente com rede instável reenvia. Duas requisições com o mesmo refresh chegam quase juntas: uma
rotaciona, a outra encontra o token já usado — e seria classificada como vazamento, derrubando a
sessão de quem só teve internet ruim.

Dentro de 10 segundos, a segunda é **recusada sem revogar a família**. Recusar já basta: quem fez
a primeira já recebeu o par novo. Fora da janela, aí sim é vazamento.

Perder a corrida no `UPDATE` também **não** conta como reuso, pelo mesmo motivo.

## 4. O cookie

`HttpOnly` · `Secure` · `SameSite=Strict` · `Path=/api/v1/auth`. Cada atributo responde a um
ataque diferente, e o `Path` reduz a superfície: o token só é enviado onde é usado.

**O refresh nunca vai no corpo da resposta** — devolvê-lo no JSON anularia o `HttpOnly`, bastando
um XSS para levar o token de trinta dias. `AutenticacaoWebIT` verifica isso.

## 5. Desativar usuário corta a renovação

O access token não é revogável. Cortar aqui garante que, em no máximo 15 minutos, o acesso acaba
de fato — e a família é revogada junto.

## 6. Testes

- [x] `RenovacaoIT` — 8: rotaciona e invalida o anterior · cadeia de rotações · **reuso revoga a
      família** · famílias independentes · desconhecido · expirado · usuário desativado ·
      **corrida arbitrada pelo banco, sem revogar a família**
- [x] `AutenticacaoWebIT` — 4: atributos do cookie · refresh nunca no corpo · rotação pelo cookie ·
      rota desconhecida exige autenticação

## 7. O que a implementação revelou

**A suíte de testes esgotou as conexões do Postgres.** Ao rodar tudo junto, três classes falharam
com `remaining connection slots are reserved for roles with the SUPERUSER attribute` — enquanto
passavam isoladas.

A causa: o Spring cria **um contexto por conjunto distinto de propriedades**, e cada contexto
mantém os próprios pools (aplicação e manutenção) mais a conexão de `LISTEN` do cache. Com pool
padrão de 20 e a suíte crescendo, o total passou de 100.

Corrigido dos dois lados: pool de 5 por padrão em teste (nenhum teste precisa de 20) e
`max_connections=300` no contêiner. **Isso provavelmente explica a falha isolada e não
identificada registrada em RT-IAM-002** — exaustão de conexões é intermitente conforme a ordem
em que os contextos sobem.

O sintoma não apontava para a causa: "não consigo conectar" numa classe que nada tem a ver com a
que abriu as conexões.

## 8. Pendências

- [ ] **Token CSRF** no endpoint de refresh. A proteção hoje é `SameSite=Strict`, que impede o
      navegador de enviar o cookie em requisição de outro site. O token é defesa em profundidade e
      entra quando houver front para carregá-lo — configuração de segurança não testada é pior que
      pendência declarada
- [ ] Expurgo de refresh vencido: a tabela cresce e a role de manutenção já tem `delete`; falta o
      job (mesmo padrão de RT-INF-005)
- [ ] Alerta em `auth.refresh.reuso.detectado > 0` — a métrica existe, o alerta não
- [ ] Limite de famílias por usuário: hoje cada login abre uma, e nada as encerra além do prazo
- [ ] Logout, que revoga a família explicitamente (RT-IAM-004)
